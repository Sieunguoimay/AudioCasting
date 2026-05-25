use crate::audio::capture::AudioChunk;
use crate::audio::encoder::Encoder;
use crate::config::Config;
use crate::network::client_handler::handle_client;
use crate::network::state::*;
use crate::protocol::*;

use log::{debug, error, info, warn};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio::sync::broadcast;

// Re-export types that external code expects from this module
pub use crate::network::state::{
    ClientInfo, FileTransferState, FileTransferStatus, ServerState, StoredMessage, VolumeGroup,
    WebEvent,
};

/// Start the TCP streaming server
pub async fn start_server(
    config: Config,
    mut audio_rx: broadcast::Receiver<AudioChunk>,
) -> Result<Arc<ServerState>, Box<dyn std::error::Error + Send + Sync>> {
    let (audio_tx, _) = broadcast::channel::<Vec<u8>>(128);
    let (web_tx, _) = broadcast::channel::<WebEvent>(256);

    let initial_pid = config.source_pid;
    let file_dir = PathBuf::from("devicelink_files");
    if !file_dir.exists() {
        std::fs::create_dir_all(&file_dir).ok();
    }
    let state = Arc::new(ServerState {
        config: config.clone(),
        clients: RwLock::new(HashMap::new()),
        clock_sync: crate::network::clock_sync::ClockSyncManager::new(),
        audio_tx: audio_tx.clone(),
        volume_groups: RwLock::new(HashMap::new()),
        source_pid: RwLock::new(initial_pid),
        peak_level: AtomicU32::new(0),
        messages: RwLock::new(Vec::new()),
        active_transfers: RwLock::new(HashMap::new()),
        file_dir,
        web_events: web_tx,
    });

    // Spawn the encoder task: captures audio → encodes → broadcasts frames
    let encoder_state = state.clone();
    tokio::spawn(async move {
        let mut encoder = match Encoder::new(
            encoder_state.config.codec_id(),
            encoder_state.config.sample_rate,
            encoder_state.config.channels,
            encoder_state.config.bitrate,
        ) {
            Ok(e) => e,
            Err(e) => {
                error!("Failed to create encoder: {}", e);
                return;
            }
        };

        let mut encode_count: u64 = 0;
        loop {
            match audio_rx.recv().await {
                Ok(chunk) => {
                    let peak = chunk.samples.iter()
                        .map(|s| s.abs())
                        .fold(0.0f32, f32::max);
                    encoder_state.peak_level.store(
                        (peak.min(1.0) * 10000.0) as u32, Ordering::Relaxed
                    );

                    if encode_count % 3 == 0 {
                        let _ = encoder_state.web_events.send(WebEvent::PeakLevel { level: peak.min(1.0) });
                    }

                    if encoder_state.client_count() == 0 {
                        continue;
                    }

                    match encoder.encode(&chunk) {
                        Ok(frame) => {
                            let data = frame.serialize();
                            encode_count += 1;
                            if encode_count % 250 == 1 {
                                info!("Encoded frame #{}, payload={} bytes, clients={}",
                                    encode_count, data.len(), encoder_state.client_count());
                            }
                            let _ = encoder_state.audio_tx.send(data);
                        }
                        Err(e) => {
                            warn!("Encode error: {}", e);
                        }
                    }
                }
                Err(broadcast::error::RecvError::Lagged(n)) => {
                    warn!("Encoder lagged by {} frames", n);
                }
                Err(broadcast::error::RecvError::Closed) => {
                    info!("Audio capture channel closed, stopping encoder");
                    break;
                }
            }
        }
    });

    // Start TCP listener
    let addr = format!("0.0.0.0:{}", config.port);
    let listener = TcpListener::bind(&addr).await?;
    info!("TCP server listening on {}", addr);

    let listener_state = state.clone();
    tokio::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((stream, addr)) => {
                    info!("New connection from {}", addr);
                    let client_state = listener_state.clone();
                    tokio::spawn(async move {
                        if let Err(e) = handle_client(stream, addr, client_state).await {
                            error!("Client {} error: {}", addr, e);
                        }
                    });
                }
                Err(e) => {
                    error!("Accept error: {}", e);
                }
            }
        }
    });

    // Spawn clock sync task
    let sync_state = state.clone();
    tokio::spawn(async move {
        clock_sync_loop(sync_state).await;
    });

    Ok(state)
}

/// Periodically send clock sync requests to all clients
async fn clock_sync_loop(state: Arc<ServerState>) {
    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));

    loop {
        interval.tick().await;

        let _sync_msg = state.clock_sync.create_sync_request();

        let states = state.clock_sync.get_all_states();
        for (id, cs) in &states {
            if cs.sync_count > 0 {
                debug!(
                    "Client {} clock: offset={}us, rtt={}us, syncs={}",
                    id, cs.offset_us, cs.rtt_us, cs.sync_count
                );
            }
        }
    }
}
