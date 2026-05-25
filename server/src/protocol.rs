use byteorder::{BigEndian, ReadBytesExt, WriteBytesExt};
use serde::{Deserialize, Serialize};
use std::io::{self, Cursor, Read, Write};

// ─── Wire Protocol ───────────────────────────────────────────────────────────
//
// Audio Frame (server → client):
//   [0x01]                     - 1 byte  : frame type (Audio)
//   [sequence: u32]            - 4 bytes  : monotonic sequence number
//   [timestamp: u64]           - 8 bytes  : presentation timestamp (microseconds)
//   [codec: u8]                - 1 byte   : codec id (1=Opus, 2=FLAC, 3=PCM)
//   [sample_rate: u32]         - 4 bytes  : sample rate in Hz
//   [channels: u8]             - 1 byte   : channel count
//   [payload_len: u32]         - 4 bytes  : payload length
//   [payload: [u8]]            - N bytes  : encoded audio data
//
// Control Message (bidirectional):
//   [0x02]                     - 1 byte  : frame type (Control)
//   [msg_len: u32]             - 4 bytes : JSON length
//   [msg: [u8]]                - N bytes : JSON-encoded ControlMessage
//
// Clock Sync (bidirectional):
//   [0x03]                     - 1 byte  : frame type (ClockSync)
//   [payload: 24 bytes]        - T1, T2, T3 as u64 microseconds
//
// File Data (bidirectional):
//   [0x04]                     - 1 byte  : frame type (FileData)
//   [transfer_id: [u8; 16]]   - 16 bytes : UUID identifying the transfer
//   [chunk_index: u32]         - 4 bytes  : chunk sequence number
//   [chunk_len: u32]           - 4 bytes  : payload length (max 65536)
//   [payload: [u8]]            - N bytes  : raw file data
//

pub const FRAME_TYPE_AUDIO: u8 = 0x01;
pub const FRAME_TYPE_CONTROL: u8 = 0x02;
pub const FRAME_TYPE_CLOCK_SYNC: u8 = 0x03;
pub const FRAME_TYPE_FILE_DATA: u8 = 0x04;

pub const CODEC_OPUS: u8 = 1;
pub const CODEC_FLAC: u8 = 2;
pub const CODEC_PCM: u8 = 3;

pub const AUDIO_FRAME_HEADER_SIZE: usize = 1 + 4 + 8 + 1 + 4 + 1 + 4; // 23 bytes
/// Audio header bytes after the type byte (seq + ts + codec + sr + ch + len)
pub const AUDIO_FRAME_REST_SIZE: usize = AUDIO_FRAME_HEADER_SIZE - 1; // 22 bytes
/// Offset of payload_len within the rest-of-header buffer
pub const AUDIO_PAYLOAD_LEN_OFFSET: usize = AUDIO_FRAME_REST_SIZE - 4; // 18

#[derive(Debug, Clone)]
pub struct AudioFrame {
    pub sequence: u32,
    pub timestamp_us: u64,
    pub codec: u8,
    pub sample_rate: u32,
    pub channels: u8,
    pub payload: Vec<u8>,
}

impl AudioFrame {
    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(AUDIO_FRAME_HEADER_SIZE + self.payload.len());
        buf.write_u8(FRAME_TYPE_AUDIO).unwrap();
        buf.write_u32::<BigEndian>(self.sequence).unwrap();
        buf.write_u64::<BigEndian>(self.timestamp_us).unwrap();
        buf.write_u8(self.codec).unwrap();
        buf.write_u32::<BigEndian>(self.sample_rate).unwrap();
        buf.write_u8(self.channels).unwrap();
        buf.write_u32::<BigEndian>(self.payload.len() as u32).unwrap();
        buf.write_all(&self.payload).unwrap();
        buf
    }

    pub fn deserialize(data: &[u8]) -> io::Result<Self> {
        let mut cursor = Cursor::new(data);
        let frame_type = cursor.read_u8()?;
        if frame_type != FRAME_TYPE_AUDIO {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Not an audio frame"));
        }
        let sequence = cursor.read_u32::<BigEndian>()?;
        let timestamp_us = cursor.read_u64::<BigEndian>()?;
        let codec = cursor.read_u8()?;
        let sample_rate = cursor.read_u32::<BigEndian>()?;
        let channels = cursor.read_u8()?;
        let payload_len = cursor.read_u32::<BigEndian>()? as usize;
        let mut payload = vec![0u8; payload_len];
        cursor.read_exact(&mut payload)?;
        Ok(AudioFrame {
            sequence,
            timestamp_us,
            codec,
            sample_rate,
            channels,
            payload,
        })
    }
}

