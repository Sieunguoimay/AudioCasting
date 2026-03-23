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
mod unified;
mod web_ui;

use clap::Parser;
use log::info;

use config::Config;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    
    if args.len() == 1 {
        run_interactive_mode();
    } else {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async_main());
    }
}

async fn async_main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let config = Config::parse();

    env_logger::Builder::new()
        .filter_level(config.log_level.parse().unwrap_or(log::LevelFilter::Info))
        .format_timestamp_millis()
        .init();

    match config.mode.as_str() {
        "receive" => run_receiver(&config).await,
        _ => run_server(config).await,
    }
}

fn run_interactive_mode() {
    env_logger::Builder::new()
        .filter_level(log::LevelFilter::Info)
        .format_timestamp_millis()
        .init();
    
    let mut app = unified::UnifiedApp::new();
    let rt = tokio::runtime::Runtime::new().unwrap();
    
    println!("╔══════════════════════════════════════╗");
    println!("║     AudioCast - Unified App        ║");
    println!("╚══════════════════════════════════════╝");
    println!();
    
    loop {
        let is_running = app.running.load(std::sync::atomic::Ordering::Relaxed);
        let status = rt.block_on(app.status.lock());
        let status_str = status.clone();
        drop(status);
        
        println!();
        println!("┌─────────────────────────────────────────┐");
        println!("│  Current Mode: {}", if app.config.mode == unified::Mode::Server { "Server (Stream audio to devices)" } else { "Receiver (Receive audio from devices)" });
        println!("│  Status: {}", status_str);
        if is_running {
            if app.config.mode == unified::Mode::Server {
                println!("│  Clients: {}", app.client_count.load(std::sync::atomic::Ordering::Relaxed));
                println!("│  Web UI: http://localhost:{}", app.config.server_port + 1);
            }
        }
        println!("└─────────────────────────────────────────┘");
        println!();
        println!("  1. Switch to Server Mode");
        println!("  2. Switch to Receiver Mode");
        
        if is_running {
            println!("  3. Stop current mode");
        } else {
            println!("  3. Start current mode");
        }
        println!();
        println!("  S. Server settings");
        println!("  R. Receiver settings");
        println!("  Q. Quit");
        println!();
        print!("  Enter choice: ");
        std::io::Write::flush(&mut std::io::stdout()).unwrap();
        
        let mut input = String::new();
        if std::io::stdin().read_line(&mut input).is_ok() {
            match input.trim() {
                "1" => {
                    app.config.mode = unified::Mode::Server;
                    println!("  Switched to Server Mode");
                }
                "2" => {
                    app.config.mode = unified::Mode::Receiver;
                    println!("  Switched to Receiver Mode");
                }
                "3" => {
                    if is_running {
                        app.stop();
                        println!("  Stopped");
                    } else {
                        let mode = app.config.mode;
                        let app_clone = app.clone();
                        std::thread::spawn(move || {
                            let rt2 = tokio::runtime::Runtime::new().unwrap();
                            rt2.block_on(async {
                                match mode {
                                    unified::Mode::Server => { let _ = app_clone.start_server().await; }
                                    unified::Mode::Receiver => { let _ = app_clone.start_receiver(); }
                                }
                            });
                        });
                        println!("  Started in {} mode", match mode {
                            unified::Mode::Server => "Server",
                            unified::Mode::Receiver => "Receiver",
                        });
                    }
                }
                "S" | "s" => {
                    edit_server_settings(&mut app, &rt);
                }
                "R" | "r" => {
                    edit_receiver_settings(&mut app);
                }
                "Q" | "q" => {
                    if is_running {
                        app.stop();
                    }
                    println!("  Goodbye!");
                    break;
                }
                _ => {
                    println!("  Invalid choice");
                }
            }
        }
    }
}

