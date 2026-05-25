use log::{debug, info, warn};
use opus::{Application, Channels, Encoder as OpusEncoder};
use std::sync::atomic::{AtomicU32, Ordering};

use crate::audio::capture::AudioChunk;
use crate::protocol::{AudioFrame, CODEC_FLAC, CODEC_OPUS, CODEC_PCM};

/// Audio encoder that converts raw PCM to the configured codec
pub struct Encoder {
    opus: Option<OpusEncoder>,
    codec: u8,
    sample_rate: u32,
    channels: u8,
    sequence: AtomicU32,
    opus_output_buf: Vec<u8>,
}

impl Encoder {
    pub fn new(codec: u8, sample_rate: u32, channels: u8, bitrate_kbps: u32) -> Result<Self, Box<dyn std::error::Error>> {
        let opus = if codec == CODEC_OPUS {
            let ch = match channels {
                1 => Channels::Mono,
                2 => Channels::Stereo,
                _ => return Err("Opus supports only mono or stereo".into()),
            };

            let mut encoder = OpusEncoder::new(sample_rate, ch, Application::Audio)?;
            encoder.set_bitrate(opus::Bitrate::Bits((bitrate_kbps * 1000) as i32))?;

            info!(
                "Opus encoder initialized: {}Hz, {} channels, {}kbps",
                sample_rate, channels, bitrate_kbps
            );
            Some(encoder)
        } else {
            None
        };

        if codec == CODEC_FLAC {
            info!(
                "FLAC encoder initialized: {}Hz, {} channels, lossless",
                sample_rate, channels
            );
        }

        // Max Opus frame size
        let opus_output_buf = vec![0u8; 4000];

        Ok(Encoder {
            opus,
            codec,
            sample_rate,
            channels,
            sequence: AtomicU32::new(0),
            opus_output_buf,
        })
    }

    /// Encode an audio chunk into a framed AudioFrame
    pub fn encode(&mut self, chunk: &AudioChunk) -> Result<AudioFrame, Box<dyn std::error::Error>> {
        let seq = self.sequence.fetch_add(1, Ordering::Relaxed);

        let payload = match self.codec {
            CODEC_OPUS => {
                let encoded_len = self.opus.as_mut().unwrap()
                    .encode_float(&chunk.samples, &mut self.opus_output_buf)?;
                debug!("Opus encoded: {} samples -> {} bytes", chunk.samples.len(), encoded_len);
                self.opus_output_buf[..encoded_len].to_vec()
            }
            CODEC_FLAC => {
                self.encode_flac(chunk)?
            }
            CODEC_PCM => {
                // Raw PCM: convert f32 to i16 LE bytes
                let mut bytes = Vec::with_capacity(chunk.samples.len() * 2);
                for &sample in &chunk.samples {
                    let s16 = (sample * 32767.0).clamp(-32768.0, 32767.0) as i16;
                    bytes.extend_from_slice(&s16.to_le_bytes());
                }
                bytes
            }
            _ => {
                return Err(format!("Unsupported codec: {}", self.codec).into());
            }
        };

        Ok(AudioFrame {
            sequence: seq,
            timestamp_us: chunk.timestamp_us,
            codec: self.codec,
            sample_rate: self.sample_rate,
            channels: self.channels,
            payload,
        })
    }

    /// Encode audio chunk to FLAC format using the pure-Rust flacenc crate.
    /// Each chunk is encoded as a complete FLAC stream so the client can
    /// decode each chunk independently.
    fn encode_flac(&self, chunk: &AudioChunk) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        use flacenc::component::BitRepr;
        use flacenc::error::Verify;

        // Convert f32 samples to i32 (16-bit range) for FLAC encoder
        let samples_i32: Vec<i32> = chunk.samples.iter()
            .map(|&s| (s * 32767.0).clamp(-32768.0, 32767.0) as i32)
            .collect();

        let config = flacenc::config::Encoder::default()
            .into_verified()
            .map_err(|(_cfg, e)| format!("FLAC config error: {:?}", e))?;

        let source = flacenc::source::MemSource::from_samples(
            &samples_i32,
            self.channels as usize,
            16,
            self.sample_rate as usize,
        );

        let flac_stream = flacenc::encode_with_fixed_block_size(
            &config,
            source,
            config.block_size,
        ).map_err(|e| format!("FLAC encode error: {:?}", e))?;

        let mut sink = flacenc::bitsink::ByteSink::new();
        flac_stream.write(&mut sink)
            .map_err(|e| format!("FLAC write error: {:?}", e))?;

        let output = sink.into_inner();

        debug!("FLAC encoded: {} samples -> {} bytes (ratio: {:.1}%)",
            chunk.samples.len(), output.len(),
            output.len() as f64 / (chunk.samples.len() * 2) as f64 * 100.0);

        Ok(output)
    }

    pub fn codec(&self) -> u8 {
        self.codec
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn channels(&self) -> u8 {
        self.channels
    }
}
