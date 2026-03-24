use crate::audio_capture::AudioChunk;
use crate::clock_sync::ClockSyncManager;
use crate::config::Config;
use crate::encoder::Encoder;
use crate::protocol::*;

use log::{debug, error, info, warn};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::broadcast;

/// Connected client info
#[derive(Debug, Clone)]
pub struct ClientInfo {
    pub client_id: String,
    pub client_name: String,
    pub session_id: String,
    pub addr: SocketAddr,
    pub connected_at: u64,
    pub volume: f32,
    pub volume_group: Option<String>,
}

/// Volume group with linked volume
#[derive(Debug, Clone)]
pub struct VolumeGroup {
    pub name: String,
    pub volume: f32,
}

/// Shared server state
pub struct ServerState {
    pub config: Config,
    pub clients: RwLock<HashMap<String, ClientInfo>>,
    pub clock_sync: ClockSyncManager,
    pub audio_tx: broadcast::Sender<Vec<u8>>,
    pub volume_groups: RwLock<HashMap<String, VolumeGroup>>,
    pub source_pid: RwLock<u32>,
}

impl ServerState {
    pub fn client_count(&self) -> usize {
        self.clients.read().len()
    }

    pub fn get_clients(&self) -> Vec<ClientInfo> {
        self.clients.read().values().cloned().collect()
    }

    /// Validate PIN for a joining client. Returns true if auth passes.
    pub fn validate_pin(&self, provided_pin: &Option<String>) -> bool {
        if !self.config.requires_auth() {
            return true;
        }
        match provided_pin {
            Some(pin) => pin == &self.config.pin,
            None => false,
        }
    }

    /// Get all volume groups
    pub fn get_volume_groups(&self) -> HashMap<String, VolumeGroup> {
        self.volume_groups.read().clone()
    }

    /// Set volume for an entire group, returns list of affected client IDs
    pub fn set_group_volume(&self, group_name: &str, volume: f32) -> Vec<String> {
        let volume = volume.clamp(0.0, 1.0);
        // Update group
        self.volume_groups.write()
            .entry(group_name.to_string())
            .and_modify(|g| g.volume = volume)
            .or_insert(VolumeGroup { name: group_name.to_string(), volume });

        // Update all clients in this group
        let mut affected = Vec::new();
        let mut clients = self.clients.write();
        for (id, client) in clients.iter_mut() {
            if client.volume_group.as_deref() == Some(group_name) {
                client.volume = volume;
                affected.push(id.clone());
            }
        }
        affected
    }

    /// List audio processes that are producing sound
    pub fn list_audio_sources(&self) -> Vec<AudioSourceInfo> {
        use sysinfo::System;
        let mut sys = System::new();
        sys.refresh_processes(sysinfo::ProcessesToUpdate::All);

        let mut sources = vec![
            AudioSourceInfo {
                pid: 0,
                name: "System Audio".to_string(),
                window_title: "All desktop audio (loopback)".to_string(),
            },
        ];

        for (pid, process) in sys.processes() {
            let name = process.name().to_string_lossy().to_string();
            // Filter to common audio-producing apps
            if is_likely_audio_process(&name) {
                sources.push(AudioSourceInfo {
                    pid: pid.as_u32(),
                    name,
                    window_title: String::new(),
                });
            }
        }

        sources
    }
}

/// Heuristic: processes likely to produce audio
fn is_likely_audio_process(name: &str) -> bool {
    let lower = name.to_lowercase();
    // Common audio/media/browser/game processes
    lower.contains("chrome") || lower.contains("firefox") || lower.contains("edge")
        || lower.contains("spotify") || lower.contains("vlc") || lower.contains("mpv")
        || lower.contains("foobar") || lower.contains("musicbee")
        || lower.contains("discord") || lower.contains("teams") || lower.contains("zoom")
        || lower.contains("slack") || lower.contains("steam") || lower.contains("obs")
        || lower.contains("audacity") || lower.contains("brave")
        || lower.contains("opera") || lower.contains("vivaldi")
        || lower.contains("wmplayer") || lower.contains("groove")
        || lower.contains("youtube") || lower.contains("twitch")
        || lower.ends_with(".exe") // include all exe processes on Windows
}

