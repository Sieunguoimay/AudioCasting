use crate::network::state::*;
use crate::protocol::*;

use log::{debug, info, warn};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;
use tokio::sync::{broadcast, mpsc};

/// Handle a single client connection
pub async fn handle_client(
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

    // Subscribe to encoded audio frames
    let mut audio_rx = state.audio_tx.subscribe();

    // Create a channel for sending individual messages back to this client
    let (client_tx, mut client_rx) = mpsc::unbounded_channel::<Vec<u8>>();

    // Register client
    let info = ClientInfo {
        client_id: client_id.clone(),
        client_name: client_name.clone(),
        session_id: session_id.clone(),
        addr,
        connected_at: now_us(),
        volume: 1.0,
        volume_group: None,
        tx: client_tx.clone(),
    };
    state.clients.write().insert(client_id.clone(), info);
    state.clock_sync.add_client(&client_id);
    let _ = state.web_events.send(WebEvent::ClientChanged);

    info!("Client {} accepted, session {}", client_name, session_id);

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
                            match ControlMessage::deserialize(&data) {
                                Ok(msg) => {
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
                                Err(e) => {
                                    warn!("Failed to deserialize control message from {}: {}", reader_client_id, e);
                                }
                            }
                        }
                        FRAME_TYPE_CLOCK_SYNC => {
                            if let Ok(sync_msg) = ClockSyncMessage::deserialize(&data) {
                                reader_state.clock_sync.process_sync_response(&reader_client_id, &sync_msg);
                            }
                        }
                        FRAME_TYPE_FILE_DATA => {
                            if let Ok(frame) = FileDataFrame::deserialize(&data) {
                                let tid = FileDataFrame::bytes_to_uuid(&frame.transfer_id);
                                handle_file_data_chunk(&reader_state, &tid, &frame);
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
                let ping = ControlMessage::Ping { timestamp_us: now_us() };
                if let Err(e) = writer.write_all(&ping.serialize()).await {
                    debug!("Keepalive write error for {}: {}", client_name, e);
                    break;
                }
            }
            _ = &mut reader_handle => {
                break;
            }
        }
    }

    // Cleanup
    state.clients.write().remove(&client_id);
    state.clock_sync.remove_client(&client_id);
    let _ = state.web_events.send(WebEvent::ClientChanged);
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
        ref text_msg @ ControlMessage::TextMessage { .. } => {
            info!("Text message from client {}", client_id);
            state.relay_text_message(text_msg, client_id);
        }
        ControlMessage::TextMessageAck { ref message_id } => {
            debug!("TextMessageAck from {} for {}", client_id, message_id);
        }
        ControlMessage::FileOffer {
            ref transfer_id,
            ref from_id,
            ref from_name,
            ref file_name,
            file_size,
            ref mime_type,
            chunk_size,
            total_chunks,
        } => {
            info!(
                "FileOffer from {} ({}): {} ({} bytes, {} chunks)",
                from_name, from_id, file_name, file_size, total_chunks
            );
            let transfer = FileTransferState {
                transfer_id: transfer_id.clone(),
                from_id: from_id.clone(),
                from_name: from_name.clone(),
                file_name: file_name.clone(),
                file_size,
                mime_type: mime_type.clone(),
                chunk_size,
                total_chunks,
                chunks_received: 0,
                status: FileTransferStatus::Pending,
                file_path: None,
            };
            state
                .active_transfers
                .write()
                .insert(transfer_id.clone(), transfer);
        }
        ControlMessage::FileAccept { ref transfer_id } => {
            info!("FileAccept from {} for {}", client_id, transfer_id);
            if let Some(t) = state.active_transfers.write().get_mut(transfer_id) {
                t.status = FileTransferStatus::Accepted;
            }
        }
        ControlMessage::FileReject {
            ref transfer_id,
            ref reason,
        } => {
            info!(
                "FileReject from {} for {}: {}",
                client_id, transfer_id, reason
            );
            if let Some(t) = state.active_transfers.write().get_mut(transfer_id) {
                t.status = FileTransferStatus::Rejected;
            }
        }
        ControlMessage::FileComplete {
            ref transfer_id,
            ref checksum,
        } => {
            info!(
                "FileComplete from {} for {} (checksum: {})",
                client_id, transfer_id, checksum
            );
            if let Some(t) = state.active_transfers.write().get_mut(transfer_id) {
                t.status = FileTransferStatus::Completed;
            }
        }
        ControlMessage::FileError {
            ref transfer_id,
            ref message,
        } => {
            warn!(
                "FileError from {} for {}: {}",
                client_id, transfer_id, message
            );
            if let Some(t) = state.active_transfers.write().get_mut(transfer_id) {
                t.status = FileTransferStatus::Error;
            }
        }
        ControlMessage::NotificationPost {
            ref app_name,
            ref title,
            ref content,
            ..
        } => {
            crate::platform::notifications::show_notification(app_name, title, content);
        }
        ControlMessage::NotificationDismiss {
            ref notification_id,
        } => {
            debug!("Notification dismissed: {}", notification_id);
        }
        ControlMessage::TouchpadMove { dx, dy, .. } => {
            crate::platform::input::move_mouse(dx as i32, dy as i32);
        }
        ControlMessage::TouchpadGesture {
            ref gesture,
            dx: _,
            dy,
        } => match gesture.as_str() {
            "tap" => crate::platform::input::mouse_click(0),
            "two_finger_tap" | "long_press" => crate::platform::input::mouse_click(1),
            "scroll" => crate::platform::input::mouse_scroll(-dy as i32),
            _ => {}
        },
        ControlMessage::KeyboardInput {
            ref text,
            ref action,
        } => {
            if action == "text" && !text.is_empty() {
                crate::platform::input::type_text(text);
            }
        }
        _ => {
            debug!("Unhandled control message from {}: {:?}", client_id, msg);
        }
    }
}

/// Handle an incoming file data chunk — write to disk for server-destined transfers
fn handle_file_data_chunk(state: &ServerState, transfer_id: &str, frame: &FileDataFrame) {
    use std::io::Write;

    let mut transfers = state.active_transfers.write();
    let Some(transfer) = transfers.get_mut(transfer_id) else {
        warn!("File chunk for unknown transfer {}", transfer_id);
        return;
    };

    if transfer.file_path.is_none() {
        let path = state.file_dir.join(&transfer.file_name);
        transfer.file_path = Some(path);
        transfer.status = FileTransferStatus::InProgress;
    }

    if let Some(ref path) = transfer.file_path {
        let file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path);

        match file {
            Ok(mut f) => {
                if let Err(e) = f.write_all(&frame.payload) {
                    warn!("Failed to write file chunk: {}", e);
                    transfer.status = FileTransferStatus::Error;
                } else {
                    transfer.chunks_received += 1;
                    let _ = state.web_events.send(WebEvent::TransferUpdate {
                        transfer_id: transfer.transfer_id.clone(),
                        status: format!("{:?}", transfer.status),
                        chunks_received: transfer.chunks_received,
                        total_chunks: transfer.total_chunks,
                    });
                    debug!(
                        "File {} chunk {}/{} written",
                        transfer.file_name, transfer.chunks_received, transfer.total_chunks
                    );
                }
            }
            Err(e) => {
                warn!("Failed to open file for writing: {}", e);
                transfer.status = FileTransferStatus::Error;
            }
        }
    }
}
