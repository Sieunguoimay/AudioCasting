use log::{error, info, warn};
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::sync::broadcast;

/// Captured audio data in f32 interleaved format
#[derive(Debug, Clone)]
pub struct AudioChunk {
    pub samples: Vec<f32>,
    pub sample_rate: u32,
    pub channels: u16,
    pub timestamp_us: u64,
}

/// Audio capture engine using WASAPI loopback to capture system audio output.
pub struct AudioCapture {
    device_name: String,
    sample_rate: u32,
    channels: u16,
    sender: broadcast::Sender<AudioChunk>,
    capture_thread: Mutex<Option<std::thread::JoinHandle<()>>>,
    running: Arc<std::sync::atomic::AtomicBool>,
}

impl AudioCapture {
    pub fn new(
        desired_sample_rate: u32,
        desired_channels: u16,
        _frame_samples: usize,
    ) -> (Self, broadcast::Receiver<AudioChunk>) {
        let (sender, receiver) = broadcast::channel(64);

        let capture = AudioCapture {
            device_name: String::new(),
            sample_rate: desired_sample_rate,
            channels: desired_channels,
            sender,
            capture_thread: Mutex::new(None),
            running: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        };

        (capture, receiver)
    }

    pub fn start(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        self.start_wasapi_loopback()
    }

    #[cfg(windows)]
    fn start_wasapi_loopback(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        use std::sync::atomic::Ordering;
        use windows::Win32::Media::Audio::*;
        use windows::Win32::System::Com::*;

        // Initialize COM on this thread to get the device name
        unsafe { CoInitializeEx(None, COINIT_MULTITHREADED).ok().unwrap_or(()) };

        // Get default audio render endpoint
        let enumerator: IMMDeviceEnumerator =
            unsafe { CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)? };

        let device = unsafe { enumerator.GetDefaultAudioEndpoint(eRender, eConsole)? };

        let device_name = "Default Audio Output".to_string();

        info!("WASAPI Loopback device: {}", device_name);
        self.device_name = device_name;

        // Get mix format
        let audio_client: IAudioClient = unsafe { device.Activate(CLSCTX_ALL, None)? };
        let mix_format_ptr = unsafe { audio_client.GetMixFormat()? };
        let mix_format = unsafe { &*mix_format_ptr };

        let sample_rate = mix_format.nSamplesPerSec;
        let channels = mix_format.nChannels;
        let bits_per_sample = mix_format.wBitsPerSample;
        let block_align = mix_format.nBlockAlign;

        info!(
            "Mix format: {}Hz, {} channels, {} bits, block_align={}",
            sample_rate, channels, bits_per_sample, block_align
        );

        self.sample_rate = sample_rate;
        self.channels = channels;

        // We'll pass the format info to the capture thread
        let sender = self.sender.clone();
        let running = self.running.clone();
        running.store(true, Ordering::SeqCst);

        let capture_sample_rate = sample_rate;
        let capture_channels = channels;
        let capture_bits = bits_per_sample;
        let capture_block_align = block_align as usize;

        // Frame accumulation: 20ms worth of samples
        let frame_size = (capture_sample_rate as usize * 20 / 1000) * capture_channels as usize;

