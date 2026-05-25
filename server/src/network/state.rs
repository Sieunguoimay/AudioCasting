use crate::config::Config;
use crate::network::clock_sync::ClockSyncManager;
use crate::protocol::*;

use log::{info, warn};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::AtomicU32;
use std::sync::Arc;
use tokio::sync::{broadcast, mpsc};

/// Connected client info
pub struct ClientInfo {
    pub client_id: String,
    pub client_name: String,
    pub session_id: String,
    pub addr: SocketAddr,
    pub connected_at: u64,
    pub volume: f32,
    pub volume_group: Option<String>,
    /// Channel to send data to this specific client's writer task
    pub tx: mpsc::UnboundedSender<Vec<u8>>,
}

impl Clone for ClientInfo {
    fn clone(&self) -> Self {
        ClientInfo {
            client_id: self.client_id.clone(),
            client_name: self.client_name.clone(),
            session_id: self.session_id.clone(),
            addr: self.addr,
            connected_at: self.connected_at,
            volume: self.volume,
            volume_group: self.volume_group.clone(),
            tx: self.tx.clone(),
        }
    }
}

impl std::fmt::Debug for ClientInfo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ClientInfo")
            .field("client_id", &self.client_id)
            .field("client_name", &self.client_name)
            .field("session_id", &self.session_id)
            .field("addr", &self.addr)
            .field("connected_at", &self.connected_at)
            .field("volume", &self.volume)
            .field("volume_group", &self.volume_group)
            .finish()
    }
}

/// Volume group with linked volume
#[derive(Debug, Clone)]
pub struct VolumeGroup {
    pub name: String,
    pub volume: f32,
}

/// A stored text message for history
#[derive(Debug, Clone, serde::Serialize)]
pub struct StoredMessage {
    pub message_id: String,
    pub from_id: String,
    pub from_name: String,
    pub content: String,
    pub timestamp_us: u64,
}

/// State of an ongoing file transfer
#[derive(Debug, Clone, serde::Serialize)]
pub struct FileTransferState {
    pub transfer_id: String,
    pub from_id: String,
    pub from_name: String,
    pub file_name: String,
    pub file_size: u64,
    pub mime_type: String,
    pub chunk_size: u32,
    pub total_chunks: u32,
    pub chunks_received: u32,
    pub status: FileTransferStatus,
    /// Path to the temp file being written (for server-received files)
    #[serde(skip)]
    pub file_path: Option<PathBuf>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub enum FileTransferStatus {
    Pending,
    Accepted,
    Rejected,
    InProgress,
    Completed,
    Error,
}

/// Events broadcast to WebSocket (web UI) subscribers
#[derive(Clone, serde::Serialize)]
#[serde(tag = "type")]
pub enum WebEvent {
    PeakLevel { level: f32 },
    ClientChanged,
    NewMessage { message_id: String, from_id: String, from_name: String, content: String, timestamp_us: u64 },
    TransferUpdate { transfer_id: String, status: String, chunks_received: u32, total_chunks: u32 },
}

/// Shared server state
pub struct ServerState {
    pub config: Config,
    pub clients: RwLock<HashMap<String, ClientInfo>>,
    pub clock_sync: ClockSyncManager,
    pub audio_tx: broadcast::Sender<Vec<u8>>,
    pub volume_groups: RwLock<HashMap<String, VolumeGroup>>,
    pub source_pid: RwLock<u32>,
    /// Current audio peak level (0..10000 representing 0.0..1.0)
    pub peak_level: AtomicU32,
    /// Recent text messages (capped at 200)
    pub messages: RwLock<Vec<StoredMessage>>,
    /// Active file transfers
    pub active_transfers: RwLock<HashMap<String, FileTransferState>>,
    /// Directory for received files
    pub file_dir: PathBuf,
    /// Broadcast channel for web UI WebSocket events
    pub web_events: broadcast::Sender<WebEvent>,
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
        self.volume_groups.write()
            .entry(group_name.to_string())
            .and_modify(|g| g.volume = volume)
            .or_insert(VolumeGroup { name: group_name.to_string(), volume });

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

