use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use log::{error, info, warn};
use std::sync::{Arc, Mutex};
use std::collections::VecDeque;

/// Ring buffer for feeding decoded audio to the output stream.
pub struct AudioRingBuffer {
    buffer: VecDeque<f32>,
    capacity: usize,
}

impl AudioRingBuffer {
    pub fn new(capacity: usize) -> Self {
        AudioRingBuffer {
            buffer: VecDeque::with_capacity(capacity),
            capacity,
        }
    }

    pub fn push(&mut self, samples: &[f32]) {
        for &s in samples {
            if self.buffer.len() >= self.capacity {
                self.buffer.pop_front(); // Drop oldest on overflow
            }
            self.buffer.push_back(s);
        }
    }

    pub fn pull(&mut self, count: usize) -> Vec<f32> {
        let available = self.buffer.len().min(count);
        self.buffer.drain(..available).collect()
    }

    pub fn len(&self) -> usize {
        self.buffer.len()
    }
}

/// Audio output player using cpal.
/// Plays decoded f32 samples through the default output device.
pub struct AudioPlayback {
    stream: Option<cpal::Stream>,
    ring_buffer: Arc<Mutex<AudioRingBuffer>>,
}

impl AudioPlayback {
    /// Create and start audio playback on the default output device.
    pub fn new(sample_rate: u32, channels: u16) -> Result<Self, Box<dyn std::error::Error>> {
        let host = cpal::default_host();
        let device = host.default_output_device()
            .ok_or("No output audio device found")?;

        let device_name = device.name().unwrap_or_else(|_| "Unknown".to_string());
        info!("Output device: {}", device_name);

        let config = cpal::StreamConfig {
            channels,
            sample_rate: cpal::SampleRate(sample_rate),
            buffer_size: cpal::BufferSize::Default,
        };

        // Ring buffer: 500ms worth of audio
        let buf_size = (sample_rate as usize * channels as usize) / 2;
        let ring_buffer = Arc::new(Mutex::new(AudioRingBuffer::new(buf_size)));
        let rb_clone = ring_buffer.clone();

        let stream = device.build_output_stream(
            &config,
            move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                let mut rb = rb_clone.lock().unwrap();
                let samples = rb.pull(data.len());
                for (i, sample) in data.iter_mut().enumerate() {
                    *sample = if i < samples.len() { samples[i] } else { 0.0 };
                }
            },
            |err| {
                error!("Output stream error: {}", err);
            },
            None,
        )?;

        stream.play()?;
        info!("Audio playback started: {}Hz, {}ch", sample_rate, channels);

        Ok(AudioPlayback {
            stream: Some(stream),
            ring_buffer,
        })
    }

    /// Push decoded samples to the playback buffer.
    pub fn push_samples(&self, samples: &[f32]) {
        if let Ok(mut rb) = self.ring_buffer.lock() {
            rb.push(samples);
        }
    }

    /// Get current buffer level in samples.
    pub fn buffer_level(&self) -> usize {
        self.ring_buffer.lock().map(|rb| rb.len()).unwrap_or(0)
    }

    pub fn stop(&mut self) {
        self.stream = None;
        info!("Audio playback stopped");
    }
}