        let handle = std::thread::spawn(move || {
            // Each thread needs its own COM initialization
            unsafe { CoInitializeEx(None, COINIT_MULTITHREADED).ok().unwrap_or(()) };

            let result = (|| -> std::result::Result<(), Box<dyn std::error::Error + Send + Sync>> {
                let enumerator: IMMDeviceEnumerator =
                    unsafe { CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)? };
                let device = unsafe { enumerator.GetDefaultAudioEndpoint(eRender, eConsole)? };
                let audio_client: IAudioClient = unsafe { device.Activate(CLSCTX_ALL, None)? };

                let mix_format_ptr = unsafe { audio_client.GetMixFormat()? };

                // Initialize in loopback mode
                // AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
                const AUDCLNT_STREAMFLAGS_LOOPBACK: u32 = 0x00020000;
                let buffer_duration: i64 = 200_000; // 20ms in 100ns units

                unsafe {
                    audio_client.Initialize(
                        AUDCLNT_SHAREMODE_SHARED,
                        AUDCLNT_STREAMFLAGS_LOOPBACK,
                        buffer_duration,
                        0,
                        mix_format_ptr,
                        None,
                    )?;
                }

                let capture_client: IAudioCaptureClient =
                    unsafe { audio_client.GetService()? };

                unsafe { audio_client.Start()? };
                info!("WASAPI loopback capture started ({}Hz, {}ch, {}bit, block_align={})",
                    capture_sample_rate, capture_channels, capture_bits, capture_block_align);

                let mut accumulator: Vec<f32> = Vec::with_capacity(frame_size * 2);
                let mut total_frames_captured: u64 = 0;
                let mut log_counter: u32 = 0;

                while running.load(Ordering::SeqCst) {
                    std::thread::sleep(std::time::Duration::from_millis(5));

                    loop {
                        let packet_length = match unsafe { capture_client.GetNextPacketSize() } {
                            Ok(len) => len,
                            Err(_) => break,
                        };

                        if packet_length == 0 {
                            break;
                        }

                        let mut buffer_ptr = std::ptr::null_mut();
                        let mut num_frames = 0u32;
                        let mut flags = 0u32;
                        let mut _device_position = 0u64;
                        let mut _qpc_position = 0u64;

                        if unsafe {
                            capture_client.GetBuffer(
                                &mut buffer_ptr,
                                &mut num_frames,
                                &mut flags,
                                Some(&mut _device_position),
                                Some(&mut _qpc_position),
                            )
                        }.is_err() {
                            break;
                        }

                        if num_frames > 0 && !buffer_ptr.is_null() {
                            let total_samples = num_frames as usize * capture_channels as usize;
                            let is_silent = (flags & 0x2) != 0; // AUDCLNT_BUFFERFLAGS_SILENT

                            if is_silent {
                                // Push silence
                                accumulator.extend(std::iter::repeat(0.0f32).take(total_samples));
                            } else {
                                let byte_count = num_frames as usize * capture_block_align;
                                let raw_bytes = unsafe {
                                    std::slice::from_raw_parts(buffer_ptr, byte_count)
                                };

                                // Convert to f32 based on format
                                let float_samples = convert_to_f32(
                                    raw_bytes,
                                    capture_bits,
                                    total_samples,
                                );
                                accumulator.extend_from_slice(&float_samples);
                            }
                        }

                        total_frames_captured += num_frames as u64;
                        let _ = unsafe { capture_client.ReleaseBuffer(num_frames) };

                        // Periodic logging
                        log_counter += 1;
                        if log_counter % 200 == 0 {
                            info!("Capture stats: {} total frames, accumulator={}, frame_size={}",
                                total_frames_captured, accumulator.len(), frame_size);
                        }

                        // Emit complete frames
                        while accumulator.len() >= frame_size {
                            let frame: Vec<f32> = accumulator.drain(..frame_size).collect();
                            let chunk = AudioChunk {
                                samples: frame,
                                sample_rate: capture_sample_rate,
                                channels: capture_channels,
                                timestamp_us: crate::protocol::now_us(),
                            };
                            let _ = sender.send(chunk);
                        }
                    }
                }

                unsafe { audio_client.Stop()? };
                info!("WASAPI loopback capture stopped");
                Ok(())
            })();

            if let Err(e) = result {
                error!("WASAPI capture thread error: {}", e);
            }

            unsafe { CoUninitialize() };
        });

        *self.capture_thread.lock() = Some(handle);
        info!("Audio capture started (WASAPI loopback)");
        Ok(())
    }

    #[cfg(not(windows))]
    fn start_wasapi_loopback(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        Err("WASAPI loopback is only supported on Windows".into())
    }

    pub fn stop(&self) {
        self.running.store(false, std::sync::atomic::Ordering::SeqCst);
        if let Some(handle) = self.capture_thread.lock().take() {
            let _ = handle.join();
        }
        info!("Audio capture stopped");
    }

    pub fn device_name(&self) -> &str {
        &self.device_name
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn channels(&self) -> u16 {
        self.channels
    }
}

/// Convert raw audio bytes to f32 samples
fn convert_to_f32(bytes: &[u8], bits_per_sample: u16, total_samples: usize) -> Vec<f32> {
    match bits_per_sample {
        16 => {
            let mut samples = Vec::with_capacity(total_samples);
            for chunk in bytes.chunks_exact(2) {
                let s = i16::from_le_bytes([chunk[0], chunk[1]]);
                samples.push(s as f32 / 32768.0);
            }
            samples
        }
        32 => {
            // Could be float32 or int32 — WASAPI mix format is typically float32
            let mut samples = Vec::with_capacity(total_samples);
            for chunk in bytes.chunks_exact(4) {
                let f = f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
                samples.push(f);
            }
            samples
        }
        24 => {
            let mut samples = Vec::with_capacity(total_samples);
            for chunk in bytes.chunks_exact(3) {
                let s = ((chunk[0] as i32) | ((chunk[1] as i32) << 8) | ((chunk[2] as i32) << 16))
                    << 8 >> 8; // sign extend
                samples.push(s as f32 / 8388608.0);
            }
            samples
        }
        _ => {
            warn!("Unsupported bits per sample: {}, treating as silence", bits_per_sample);
            vec![0.0f32; total_samples]
        }
    }
}