    /// Store a text message and relay it to all other connected clients
    pub fn relay_text_message(&self, msg: &ControlMessage, sender_id: &str) {
        if let ControlMessage::TextMessage {
            message_id,
            from_id,
            from_name,
            content,
            timestamp_us,
        } = msg
        {
            info!(
                "Storing message '{}' from {} ({}), ts={}",
                &content[..content.len().min(50)],
                from_name,
                from_id,
                timestamp_us
            );
            let stored = StoredMessage {
                message_id: message_id.clone(),
                from_id: from_id.clone(),
                from_name: from_name.clone(),
                content: content.clone(),
                timestamp_us: *timestamp_us,
            };
            {
                let mut messages = self.messages.write();
                messages.push(stored);
                if messages.len() > 200 {
                    let excess = messages.len() - 200;
                messages.drain(0..excess);
                }
            }

            let _ = self.web_events.send(WebEvent::NewMessage {
                message_id: message_id.clone(),
                from_id: from_id.clone(),
                from_name: from_name.clone(),
                content: content.clone(),
                timestamp_us: *timestamp_us,
            });

            let serialized = msg.serialize();
            let clients = self.clients.read();
            for (id, client) in clients.iter() {
                if id != sender_id {
                    let _ = client.tx.send(serialized.clone());
                }
            }
        }
    }

    /// Send a text message from the server (PC) to all connected clients
    pub fn send_server_message(&self, content: String) -> StoredMessage {
        let msg_id = uuid::Uuid::new_v4().to_string();
        let ts = now_us();
        let msg = ControlMessage::TextMessage {
            message_id: msg_id.clone(),
            from_id: "server".to_string(),
            from_name: self.config.name.clone(),
            content: content.clone(),
            timestamp_us: ts,
        };

        let stored = StoredMessage {
            message_id: msg_id,
            from_id: "server".to_string(),
            from_name: self.config.name.clone(),
            content,
            timestamp_us: ts,
        };

        {
            let mut messages = self.messages.write();
            messages.push(stored.clone());
            if messages.len() > 200 {
                let excess = messages.len() - 200;
                messages.drain(0..excess);
            }
        }

        let _ = self.web_events.send(WebEvent::NewMessage {
            message_id: stored.message_id.clone(),
            from_id: "server".to_string(),
            from_name: stored.from_name.clone(),
            content: stored.content.clone(),
            timestamp_us: stored.timestamp_us,
        });

        let serialized = msg.serialize();
        let clients = self.clients.read();
        for client in clients.values() {
            let _ = client.tx.send(serialized.clone());
        }

        stored
    }

    /// Get messages since a given timestamp
    pub fn get_messages_since(&self, since_us: u64) -> Vec<StoredMessage> {
        self.messages
            .read()
            .iter()
            .filter(|m| m.timestamp_us > since_us)
            .cloned()
            .collect()
    }

    /// Send data to a specific client by ID
    pub fn send_to_client(&self, client_id: &str, data: Vec<u8>) -> bool {
        if let Some(client) = self.clients.read().get(client_id) {
            client.tx.send(data).is_ok()
        } else {
            false
        }
    }

    /// Broadcast data to all connected clients
    pub fn broadcast_to_clients(&self, data: &[u8]) {
        let clients = self.clients.read();
        for client in clients.values() {
            let _ = client.tx.send(data.to_vec());
        }
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
    lower.contains("chrome") || lower.contains("firefox") || lower.contains("edge")
        || lower.contains("spotify") || lower.contains("vlc") || lower.contains("mpv")
        || lower.contains("foobar") || lower.contains("musicbee")
        || lower.contains("discord") || lower.contains("teams") || lower.contains("zoom")
        || lower.contains("slack") || lower.contains("steam") || lower.contains("obs")
        || lower.contains("audacity") || lower.contains("brave")
        || lower.contains("opera") || lower.contains("vivaldi")
        || lower.contains("wmplayer") || lower.contains("groove")
        || lower.contains("youtube") || lower.contains("twitch")
        || lower.ends_with(".exe")
}
