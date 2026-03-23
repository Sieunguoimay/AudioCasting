use clap::Parser;

#[derive(Parser, Debug, Clone)]
#[command(name = "audiocast-server", about = "AudioCasting Server - Stream PC audio to Android devices")]
pub struct Config {
    /// Server name advertised via mDNS
    #[arg(long, default_value = "AudioCast Server")]
    pub name: String,

    /// TCP port for audio streaming
    #[arg(short, long, default_value_t = 4953)]
    pub port: u16,

    /// Web UI port
    #[arg(long, default_value_t = 4954)]
    pub web_port: u16,

    /// Audio codec: pcm, opus, flac
    #[arg(long, default_value = "pcm")]
    pub codec: String,

    /// Opus bitrate in kbps (64-512)
    #[arg(long, default_value_t = 192)]
    pub bitrate: u32,

    /// Sample rate in Hz
    #[arg(long, default_value_t = 48000)]
    pub sample_rate: u32,

    /// Number of audio channels (1=mono, 2=stereo)
    #[arg(long, default_value_t = 2)]
    pub channels: u8,

    /// Audio frame duration in milliseconds (10, 20, 40, 60 for Opus)
    #[arg(long, default_value_t = 20)]
    pub frame_ms: u32,

    /// Buffer size in milliseconds for clients
    #[arg(long, default_value_t = 50)]
    pub buffer_ms: u32,

    /// Enable UDP multicast streaming
    #[arg(long, default_value_t = false)]
    pub multicast: bool,

    /// Multicast group address
    #[arg(long, default_value = "239.255.77.77")]
    pub multicast_addr: String,

    /// Multicast port
    #[arg(long, default_value_t = 4955)]
    pub multicast_port: u16,

    /// Log level (trace, debug, info, warn, error)
    #[arg(long, default_value = "info")]
    pub log_level: String,

    /// PIN for client authentication (empty = no authentication)
    #[arg(long, default_value = "")]
    pub pin: String,

    /// Capture audio from a specific process ID (0 = system-wide loopback)
    #[arg(long, default_value_t = 0)]
    pub source_pid: u32,

    /// Disable system tray icon (Windows only)
    #[arg(long, default_value_t = false)]
    pub no_tray: bool,

    /// Mode: serve (default) or receive
    #[arg(long, default_value = "serve")]
    pub mode: String,

    /// Connect to server (required in receive mode), e.g. 192.168.1.5:4953
    #[arg(long)]
    pub connect: Option<String>,

    /// Client name (used in receive mode)
    #[arg(long, default_value = "PC Receiver")]
    pub client_name: String,
}

impl Config {
    pub fn frame_size_samples(&self) -> usize {
        (self.sample_rate as usize * self.frame_ms as usize) / 1000
    }

    pub fn codec_id(&self) -> u8 {
        match self.codec.as_str() {
            "opus" => crate::protocol::CODEC_OPUS,
            "flac" => crate::protocol::CODEC_FLAC,
            "pcm" => crate::protocol::CODEC_PCM,
            _ => crate::protocol::CODEC_OPUS,
        }
    }

    pub fn requires_auth(&self) -> bool {
        !self.pin.is_empty()
    }
}
