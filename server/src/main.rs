mod audio_capture;
mod audio_playback;
mod clock_sync;
mod config;
mod decoder;
mod discovery;
mod encoder;
mod multicast;
mod protocol;
mod receiver;
mod server;
mod system_tray;
mod web_ui;

use clap::Parser;
use log::info;

use config::Config;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let config = Config::parse();

    // Initialize logging
    env_logger::Builder::new()
        .filter_level(config.log_level.parse().unwrap_or(log::LevelFilter::Info))
        .format_timestamp_millis()
        .init();

    match config.mode.as_str() {
        "receive" => run_receiver(&config).await,
        _ => run_server(config).await,
    }
}

/// Run in server mode (default): capture audio and stream to clients
async fn run_server(config: Config) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    use audio_capture::AudioCapture;

    info!("╔══════════════════════════════════════╗");
    info!("║       AudioCast Server v{}       ║", env!("CARGO_PKG_VERSION"));
    info!("╚══════════════════════════════════════╝");
    info!("Name:        {}", config.name);
    info!("Port:        {}", config.port);
    info!("Web UI:      http://localhost:{}", config.web_port);
    info!("Codec:       {} @ {}kbps", config.codec, config.bitrate);
    info!("Sample rate: {}Hz", config.sample_rate);
    info!("Channels:    {}", config.channels);
    info!("Frame size:  {}ms", config.frame_ms);
    info!("Buffer:      {}ms", config.buffer_ms);
    if config.requires_auth() {
        info!("PIN auth:    enabled");
    }
    if config.source_pid > 0 {
        info!("Source PID:  {}", config.source_pid);
    }

    // Start audio capture
    let frame_samples = config.frame_size_samples();
    let (mut capture, audio_rx) = AudioCapture::new(
        config.sample_rate,
        config.channels as u16,
        frame_samples,
    );

    capture.start().map_err(|e| -> Box<dyn std::error::Error + Send + Sync> {
        format!("Failed to start audio capture: {}. Make sure an audio device is available.", e).into()
    })?;

    info!("Audio capture started on: {}", capture.device_name());

    // Start the streaming server
    let state = server::start_server(config.clone(), audio_rx).await?;

    // Start multicast forwarder if enabled
    if config.multicast {
        let mc_rx = state.audio_tx.subscribe();
        let mc_addr = config.multicast_addr.clone();
        let mc_port = config.multicast_port;
        tokio::spawn(async move {
            if let Err(e) = multicast::start_multicast_forwarder(mc_addr, mc_port, mc_rx).await {
                log::error!("Multicast forwarder error: {}", e);
            }
        });
        info!(
            "Multicast enabled on {}:{}",
            config.multicast_addr, config.multicast_port
        );
    }

    // Start mDNS discovery
    let mut discovery = discovery::Discovery::new()?;
    discovery.register(
        &config.name,
        config.port,
        &config.codec,
        config.sample_rate,
        config.channels,
    )?;

    // Start web UI
    let web_state = state.clone();
    let web_port = config.web_port;
    tokio::spawn(async move {
        if let Err(e) = web_ui::start_web_ui(web_state, web_port).await {
            log::error!("Web UI error: {}", e);
        }
    });

    // Start system tray (Windows only)
    let _tray = if !config.no_tray {
        Some(system_tray::SystemTray::start(state.clone()))
    } else {
        None
    };

    info!("Server is ready. Press Ctrl+C to stop.");

    // Wait for shutdown signal
    tokio::signal::ctrl_c().await?;
    info!("Shutting down...");

    capture.stop();
    discovery.unregister();

    info!("Goodbye!");
    Ok(())
}

/// Run in receiver mode: connect to a server and play audio
async fn run_receiver(config: &Config) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    info!("╔══════════════════════════════════════╗");
    info!("║      AudioCast Receiver v{}      ║", env!("CARGO_PKG_VERSION"));
    info!("╚══════════════════════════════════════╝");
    info!("Client name: {}", config.client_name);
    info!(
        "Connecting to: {}",
        config.connect.as_deref().unwrap_or("(not specified)")
    );

    receiver::start_receiver(config).await
}
