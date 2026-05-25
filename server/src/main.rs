mod audio;
mod config;
mod network;
mod platform;
mod protocol;
mod ui;

use clap::Parser;

fn main() {
    env_logger::Builder::new()
        .filter_level(log::LevelFilter::Info)
        .format_timestamp_millis()
        .init();

    // Headless mode: snm-sidekick (and any other embedder) passes `--headless` to
    // skip the eframe GUI and run the TCP server directly. We strip the flag before
    // letting clap parse the remaining args into Config.
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--headless") {
        let filtered: Vec<String> = args.into_iter().filter(|a| a != "--headless").collect();
        let cfg = config::Config::parse_from(filtered);
        run_headless(cfg);
        return;
    }

    let native_options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_inner_size([900.0, 650.0])
            .with_min_inner_size([700.0, 500.0])
            .with_title("DeviceLink"),
        ..Default::default()
    };

    eframe::run_native(
        "DeviceLink",
        native_options,
        Box::new(|cc| Ok(Box::new(ui::app::DeviceLinkApp::new(cc)))),
    )
    .unwrap();
}

fn run_headless(config: config::Config) {
    let rt = tokio::runtime::Runtime::new().expect("tokio runtime");
    rt.block_on(async move {
        let frame_samples = config.frame_size_samples();
        let (mut capture, audio_rx) = audio::capture::AudioCapture::new(
            config.sample_rate,
            config.channels as u16,
            frame_samples,
        );
        if let Err(e) = capture.start() {
            log::error!("audio capture start failed: {e}");
            std::process::exit(2);
        }
        log::info!("audio capture started on device: {}", capture.device_name());
        match network::server::start_server(config, audio_rx).await {
            Ok(_state) => {
                log::info!("TCP server started; running headless");
                // Park forever — the server's spawned tasks keep tokio alive.
                std::future::pending::<()>().await;
            }
            Err(e) => {
                log::error!("server start failed: {e}");
                std::process::exit(3);
            }
        }
    });
}
