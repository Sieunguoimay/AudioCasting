use log::{error, info, warn};
use std::net::{SocketAddr, UdpSocket};
use tokio::sync::broadcast;

/// UDP Multicast streamer for efficient one-to-many audio delivery.
/// All clients join the multicast group and receive the same stream.
pub struct MulticastStreamer {
    socket: Option<UdpSocket>,
    multicast_addr: SocketAddr,
    enabled: bool,
}

impl MulticastStreamer {
    pub fn new(multicast_addr: &str, port: u16) -> Result<Self, Box<dyn std::error::Error>> {
        let addr: SocketAddr = format!("{}:{}", multicast_addr, port).parse()?;

        Ok(MulticastStreamer {
            socket: None,
            multicast_addr: addr,
            enabled: false,
        })
    }

    /// Start the multicast sender
    pub fn start(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let socket = UdpSocket::bind("0.0.0.0:0")?;

        // Set multicast TTL (1 = local network only)
        socket.set_multicast_ttl_v4(1)?;

        // Enable loopback for testing
        socket.set_multicast_loop_v4(false)?;

        info!("Multicast streamer bound, sending to {}", self.multicast_addr);

        self.socket = Some(socket);
        self.enabled = true;
        Ok(())
    }

    /// Send a frame via multicast
    pub fn send(&self, data: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(ref socket) = self.socket {
            socket.send_to(data, self.multicast_addr)?;
        }
        Ok(())
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    pub fn multicast_addr(&self) -> SocketAddr {
        self.multicast_addr
    }
}

/// Spawn a task that reads from the encoded audio broadcast channel
/// and forwards frames via UDP multicast
pub async fn start_multicast_forwarder(
    multicast_addr: String,
    multicast_port: u16,
    mut audio_rx: broadcast::Receiver<Vec<u8>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut streamer = MulticastStreamer::new(&multicast_addr, multicast_port)
        .map_err(|e| -> Box<dyn std::error::Error + Send + Sync> { e.to_string().into() })?;
    streamer.start()
        .map_err(|e| -> Box<dyn std::error::Error + Send + Sync> { e.to_string().into() })?;

    info!(
        "Multicast forwarder started on {}:{}",
        multicast_addr, multicast_port
    );

    loop {
        match audio_rx.recv().await {
            Ok(frame_data) => {
                // UDP has a max practical payload of ~1400 bytes for safe transmission.
                // Opus frames are typically well under this. FLAC/PCM might need fragmentation.
                if frame_data.len() > 1400 {
                    // Fragment large frames
                    // For simplicity, we skip frames that are too large.
                    // A production system would implement fragmentation.
                    warn!(
                        "Frame too large for UDP ({}), skipping. Consider using Opus codec.",
                        frame_data.len()
                    );
                    continue;
                }

                if let Err(e) = streamer.send(&frame_data) {
                    error!("Multicast send error: {}", e);
                }
            }
            Err(broadcast::error::RecvError::Lagged(n)) => {
                warn!("Multicast forwarder lagged by {} frames", n);
            }
            Err(broadcast::error::RecvError::Closed) => {
                info!("Audio channel closed, stopping multicast forwarder");
                break;
            }
        }
    }

    Ok(())
}