// ─── File Data Frame ─────────────────────────────────────────────────────────

pub const FILE_DATA_HEADER_SIZE: usize = 1 + 16 + 4 + 4; // 25 bytes
/// File data header bytes after the type byte (transfer_id + chunk_index + chunk_len)
pub const FILE_DATA_REST_SIZE: usize = FILE_DATA_HEADER_SIZE - 1; // 24 bytes
/// Offset of chunk_len within the rest-of-header buffer
pub const FILE_CHUNK_LEN_OFFSET: usize = FILE_DATA_REST_SIZE - 4; // 20
pub const FILE_CHUNK_MAX_SIZE: usize = 65536; // 64 KB

/// Clock sync payload size (T1 + T2 + T3 as u64)
pub const CLOCK_SYNC_PAYLOAD_SIZE: usize = 3 * 8; // 24 bytes
/// Full clock sync frame size including type byte
pub const CLOCK_SYNC_FRAME_SIZE: usize = 1 + CLOCK_SYNC_PAYLOAD_SIZE; // 25 bytes

/// Maximum allowed control message size (1 MB)
pub const CONTROL_MESSAGE_MAX_SIZE: usize = 1_000_000;

#[derive(Debug, Clone)]
pub struct FileDataFrame {
    pub transfer_id: [u8; 16],
    pub chunk_index: u32,
    pub payload: Vec<u8>,
}

impl FileDataFrame {
    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(FILE_DATA_HEADER_SIZE + self.payload.len());
        buf.write_u8(FRAME_TYPE_FILE_DATA).unwrap();
        buf.write_all(&self.transfer_id).unwrap();
        buf.write_u32::<BigEndian>(self.chunk_index).unwrap();
        buf.write_u32::<BigEndian>(self.payload.len() as u32).unwrap();
        buf.write_all(&self.payload).unwrap();
        buf
    }

    pub fn deserialize(data: &[u8]) -> io::Result<Self> {
        let mut cursor = Cursor::new(data);
        let frame_type = cursor.read_u8()?;
        if frame_type != FRAME_TYPE_FILE_DATA {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Not a file data frame"));
        }
        let mut transfer_id = [0u8; 16];
        cursor.read_exact(&mut transfer_id)?;
        let chunk_index = cursor.read_u32::<BigEndian>()?;
        let chunk_len = cursor.read_u32::<BigEndian>()? as usize;
        let mut payload = vec![0u8; chunk_len];
        cursor.read_exact(&mut payload)?;
        Ok(FileDataFrame {
            transfer_id,
            chunk_index,
            payload,
        })
    }

    /// Helper: convert a UUID string to the 16-byte transfer_id
    pub fn uuid_to_bytes(uuid_str: &str) -> [u8; 16] {
        uuid::Uuid::parse_str(uuid_str)
            .map(|u| *u.as_bytes())
            .unwrap_or([0u8; 16])
    }

    /// Helper: convert the 16-byte transfer_id to a UUID string
    pub fn bytes_to_uuid(bytes: &[u8; 16]) -> String {
        uuid::Uuid::from_bytes(*bytes).to_string()
    }
}

