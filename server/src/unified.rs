use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use tokio::sync::Mutex;

#[derive(Clone)]
pub struct UnifiedConfig {
    pub mode: Mode,
    pub server_name: String,
    pub server_port: u16,
    pub server_codec: String,
    pub server_bitrate: u32,
    pub server_pin: String,
    pub receiver_addr: String,
    pub receiver_name: String,
}

#[derive(Clone, Copy, PartialEq)]
pub enum Mode {
    Server,
    Receiver,
}

impl UnifiedConfig {
    pub fn new() -> Self {
        Self {
            mode: Mode::Server,
            server_name: hostname::get()
                .map(|h| h.to_string_lossy().to_string())
                .unwrap_or_else(|_| "My PC".to_string()),
            server_port: 4953,
            server_codec: "pcm".to_string(),
            server_bitrate: 192,
            server_pin: String::new(),
            receiver_addr: String::new(),
            receiver_name: "PC Receiver".to_string(),
        }
    }
}

pub struct UnifiedApp {
    pub config: UnifiedConfig,
    pub running: Arc<AtomicBool>,
    pub client_count: Arc<AtomicUsize>,
    pub status: Arc<Mutex<String>>,
}

impl Clone for UnifiedApp {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            running: self.running.clone(),
            client_count: self.client_count.clone(),
            status: self.status.clone(),
        }
    }
}

impl Default for UnifiedApp {
    fn default() -> Self {
        Self::new()
    }
}

impl UnifiedApp {
    pub fn new() -> Self {
        Self {
            config: UnifiedConfig::new(),
            running: Arc::new(AtomicBool::new(false)),
            client_count: Arc::new(AtomicUsize::new(0)),
            status: Arc::new(Mutex::new("Ready".to_string())),
        }
    }
    
    pub async fn start_server(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        if self.running.load(Ordering::Relaxed) {
            return Ok(());
        }
        
        self.running.store(true, Ordering::Relaxed);
        let status = self.status.clone();
        
        let name = self.config.server_name.clone();
        let port = self.config.server_port;
        let codec = self.config.server_codec.clone();
        let bitrate = self.config.server_bitrate;
        let pin = self.config.server_pin.clone();
        let running = self.running.clone();
        let client_count = self.client_count.clone();
        
        std::thread::spawn(move || {
            let rt = tokio::runtime::Runtime::new().unwrap();
            rt.block_on(async {
                use crate::audio_capture::AudioCapture;
                use crate::server;
                use crate::discovery;
                use crate::web_ui;
                use crate::config::Config;
                
                async fn run_server(
                    name: String,
                    port: u16,
                    codec: String,
                    bitrate: u32,
                    pin: String,
                    running: Arc<AtomicBool>,
                    client_count: Arc<AtomicUsize>,
                    status: Arc<Mutex<String>>,
                ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
                    {
                        let mut s = status.lock().await;
                        *s = format!("Starting server on port {}...", port);
                    }
                    
                    let config = Config {
                        name: name.clone(),
                        port,
                        web_port: port + 1,
                        codec: codec.clone(),
                        bitrate,
                        sample_rate: 48000,
                        channels: 2,
                        frame_ms: 20,
                        buffer_ms: 50,
                        multicast: false,
                        multicast_addr: "239.255.77.77".to_string(),
                        multicast_port: 4955,
                        log_level: "info".to_string(),
                        pin: pin.clone(),
                        source_pid: 0,
                        no_tray: true,
                        mode: "serve".to_string(),
                        connect: None,
                        client_name: "PC".to_string(),
                    };
                    
                    let frame_samples = (config.sample_rate as usize * config.frame_ms as usize) / 1000;
                    let (mut capture, audio_rx) = AudioCapture::new(
                        config.sample_rate,
                        config.channels as u16,
                        frame_samples,
                    );
                    
                    if let Err(e) = capture.start() {
                        let err_msg = format!("{}", e);
                        let mut s = status.lock().await;
                        *s = format!("Failed: {}", err_msg);
                        running.store(false, Ordering::Relaxed);
                        return Err(err_msg.into());
                    }
                    
                    let state = server::start_server(config.clone(), audio_rx).await?;
                    
                    let mut discovery = discovery::Discovery::new()?;
                    let _ = discovery.register(&config.name, config.port, &config.codec, config.sample_rate, config.channels);
                    
                    let web_state = state.clone();
                    let web_port = config.web_port;
                    tokio::spawn(async move {
                        if let Err(e) = web_ui::start_web_ui(web_state, web_port).await {
                            log::error!("Web UI error: {}", e);
                        }
                    });
                    
                    {
                        let mut s = status.lock().await;
                        *s = format!("Server running at http://localhost:{}", port);
                    }
                    println!("Server ready at http://localhost:{}", port);
                    
                    while running.load(Ordering::Relaxed) {
                        client_count.store(state.get_clients().len(), Ordering::Relaxed);
                        tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
                    }
                    
                    capture.stop();
                    discovery.unregister();
                    
                    let mut s = status.lock().await;
                    *s = "Server stopped".to_string();
                    
                    Ok(())
                }
                
                if let Err(e) = run_server(name, port, codec, bitrate, pin, running.clone(), client_count.clone(), status.clone()).await {
                    let mut s = status.lock().await;
                    *s = format!("Server error: {}", e);
                }
            });
        });
        
        Ok(())
    }
    
    pub fn start_receiver(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        if self.running.load(Ordering::Relaxed) {
            return Ok(());
        }
        
        self.running.store(true, Ordering::Relaxed);
        let status = self.status.clone();
        
        let addr = self.config.receiver_addr.clone();
        let name = self.config.receiver_name.clone();
        let running = self.running.clone();
        
        std::thread::spawn(move || {
            let rt = tokio::runtime::Runtime::new().unwrap();
            rt.block_on(async {
                use crate::receiver;
                use crate::config::Config;
                
                async fn run_receiver(
                    addr: String,
                    name: String,
                    running: Arc<AtomicBool>,
                    status: Arc<Mutex<String>>,
                ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
                    let config = Config {
                        name: "".to_string(),
                        port: 4953,
                        web_port: 4954,
                        codec: "pcm".to_string(),
                        bitrate: 192,
                        sample_rate: 48000,
                        channels: 2,
                        frame_ms: 20,
                        buffer_ms: 50,
                        multicast: false,
                        multicast_addr: "239.255.77.77".to_string(),
                        multicast_port: 4955,
                        log_level: "info".to_string(),
                        pin: String::new(),
                        source_pid: 0,
                        no_tray: true,
                        mode: "receive".to_string(),
                        connect: Some(addr.clone()),
                        client_name: name.clone(),
                    };
                    
                    while running.load(Ordering::Relaxed) {
                        {
                            let mut s = status.lock().await;
                            *s = format!("Connecting to {}...", addr);
                        }
                        
                        if let Err(e) = receiver::start_receiver(&config).await {
                            log::error!("Connection error: {}", e);
                        }
                        
                        if running.load(Ordering::Relaxed) {
                            tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
                        }
                    }
                    
                    let mut s = status.lock().await;
                    *s = "Disconnected".to_string();
                    
                    Ok(())
                }
                
                if let Err(e) = run_receiver(addr, name, running.clone(), status.clone()).await {
                    let mut s = status.lock().await;
                    *s = format!("Receiver error: {}", e);
                }
            });
        });
        
        Ok(())
    }
    
    pub fn stop(&self) {
        self.running.store(false, Ordering::Relaxed);
    }
}
