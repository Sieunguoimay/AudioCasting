use log::{debug, info, warn};
use crate::protocol::{CODEC_FLAC, CODEC_OPUS, CODEC_PCM};

/// Audio decoder — inverse of encoder.rs.
/// Decodes received audio frames back to f32 PCM samples.
pub struct Decoder {
    opus: Option<opus::Decoder>,
    sample_rate: u32,
    channels: u8,
}

impl Decoder {
    pub fn new(codec: u8, sample_rate: u32, channels: u8) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let opus = if codec == CODEC_OPUS {
            let ch = match channels {
                1 => opus::Channels::Mono,
                2 => opus::Channels::Stereo,
                _ => return Err("Opus supports only mono or stereo".into()),
            };
            let decoder = opus::Decoder::new(sample_rate, ch)?;
            info!("Opus decoder initialized: {}Hz, {}ch", sample_rate, channels);
            Some(decoder)
        } else {
            None
        };

        Ok(Decoder {
            opus,
            sample_rate,
            channels,
        })
    }

    /// Decode encoded payload to f32 samples.
    pub fn decode(&mut self, codec: u8, payload: &[u8]) -> Result<Vec<f32>, Box<dyn std::error::Error + Send + Sync>> {
        match codec {
            CODEC_PCM => {
                // PCM i16 LE → f32
                let mut samples = Vec::with_capacity(payload.len() / 2);
                for chunk in payload.chunks_exact(2) {
                    let s = i16::from_le_bytes([chunk[0], chunk[1]]);
                    samples.push(s as f32 / 32768.0);
                }
                Ok(samples)
            }
            CODEC_OPUS => {
                let decoder = self.opus.as_mut()
                    .ok_or::<Box<dyn std::error::Error + Send + Sync>>("Opus decoder not initialized".into())?;
                // Max frame: 120ms at 48kHz stereo = 11520 samples
                let max_samples = 11520 * self.channels as usize;
                let mut output = vec![0.0f32; max_samples];
                let decoded = decoder.decode_float(payload, &mut output, false)?;
                output.truncate(decoded * self.channels as usize);
                debug!("Opus decoded: {} bytes -> {} samples", payload.len(), output.len());
                Ok(output)
            }
            CODEC_FLAC => {
                // Decode FLAC stream using claxon
                let cursor = std::io::Cursor::new(payload);
                let mut reader = claxon::FlacReader::new(cursor)
                    .map_err(|e| -> Box<dyn std::error::Error + Send + Sync> { format!("FLAC decode error: {}", e).into() })?;
                let bps = reader.streaminfo().bits_per_sample;
                let scale = (1u32 << (bps - 1)) as f32;

                let mut samples: Vec<f32> = Vec::new();
                for sample_result in reader.samples() {
                    let s = sample_result
                        .map_err(|e| -> Box<dyn std::error::Error + Send + Sync> { format!("FLAC sample error: {}", e).into() })?;
                    samples.push(s as f32 / scale);
                }

                debug!("FLAC decoded: {} bytes -> {} samples", payload.len(), samples.len());
                Ok(samples)
            }
            _ => Err(format!("Unknown codec: {}", codec).into()),
        }
    }
}
