use egui::{Color32, RichText, Rounding, Vec2};

use crate::ui::app::{AppCommand, DeviceLinkApp};
use crate::config::Config;

pub fn show(app: &mut DeviceLinkApp, ui: &mut egui::Ui) {
    let accent = Color32::from_rgb(0, 212, 255);
    let surface = Color32::from_rgb(26, 26, 46);

    ui.add_space(8.0);
    ui.label(
        RichText::new("Settings")
            .color(accent)
            .size(16.0)
            .strong(),
    );
    ui.add_space(12.0);

    // Mode selector
    egui::Frame::none()
        .fill(surface)
        .rounding(Rounding::same(6))
        .inner_margin(12.0)
        .show(ui, |ui| {
            ui.label(RichText::new("Mode").color(Color32::GRAY).size(12.0));
            ui.horizontal(|ui| {
                ui.selectable_value(&mut app.settings_mode, "server".to_string(), "Server");
                ui.selectable_value(&mut app.settings_mode, "receiver".to_string(), "Receiver");
            });
        });

    ui.add_space(8.0);

    if app.settings_mode == "server" {
        // Server settings
        egui::Frame::none()
            .fill(surface)
            .rounding(Rounding::same(6))
            .inner_margin(12.0)
            .show(ui, |ui| {
                ui.label(
                    RichText::new("Server Configuration")
                        .color(accent)
                        .size(13.0)
                        .strong(),
                );
                ui.add_space(8.0);

                egui::Grid::new("server_settings_grid")
                    .num_columns(2)
                    .spacing([12.0, 8.0])
                    .show(ui, |ui| {
                        ui.label(RichText::new("Name:").color(Color32::GRAY));
                        ui.add(
                            egui::TextEdit::singleline(&mut app.settings_name)
                                .desired_width(200.0),
                        );
                        ui.end_row();

                        ui.label(RichText::new("Port:").color(Color32::GRAY));
                        ui.add(
                            egui::TextEdit::singleline(&mut app.settings_port)
                                .desired_width(100.0),
                        );
                        ui.end_row();

                        ui.label(RichText::new("Codec:").color(Color32::GRAY));
                        egui::ComboBox::from_id_salt("codec_combo")
                            .selected_text(&app.settings_codec)
                            .show_ui(ui, |ui| {
                                ui.selectable_value(
                                    &mut app.settings_codec,
                                    "pcm".to_string(),
                                    "PCM",
                                );
                                ui.selectable_value(
                                    &mut app.settings_codec,
                                    "opus".to_string(),
                                    "Opus",
                                );
                                ui.selectable_value(
                                    &mut app.settings_codec,
                                    "flac".to_string(),
                                    "FLAC",
                                );
                            });
                        ui.end_row();

                        ui.label(RichText::new("Bitrate (kbps):").color(Color32::GRAY));
                        ui.add(egui::Slider::new(&mut app.settings_bitrate, 64..=512));
                        ui.end_row();

                        ui.label(RichText::new("PIN:").color(Color32::GRAY));
                        ui.add(
                            egui::TextEdit::singleline(&mut app.settings_pin)
                                .desired_width(150.0)
                                .password(true),
                        );
                        ui.end_row();
                    });
            });
    } else {
        // Receiver settings
        egui::Frame::none()
            .fill(surface)
            .rounding(Rounding::same(6))
            .inner_margin(12.0)
            .show(ui, |ui| {
                ui.label(
                    RichText::new("Receiver Configuration")
                        .color(accent)
                        .size(13.0)
                        .strong(),
                );
                ui.add_space(8.0);

                egui::Grid::new("receiver_settings_grid")
                    .num_columns(2)
                    .spacing([12.0, 8.0])
                    .show(ui, |ui| {
                        ui.label(RichText::new("Your Name:").color(Color32::GRAY));
                        ui.add(
                            egui::TextEdit::singleline(&mut app.settings_receiver_name)
                                .desired_width(200.0),
                        );
                        ui.end_row();

                        ui.label(RichText::new("Server Address:").color(Color32::GRAY));
                        ui.add(
                            egui::TextEdit::singleline(&mut app.settings_receiver_addr)
                                .desired_width(200.0)
                                .hint_text("e.g. 192.168.1.100:4953"),
                        );
                        ui.end_row();
                    });
            });
    }

    ui.add_space(16.0);

    // Start / Stop button
    ui.horizontal(|ui| {
        if app.server_running {
            if ui
                .add_sized(
                    Vec2::new(120.0, 32.0),
                    egui::Button::new(
                        RichText::new("Stop Server")
                            .color(Color32::WHITE)
                            .strong(),
                    )
                    .fill(Color32::from_rgb(200, 50, 50)),
                )
                .clicked()
            {
                let _ = app.cmd_tx.send(AppCommand::StopServer);
            }
        } else {
            if ui
                .add_sized(
                    Vec2::new(120.0, 32.0),
                    egui::Button::new(
                        RichText::new("Start Server")
                            .color(Color32::WHITE)
                            .strong(),
                    )
                    .fill(Color32::from_rgb(0, 160, 120)),
                )
                .clicked()
            {
                // Build config from settings
                let port: u16 = app.settings_port.parse().unwrap_or(4953);
                let config = Config {
                    name: app.settings_name.clone(),
                    port,
                    web_port: port + 1,
                    codec: app.settings_codec.clone(),
                    bitrate: app.settings_bitrate,
                    sample_rate: 48000,
                    channels: 2,
                    frame_ms: 20,
                    buffer_ms: 50,
                    multicast: false,
                    multicast_addr: "239.255.77.77".to_string(),
                    multicast_port: 4955,
                    log_level: "info".to_string(),
                    pin: app.settings_pin.clone(),
                    source_pid: 0,
                    no_tray: true,
                    mode: app.settings_mode.clone(),
                    connect: if app.settings_mode == "receiver" {
                        Some(app.settings_receiver_addr.clone())
                    } else {
                        None
                    },
                    client_name: app.settings_receiver_name.clone(),
                };
                let _ = app.cmd_tx.send(AppCommand::StartServer(config));
            }
        }

        ui.add_space(12.0);

        // Status message
        if !app.status_message.is_empty() {
            let color = if app.status_message.contains("Error") || app.status_message.contains("error") {
                Color32::from_rgb(255, 80, 80)
            } else {
                Color32::from_rgb(0, 255, 128)
            };
            ui.label(RichText::new(&app.status_message).color(color));
        }
    });
}