fn edit_server_settings(app: &mut unified::UnifiedApp, _rt: &tokio::runtime::Runtime) {
    loop {
        println!();
        println!("┌─────────────────────────────────────────┐");
        println!("│  Server Settings");
        println!("└─────────────────────────────────────────┘");
        println!();
        println!("  1. Name: {}", app.config.server_name);
        println!("  2. Port: {}", app.config.server_port);
        println!("  3. Codec: {}", app.config.server_codec);
        println!("  4. Bitrate: {} kbps", app.config.server_bitrate);
        println!("  5. PIN: {}", if app.config.server_pin.is_empty() { "(none)" } else { &app.config.server_pin });
        println!();
        println!("  B. Back");
        println!();
        print!("  Enter setting to edit: ");
        std::io::Write::flush(&mut std::io::stdout()).unwrap();
        
        let mut input = String::new();
        if std::io::stdin().read_line(&mut input).is_ok() {
            match input.trim() {
                "1" => {
                    print!("  Enter name: ");
                    std::io::Write::flush(&mut std::io::stdout()).unwrap();
                    input.clear();
                    if std::io::stdin().read_line(&mut input).is_ok() {
                        app.config.server_name = input.trim().to_string();
                    }
                }
                "2" => {
                    print!("  Enter port (1024-65535): ");
                    std::io::Write::flush(&mut std::io::stdout()).unwrap();
                    input.clear();
                    if std::io::stdin().read_line(&mut input).is_ok() {
                        if let Ok(port) = input.trim().parse::<u16>() {
                            if port >= 1024 {
                                app.config.server_port = port;
                            }
                        }
                    }
                }
                "3" => {
                    println!("  Select codec (pcm/opus/flac): ");
                    input.clear();
                    if std::io::stdin().read_line(&mut input).is_ok() {
                        let codec = input.trim().to_lowercase();
                        if ["pcm", "opus", "flac"].contains(&codec.as_str()) {
                            app.config.server_codec = codec;
                        }
                    }
                }
                "4" => {
                    print!("  Enter bitrate (64-512 kbps): ");
                    std::io::Write::flush(&mut std::io::stdout()).unwrap();
                    input.clear();
                    if std::io::stdin().read_line(&mut input).is_ok() {
                        if let Ok(bitrate) = input.trim().parse::<u32>() {
                            if (64..=512).contains(&bitrate) {
                                app.config.server_bitrate = bitrate;
                            }
                        }
                    }
                }
                "5" => {
                    print!("  Enter PIN (empty for none): ");
                    std::io::Write::flush(&mut std::io::stdout()).unwrap();
                    input.clear();
                    if std::io::stdin().read_line(&mut input).is_ok() {
                        app.config.server_pin = input.trim().to_string();
                    }
                }
                "B" | "b" => break,
                _ => {}
            }
        }
    }
}

fn edit_receiver_settings(app: &mut unified::UnifiedApp) {
    loop {
        println!();
        println!("┌─────────────────────────────────────────┐");
        println!("│  Receiver Settings");
        println!("└─────────────────────────────────────────┘");
        println!();
        println!("  1. Your name: {}", app.config.receiver_name);
        println!("  2. Server address: {}", if app.config.receiver_addr.is_empty() { "(not set)" } else { &app.config.receiver_addr });
        println!();
        println!("  B. Back");
        println!();
        print!("  Enter setting to edit: ");
        std::io::Write::flush(&mut std::io::stdout()).unwrap();
        
        let mut input = String::new();
        if std::io::stdin().read_line(&mut input).is_ok() {
            match input.trim() {
                "1" => {
                    print!("  Enter name: ");
                    std::io::Write::flush(&mut std::io::stdout()).unwrap();
                    input.clear();
                    if std::io::stdin().read_line(&mut input).is_ok() {
                        app.config.receiver_name = input.trim().to_string();
                    }
                }
                "2" => {
                    print!("  Enter server (e.g. 192.168.1.100:4953): ");
                    std::io::Write::flush(&mut std::io::stdout()).unwrap();
                    input.clear();
                    if std::io::stdin().read_line(&mut input).is_ok() {
                        app.config.receiver_addr = input.trim().to_string();
                    }
                }
                "B" | "b" => break,
                _ => {}
            }
        }
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

    let state = server::start_server(config.clone(), audio_rx).await?;

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

    let mut discovery = discovery::Discovery::new()?;
    discovery.register(
        &config.name,
        config.port,
        &config.codec,
        config.sample_rate,
        config.channels,
    )?;

    let web_state = state.clone();
    let web_port = config.web_port;
    tokio::spawn(async move {
        if let Err(e) = web_ui::start_web_ui(web_state, web_port).await {
            log::error!("Web UI error: {}", e);
        }
    });

    let _tray = if !config.no_tray {
        Some(system_tray::SystemTray::start(state.clone()))
    } else {
        None
    };

    info!("Server is ready. Press Ctrl+C to stop.");

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