/// Start the TCP streaming server
pub async fn start_server(
    config: Config,
    mut audio_rx: broadcast::Receiver<AudioChunk>,
) -> Result<Arc<ServerState>, Box<dyn std::error::Error + Send + Sync>> {
    let (audio_tx, _) = broadcast::channel::<Vec<u8>>(128);

    let initial_pid = config.source_pid;
    let state = Arc::new(ServerState {
        config: config.clone(),
        clients: RwLock::new(HashMap::new()),
        clock_sync: ClockSyncManager::new(),
        audio_tx: audio_tx.clone(),
        volume_groups: RwLock::new(HashMap::new()),
        source_pid: RwLock::new(initial_pid),
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
                    if encoder_state.client_count() == 0 {
                        continue; // Don't encode if no clients
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

/// Handle a single client connection
async fn handle_client(
    stream: TcpStream,
    addr: SocketAddr,
    state: Arc<ServerState>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let (mut reader, mut writer) = stream.into_split();

    // Wait for ClientJoin message
    let frame_data = read_frame(&mut reader).await?;
    let join_msg = ControlMessage::deserialize(&frame_data)?;

    let (client_id, client_name) = match join_msg {
        ControlMessage::ClientJoin { client_name, client_id, pin } => {
            // Validate PIN if required
            if !state.validate_pin(&pin) {
                let auth_req = ControlMessage::AuthRequired {
                    message: "Invalid PIN. Authentication required.".to_string(),
                };
                writer.write_all(&auth_req.serialize()).await?;
                return Err("Client failed authentication".into());
            }
            info!("Client joined: {} ({})", client_name, client_id);
            (client_id, client_name)
        }
        _ => {
            let err = ControlMessage::Error {
                message: "Expected ClientJoin message".to_string(),
            };
            writer.write_all(&err.serialize()).await?;
            return Err("Client did not send ClientJoin".into());
        }
    };

    let session_id = uuid::Uuid::new_v4().to_string();

    // Send ClientAccepted
    let accepted = ControlMessage::ClientAccepted {
        session_id: session_id.clone(),
        codec: state.config.codec.clone(),
        sample_rate: state.config.sample_rate,
        channels: state.config.channels,
    };
    writer.write_all(&accepted.serialize()).await?;

    // Register client
    let info = ClientInfo {
        client_id: client_id.clone(),
        client_name: client_name.clone(),
        session_id: session_id.clone(),
        addr,
        connected_at: now_us(),
        volume: 1.0,
        volume_group: None,
    };
    state.clients.write().insert(client_id.clone(), info);
    state.clock_sync.add_client(&client_id);

    info!("Client {} accepted, session {}", client_name, session_id);

    // Subscribe to encoded audio frames
    let mut audio_rx = state.audio_tx.subscribe();

    // Create a channel for sending individual messages back to this client
    let (client_tx, mut client_rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();

    // Spawn a reader task for control messages from client
    let reader_client_id = client_id.clone();
    let reader_state = state.clone();
    let mut reader_handle = tokio::spawn(async move {
        loop {
            match read_frame(&mut reader).await {
                Ok(data) => {
                    if data.is_empty() {
                        break;
                    }
                    match data[0] {
                        FRAME_TYPE_CONTROL => {
                            if let Ok(msg) = ControlMessage::deserialize(&data) {
                                if let ControlMessage::Ping { timestamp_us } = msg {
                                    let pong = ControlMessage::Pong {
                                        ping_timestamp_us: timestamp_us,
                                        pong_timestamp_us: now_us(),
                                    };
                                    let _ = client_tx.send(pong.serialize());
                                } else {
                                    handle_control_message(&reader_client_id, msg, &reader_state);
                                }
                            }
                        }
                        FRAME_TYPE_CLOCK_SYNC => {
                            if let Ok(sync_msg) = ClockSyncMessage::deserialize(&data) {
                                reader_state.clock_sync.process_sync_response(&reader_client_id, &sync_msg);
                            }
                        }
                        _ => {}
                    }
                }
                Err(_) => break,
            }
        }
    });

    // Stream audio frames to this client, with server-initiated keepalive pings
    let mut keepalive_interval = tokio::time::interval(tokio::time::Duration::from_secs(10));
    keepalive_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        tokio::select! {
            result = audio_rx.recv() => {
                match result {
                    Ok(frame_data) => {
                        if let Err(e) = writer.write_all(&frame_data).await {
                            debug!("Write error for {}: {}", client_name, e);
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        warn!("Client {} lagged by {} frames", client_name, n);
                    }
                    Err(broadcast::error::RecvError::Closed) => {
                        break;
                    }
                }
            }
            Some(msg_data) = client_rx.recv() => {
                if let Err(e) = writer.write_all(&msg_data).await {
                    debug!("Write error for control message {}: {}", client_name, e);
                    break;
                }
            }
            _ = keepalive_interval.tick() => {
                // Server-initiated keepalive ping to detect dead connections
                let ping = ControlMessage::Ping { timestamp_us: now_us() };
                if let Err(e) = writer.write_all(&ping.serialize()).await {
                    debug!("Keepalive write error for {}: {}", client_name, e);
                    break;
                }
            }
            _ = &mut reader_handle => {
                // Reader task ended (client disconnected)
                break;
            }
        }
    }

    // Cleanup
    state.clients.write().remove(&client_id);
    state.clock_sync.remove_client(&client_id);
    info!("Client {} disconnected", client_name);

    Ok(())
}

fn handle_control_message(client_id: &str, msg: ControlMessage, state: &ServerState) {
    match msg {
        ControlMessage::SetVolume { volume } => {
            if let Some(client) = state.clients.write().get_mut(client_id) {
                client.volume = volume.clamp(0.0, 1.0);
                info!("Client {} volume set to {:.2}", client_id, client.volume);
            }
        }
        ControlMessage::SetVolumeGroup { group_name } => {
            let mut clients = state.clients.write();
            if let Some(client) = clients.get_mut(client_id) {
                if group_name.is_empty() {
                    client.volume_group = None;
                    info!("Client {} removed from volume group", client_id);
                } else {
                    client.volume_group = Some(group_name.clone());
                    // Apply current group volume if group exists
                    if let Some(group) = state.volume_groups.read().get(&group_name) {
                        client.volume = group.volume;
                    }
                    info!("Client {} joined volume group '{}'", client_id, group_name);
                }
            }
        }
        ControlMessage::ClientLeave { .. } => {
            info!("Client {} requested disconnect", client_id);
        }
        ControlMessage::Pong { ping_timestamp_us, pong_timestamp_us: _ } => {
            let now = now_us();
            let rtt = now - ping_timestamp_us;
            debug!("Client {} RTT: {}us", client_id, rtt);
        }
        _ => {
            debug!("Unhandled control message from {}: {:?}", client_id, msg);
        }
    }
}

/// Periodically send clock sync requests to all clients
async fn clock_sync_loop(state: Arc<ServerState>) {
    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));

    loop {
        interval.tick().await;

        let _sync_msg = state.clock_sync.create_sync_request();

        // We'd need per-client writers to send sync messages.
        // For now, clock sync is initiated by clients via control channel.
        // This loop logs sync state for monitoring.
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