// ─── Control Messages ────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ControlMessage {
    /// Server announces its capabilities
    ServerInfo {
        name: String,
        version: String,
        codec: String,
        sample_rate: u32,
        channels: u8,
        buffer_ms: u32,
        #[serde(default)]
        requires_pin: bool,
    },
    /// Client requests to join the stream
    ClientJoin {
        client_name: String,
        client_id: String,
        #[serde(default)]
        pin: Option<String>,
    },
    /// Server acknowledges client join
    ClientAccepted {
        session_id: String,
        codec: String,
        sample_rate: u32,
        channels: u8,
    },
    /// Server rejects client (wrong PIN, etc.)
    AuthRequired {
        message: String,
    },
    /// Volume control
    SetVolume { volume: f32 },
    /// Codec change request
    SetCodec { codec: String, bitrate: u32 },
    /// Latency/buffer change
    SetBuffer { buffer_ms: u32 },
    /// Client disconnect
    ClientLeave { client_id: String },
    /// Ping for latency measurement
    Ping { timestamp_us: u64 },
    /// Pong response
    Pong { ping_timestamp_us: u64, pong_timestamp_us: u64 },
    /// Error message
    Error { message: String },
    /// Set volume group for this client
    SetVolumeGroup { group_name: String },
    /// Set volume for an entire group (server → all clients in group)
    GroupVolume { group_name: String, volume: f32 },
    /// Server sends list of available audio sources (processes)
    AudioSourceList { sources: Vec<AudioSourceInfo> },
    /// Client/web requests to change audio source
    SetAudioSource { pid: u32 },

    // ─── Text Messaging ──────────────────────────────────────────────────────
    /// Bidirectional text message (clipboard, links, notes)
    TextMessage {
        message_id: String,
        from_id: String,
        from_name: String,
        content: String,
        timestamp_us: u64,
    },
    /// Acknowledgement of a received text message
    TextMessageAck {
        message_id: String,
    },

    // ─── File Transfer ───────────────────────────────────────────────────────
    /// Sender offers a file to a recipient
    FileOffer {
        transfer_id: String,
        from_id: String,
        from_name: String,
        file_name: String,
        file_size: u64,
        mime_type: String,
        chunk_size: u32,
        total_chunks: u32,
    },
    /// Recipient accepts a file transfer
    FileAccept {
        transfer_id: String,
    },
    /// Recipient rejects a file transfer
    FileReject {
        transfer_id: String,
        reason: String,
    },
    /// Sender signals all chunks have been sent
    FileComplete {
        transfer_id: String,
        checksum: String,
    },
    /// Error during file transfer
    FileError {
        transfer_id: String,
        message: String,
    },

    // ─── Notification Relay ─────────────────────────────────────────────────
    /// Android forwards a notification to PC
    NotificationPost {
        notification_id: String,
        app_package: String,
        app_name: String,
        title: String,
        content: String,
        timestamp_us: u64,
    },
    /// Notification was dismissed on Android
    NotificationDismiss {
        notification_id: String,
    },

    // ─── Remote Input ───────────────────────────────────────────────────────
    /// Touchpad relative movement
    TouchpadMove {
        dx: f32,
        dy: f32,
        fingers: u8,
    },
    /// Touchpad gesture (tap, scroll, etc.)
    TouchpadGesture {
        gesture: String,
        dx: f32,
        dy: f32,
    },
    /// Keyboard input
    KeyboardInput {
        text: String,
        action: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioSourceInfo {
    pub pid: u32,
    pub name: String,
    pub window_title: String,
}

impl ControlMessage {
    pub fn serialize(&self) -> Vec<u8> {
        let json = serde_json::to_vec(self).unwrap();
        let mut buf = Vec::with_capacity(1 + 4 + json.len());
        buf.write_u8(FRAME_TYPE_CONTROL).unwrap();
        buf.write_u32::<BigEndian>(json.len() as u32).unwrap();
        buf.write_all(&json).unwrap();
        buf
    }

    pub fn deserialize(data: &[u8]) -> io::Result<Self> {
        let mut cursor = Cursor::new(data);
        let frame_type = cursor.read_u8()?;
        if frame_type != FRAME_TYPE_CONTROL {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Not a control frame"));
        }
        let msg_len = cursor.read_u32::<BigEndian>()? as usize;
        let mut msg_buf = vec![0u8; msg_len];
        cursor.read_exact(&mut msg_buf)?;
        serde_json::from_slice(&msg_buf)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
    }
}

#[derive(Debug, Clone)]
pub struct ClockSyncMessage {
    pub t1: u64, // Server send time
    pub t2: u64, // Client receive time
    pub t3: u64, // Client send time (response)
}

impl ClockSyncMessage {
    pub fn serialize(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(CLOCK_SYNC_FRAME_SIZE);
        buf.write_u8(FRAME_TYPE_CLOCK_SYNC).unwrap();
        buf.write_u64::<BigEndian>(self.t1).unwrap();
        buf.write_u64::<BigEndian>(self.t2).unwrap();
        buf.write_u64::<BigEndian>(self.t3).unwrap();
        buf
    }

    pub fn deserialize(data: &[u8]) -> io::Result<Self> {
        let mut cursor = Cursor::new(data);
        let frame_type = cursor.read_u8()?;
        if frame_type != FRAME_TYPE_CLOCK_SYNC {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Not a clock sync frame"));
        }
        let t1 = cursor.read_u64::<BigEndian>()?;
        let t2 = cursor.read_u64::<BigEndian>()?;
        let t3 = cursor.read_u64::<BigEndian>()?;
        Ok(ClockSyncMessage { t1, t2, t3 })
    }
}

/// Read a complete frame from a stream. Returns the raw bytes including the type byte.
pub async fn read_frame(reader: &mut (impl tokio::io::AsyncReadExt + Unpin)) -> io::Result<Vec<u8>> {
    let frame_type = reader.read_u8().await?;

    match frame_type {
        FRAME_TYPE_AUDIO => {
            let mut header = vec![frame_type];
            let mut rest = vec![0u8; AUDIO_FRAME_REST_SIZE];
            reader.read_exact(&mut rest).await?;
            header.extend_from_slice(&rest);

            let payload_len = u32::from_be_bytes([
                rest[AUDIO_PAYLOAD_LEN_OFFSET],
                rest[AUDIO_PAYLOAD_LEN_OFFSET + 1],
                rest[AUDIO_PAYLOAD_LEN_OFFSET + 2],
                rest[AUDIO_PAYLOAD_LEN_OFFSET + 3],
            ]) as usize;
            let mut payload = vec![0u8; payload_len];
            reader.read_exact(&mut payload).await?;
            header.extend_from_slice(&payload);
            Ok(header)
        }
        FRAME_TYPE_CONTROL => {
            let mut len_buf = [0u8; 4];
            reader.read_exact(&mut len_buf).await?;
            let msg_len = u32::from_be_bytes(len_buf) as usize;
            if msg_len > CONTROL_MESSAGE_MAX_SIZE {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "Control message too large"));
            }
            let mut msg = vec![0u8; msg_len];
            reader.read_exact(&mut msg).await?;

            let mut buf = vec![frame_type];
            buf.extend_from_slice(&len_buf);
            buf.extend_from_slice(&msg);
            Ok(buf)
        }
        FRAME_TYPE_CLOCK_SYNC => {
            let mut buf = vec![frame_type];
            let mut rest = vec![0u8; CLOCK_SYNC_PAYLOAD_SIZE];
            reader.read_exact(&mut rest).await?;
            buf.extend_from_slice(&rest);
            Ok(buf)
        }
        FRAME_TYPE_FILE_DATA => {
            let mut header = vec![0u8; FILE_DATA_REST_SIZE];
            reader.read_exact(&mut header).await?;

            let chunk_len = u32::from_be_bytes([
                header[FILE_CHUNK_LEN_OFFSET],
                header[FILE_CHUNK_LEN_OFFSET + 1],
                header[FILE_CHUNK_LEN_OFFSET + 2],
                header[FILE_CHUNK_LEN_OFFSET + 3],
            ]) as usize;
            if chunk_len > FILE_CHUNK_MAX_SIZE {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "File chunk too large"));
            }
            let mut payload = vec![0u8; chunk_len];
            reader.read_exact(&mut payload).await?;

            let mut buf = vec![frame_type];
            buf.extend_from_slice(&header);
            buf.extend_from_slice(&payload);
            Ok(buf)
        }
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("Unknown frame type: {}", frame_type),
        )),
    }
}

/// Get current time in microseconds (monotonic-ish, based on system time)
pub fn now_us() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_micros() as u64
}
