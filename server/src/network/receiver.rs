use crate::audio::playback::AudioPlayback;
use crate::config::Config;
use crate::audio::decoder::Decoder;
use crate::protocol::*;

use log::{debug, error, info, warn};
use parking_lot::RwLock;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;

/// Shared receiver state for the web UI.
pub struct ReceiverState {
    pub server_addr: String,
    pub server_name: String,
    pub codec: String,
    pub sample_rate: u32,
    pub channels: u8,
    pub session_id: String,
    pub connected: RwLock<bool>,
    pub frame_count: AtomicU64,
    pub buffer_level: AtomicU64,
    pub decode_errors: AtomicU64,
    pub connected_at: u64,
    pub client_name: String,
}

/// Start the web UI for receiver mode.
pub async fn start_receiver_web_ui(
    state: Arc<ReceiverState>,
    port: u16,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    use axum::{extract::State, response::Html, routing::get, Json, Router};
    use serde::Serialize;

    #[derive(Serialize)]
    struct ReceiverStatus {
        client_name: String,
        server_addr: String,
        server_name: String,
        codec: String,
        sample_rate: u32,
        channels: u8,
        session_id: String,
        connected: bool,
        frame_count: u64,
        buffer_level: u64,
        decode_errors: u64,
        uptime_secs: u64,
    }

    async fn get_status(State(state): State<Arc<ReceiverState>>) -> Json<ReceiverStatus> {
        let uptime = if state.connected_at > 0 {
            (now_us() - state.connected_at) / 1_000_000
        } else {
            0
        };
        Json(ReceiverStatus {
            client_name: state.client_name.clone(),
            server_addr: state.server_addr.clone(),
            server_name: state.server_name.clone(),
            codec: state.codec.clone(),
            sample_rate: state.sample_rate,
            channels: state.channels,
            session_id: state.session_id.clone(),
            connected: *state.connected.read(),
            frame_count: state.frame_count.load(Ordering::Relaxed),
            buffer_level: state.buffer_level.load(Ordering::Relaxed),
            decode_errors: state.decode_errors.load(Ordering::Relaxed),
            uptime_secs: uptime,
        })
    }

    async fn index_page() -> Html<String> {
        Html(RECEIVER_WEB_UI_HTML.to_string())
    }

    let app = Router::new()
        .route("/", get(index_page))
        .route("/api/status", get(get_status))
        .with_state(state);

    let addr = format!("0.0.0.0:{}", port);
    info!("Receiver Web UI at http://localhost:{}", port);

    let listener = tokio::net::TcpListener::bind(&addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

/// Start the receiver: connect to a server, receive audio, decode, and play.
pub async fn start_receiver(config: &Config) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let connect_addr = config.connect.as_ref()
        .ok_or("--connect <host:port> is required in receive mode")?;

    info!("Connecting to {}...", connect_addr);

    let stream = TcpStream::connect(connect_addr).await?;
    stream.set_nodelay(true)?;
    let (mut reader, mut writer) = stream.into_split();

    info!("Connected to {}", connect_addr);

    // Send ClientJoin
    let join_msg = ControlMessage::ClientJoin {
        client_name: config.client_name.clone(),
        client_id: uuid::Uuid::new_v4().to_string(),
        pin: if config.pin.is_empty() { None } else { Some(config.pin.clone()) },
    };
    writer.write_all(&join_msg.serialize()).await?;

    // Wait for response
    let response = read_frame(&mut reader).await?;
    let ctrl = ControlMessage::deserialize(&response)?;

    let (codec_name, sample_rate, channels, session_id) = match ctrl {
        ControlMessage::ClientAccepted { session_id, codec, sample_rate, channels } => {
            info!("Session accepted: {}, codec={}, {}Hz, {}ch", session_id, codec, sample_rate, channels);
            (codec, sample_rate, channels, session_id)
        }
        ControlMessage::AuthRequired { message } => {
            error!("Authentication failed: {}", message);
            return Err(message.into());
        }
        ControlMessage::Error { message } => {
            error!("Server error: {}", message);
            return Err(message.into());
        }
        _ => {
            return Err("Unexpected response from server".into());
        }
    };

    // Determine codec ID
    let codec_id = match codec_name.as_str() {
        "opus" => CODEC_OPUS,
        "flac" => CODEC_FLAC,
        "pcm" => CODEC_PCM,
        _ => CODEC_PCM,
    };

    // Initialize decoder
    let mut decoder = Decoder::new(codec_id, sample_rate, channels)?;

    // Initialize audio playback
    let playback = AudioPlayback::new(sample_rate, channels as u16)
        .map_err(|e| -> Box<dyn std::error::Error + Send + Sync> {
            format!("Failed to start audio playback: {}", e).into()
        })?;

    // Create shared state for web UI
    let state = Arc::new(ReceiverState {
        server_addr: connect_addr.clone(),
        server_name: String::new(),
        codec: codec_name.clone(),
        sample_rate,
        channels,
        session_id,
        connected: RwLock::new(true),
        frame_count: AtomicU64::new(0),
        buffer_level: AtomicU64::new(0),
        decode_errors: AtomicU64::new(0),
        connected_at: now_us(),
        client_name: config.client_name.clone(),
    });

    // Start web UI
    let web_state = state.clone();
    let web_port = config.web_port;
    tokio::spawn(async move {
        if let Err(e) = start_receiver_web_ui(web_state, web_port).await {
            error!("Receiver web UI error: {}", e);
        }
    });

    info!("Receiver ready. Playing audio from {}...", connect_addr);

    // Main receive loop
    loop {
        match read_frame(&mut reader).await {
            Ok(data) => {
                match data[0] {
                    FRAME_TYPE_AUDIO => {
                        let frame = AudioFrame::deserialize(&data)?;
                        let count = state.frame_count.fetch_add(1, Ordering::Relaxed) + 1;

                        match decoder.decode(frame.codec, &frame.payload) {
                            Ok(samples) => {
                                playback.push_samples(&samples);
                                state.buffer_level.store(playback.buffer_level() as u64, Ordering::Relaxed);
                                if count % 250 == 1 {
                                    info!(
                                        "Frame #{}: {} samples, buffer={}",
                                        count,
                                        samples.len(),
                                        playback.buffer_level()
                                    );
                                }
                            }
                            Err(e) => {
                                state.decode_errors.fetch_add(1, Ordering::Relaxed);
                                warn!("Decode error on frame #{}: {}", count, e);
                            }
                        }
                    }
                    FRAME_TYPE_CONTROL => {
                        if let Ok(msg) = ControlMessage::deserialize(&data) {
                            match msg {
                                ControlMessage::Ping { timestamp_us } => {
                                    let pong = ControlMessage::Pong {
                                        ping_timestamp_us: timestamp_us,
                                        pong_timestamp_us: now_us(),
                                    };
                                    let _ = writer.write_all(&pong.serialize()).await;
                                }
                                _ => {
                                    debug!("Control message: {:?}", msg);
                                }
                            }
                        }
                    }
                    FRAME_TYPE_CLOCK_SYNC => {
                        if let Ok(sync) = ClockSyncMessage::deserialize(&data) {
                            let t2 = now_us();
                            let response = ClockSyncMessage {
                                t1: sync.t1,
                                t2,
                                t3: now_us(),
                            };
                            let _ = writer.write_all(&response.serialize()).await;
                        }
                    }
                    _ => {}
                }
            }
            Err(e) => {
                error!("Connection lost: {}", e);
                *state.connected.write() = false;
                break;
            }
        }
    }

    info!("Receiver stopped");
    Ok(())
}

const RECEIVER_WEB_UI_HTML: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>DeviceLink Receiver</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #0f0f23;
            color: #e0e0e0;
            min-height: 100vh;
            padding: 2rem;
        }
        h1 { color: #00d4ff; margin-bottom: 0.5rem; font-size: 1.8rem; }
        h2 { color: #888; margin-bottom: 1.5rem; font-size: 0.9rem; font-weight: normal; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1.5rem; margin-bottom: 2rem; }
        .card {
            background: #1a1a2e;
            border-radius: 12px;
            padding: 1.5rem;
            border: 1px solid #2a2a4a;
        }
        .card h3 { color: #00d4ff; margin-bottom: 1rem; font-size: 1rem; }
        .stat { display: flex; justify-content: space-between; padding: 0.5rem 0; border-bottom: 1px solid #2a2a4a; }
        .stat:last-child { border-bottom: none; }
        .stat-label { color: #888; }
        .stat-value { color: #fff; font-weight: 500; }
        .status-dot {
            display: inline-block;
            width: 10px; height: 10px;
            border-radius: 50%;
            margin-right: 8px;
            vertical-align: middle;
        }
        .status-connected { background: #4caf50; box-shadow: 0 0 8px #4caf5088; }
        .status-disconnected { background: #ff4444; }
        .big-number {
            font-size: 3rem;
            color: #00d4ff;
            font-weight: 700;
            text-align: center;
            padding: 1rem 0;
        }
        .big-label { color: #888; text-align: center; }
        #error { color: #ff4444; margin: 1rem 0; display: none; }
    </style>
</head>
<body>
    <h1>DeviceLink Receiver</h1>
    <h2 id="subtitle">Connecting...</h2>
    <div id="error"></div>

    <div class="grid">
        <div class="card">
            <h3>Connection</h3>
            <div class="stat">
                <span class="stat-label">Status</span>
                <span class="stat-value" id="status"><span class="status-dot status-disconnected"></span>Disconnected</span>
            </div>
            <div class="stat"><span class="stat-label">Server</span><span class="stat-value" id="serverAddr">-</span></div>
            <div class="stat"><span class="stat-label">Client Name</span><span class="stat-value" id="clientName">-</span></div>
            <div class="stat"><span class="stat-label">Session</span><span class="stat-value" id="session">-</span></div>
            <div class="stat"><span class="stat-label">Uptime</span><span class="stat-value" id="uptime">-</span></div>
        </div>
        <div class="card">
            <h3>Audio</h3>
            <div class="stat"><span class="stat-label">Codec</span><span class="stat-value" id="codec">-</span></div>
            <div class="stat"><span class="stat-label">Sample Rate</span><span class="stat-value" id="sampleRate">-</span></div>
            <div class="stat"><span class="stat-label">Channels</span><span class="stat-value" id="channels">-</span></div>
        </div>
    </div>

    <div class="grid">
        <div class="card">
            <h3>Frames Received</h3>
            <div class="big-number" id="frameCount">0</div>
            <div class="big-label">frames</div>
        </div>
        <div class="card">
            <h3>Buffer Level</h3>
            <div class="big-number" id="bufferLevel">0</div>
            <div class="big-label">samples</div>
        </div>
        <div class="card">
            <h3>Decode Errors</h3>
            <div class="big-number" id="decodeErrors" style="color:#4caf50;">0</div>
            <div class="big-label">errors</div>
        </div>
    </div>

    <script>
        function formatUptime(secs) {
            if (secs < 60) return secs + 's';
            if (secs < 3600) return Math.floor(secs/60) + 'm ' + (secs%60) + 's';
            return Math.floor(secs/3600) + 'h ' + Math.floor((secs%3600)/60) + 'm';
        }

        async function refresh() {
            try {
                const res = await fetch('/api/status');
                const d = await res.json();

                document.getElementById('subtitle').textContent = d.client_name;
                document.getElementById('clientName').textContent = d.client_name;
                document.getElementById('serverAddr').textContent = d.server_addr;
                document.getElementById('session').textContent = d.session_id.substring(0, 8) + '...';
                document.getElementById('codec').textContent = d.codec.toUpperCase();
                document.getElementById('sampleRate').textContent = d.sample_rate + ' Hz';
                document.getElementById('channels').textContent = d.channels === 2 ? 'Stereo' : 'Mono';
                document.getElementById('frameCount').textContent = d.frame_count.toLocaleString();
                document.getElementById('bufferLevel').textContent = d.buffer_level.toLocaleString();
                document.getElementById('uptime').textContent = formatUptime(d.uptime_secs);

                const errEl = document.getElementById('decodeErrors');
                errEl.textContent = d.decode_errors;
                errEl.style.color = d.decode_errors > 0 ? '#ff4444' : '#4caf50';

                const statusEl = document.getElementById('status');
                if (d.connected) {
                    statusEl.innerHTML = '<span class="status-dot status-connected"></span>Connected';
                } else {
                    statusEl.innerHTML = '<span class="status-dot status-disconnected"></span>Disconnected';
                }

                document.getElementById('error').style.display = 'none';
            } catch (e) {
                document.getElementById('error').textContent = 'Connection error: ' + e.message;
                document.getElementById('error').style.display = 'block';
            }
        }

        refresh();
        setInterval(refresh, 1000);
    </script>
</body>
</html>
"#;
