mod audio;
mod config;
mod network;
mod platform;
mod protocol;
mod ui;

fn main() {
    env_logger::Builder::new()
        .filter_level(log::LevelFilter::Info)
        .format_timestamp_millis()
        .init();

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
