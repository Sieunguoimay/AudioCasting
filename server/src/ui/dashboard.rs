use egui::{Color32, RichText, Rounding, Vec2};

use crate::ui::app::DeviceLinkApp;

pub fn show(app: &mut DeviceLinkApp, ui: &mut egui::Ui) {
    let accent = Color32::from_rgb(0, 212, 255);
    let surface = Color32::from_rgb(26, 26, 46);

    ui.add_space(8.0);

    // Server info header
    egui::Frame::none()
        .fill(surface)
        .rounding(Rounding::same(8))
        .inner_margin(12.0)
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                let status_color = if app.server_running {
                    Color32::from_rgb(0, 255, 128)
                } else {
                    Color32::from_rgb(255, 80, 80)
                };
                ui.label(
                    RichText::new(if app.server_running { "LIVE" } else { "OFFLINE" })
                        .color(status_color)
                        .strong()
                        .size(14.0),
                );
                ui.separator();
                ui.label(
                    RichText::new(format!("Port: {}", app.settings_port))
                        .color(Color32::GRAY),
                );
                ui.separator();
                ui.label(
                    RichText::new(format!("Codec: {}", app.settings_codec))
                        .color(Color32::GRAY),
                );
                ui.separator();
                ui.label(
                    RichText::new(format!("Clients: {}", app.clients.len()))
                        .color(accent),
                );
            });
        });

    ui.add_space(12.0);

    // VU Meter
    ui.label(RichText::new("Audio Level").color(Color32::GRAY).size(12.0));
    ui.add_space(4.0);

    let desired_size = Vec2::new(ui.available_width(), 24.0);
    let (rect, _response) = ui.allocate_exact_size(desired_size, egui::Sense::hover());

    let painter = ui.painter();

    // Background
    painter.rect_filled(rect, Rounding::same(4), Color32::from_rgb(20, 20, 40));

    // Level bar
    let level = app.smoothed_peak.clamp(0.0, 1.0);
    if level > 0.001 {
        let bar_width = rect.width() * level;
        let bar_rect = egui::Rect::from_min_size(rect.min, Vec2::new(bar_width, rect.height()));

        let bar_color = if level < 0.6 {
            Color32::from_rgb(0, 212, 255)
        } else if level < 0.85 {
            Color32::from_rgb(255, 200, 0)
        } else {
            Color32::from_rgb(255, 60, 60)
        };

        painter.rect_filled(bar_rect, Rounding::same(4), bar_color);
    }

    // Level text
    painter.text(
        rect.center(),
        egui::Align2::CENTER_CENTER,
        format!("{:.0}%", level * 100.0),
        egui::FontId::proportional(12.0),
        Color32::WHITE,
    );

    ui.add_space(16.0);

    // Connected clients
    ui.label(
        RichText::new(format!("Connected Clients ({})", app.clients.len()))
            .color(accent)
            .size(14.0)
            .strong(),
    );
    ui.add_space(6.0);

    if app.clients.is_empty() {
        ui.label(
            RichText::new("No clients connected")
                .color(Color32::from_rgb(100, 100, 120))
                .italics(),
        );
    } else {
        // We need to collect changes, then apply after iteration
        let mut volume_changes: Vec<(String, f32)> = Vec::new();

        for client in &app.clients {
            egui::Frame::none()
                .fill(surface)
                .rounding(Rounding::same(6))
                .inner_margin(10.0)
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(
                                RichText::new(&client.client_name)
                                    .color(Color32::WHITE)
                                    .strong(),
                            );
                            ui.label(
                                RichText::new(&client.addr)
                                    .color(Color32::from_rgb(120, 120, 140))
                                    .size(11.0),
                            );
                            if let Some(rtt) = client.rtt_us {
                                ui.label(
                                    RichText::new(format!("RTT: {}ms", rtt / 1000))
                                        .color(Color32::from_rgb(100, 100, 120))
                                        .size(10.0),
                                );
                            }
                            if let Some(ref group) = client.volume_group {
                                ui.label(
                                    RichText::new(format!("Group: {}", group))
                                        .color(accent)
                                        .size(10.0),
                                );
                            }
                        });

                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let mut vol = client.volume;
                            ui.label(
                                RichText::new(format!("{:.0}%", vol * 100.0))
                                    .color(Color32::GRAY)
                                    .size(11.0),
                            );
                            if ui
                                .add(egui::Slider::new(&mut vol, 0.0..=1.0).show_value(false))
                                .changed()
                            {
                                volume_changes.push((client.client_id.clone(), vol));
                            }
                        });
                    });
                });
            ui.add_space(4.0);
        }

        // Apply volume changes
        for (client_id, volume) in volume_changes {
            // Update cached state
            for c in &mut app.clients {
                if c.client_id == client_id {
                    c.volume = volume;
                }
            }
            // Send command
            let _ = app.cmd_tx.send(crate::ui::app::AppCommand::SetClientVolume {
                client_id,
                volume,
            });
        }
    }

    ui.add_space(16.0);

    // Audio sources
    ui.horizontal(|ui| {
        ui.label(
            RichText::new("Audio Sources")
                .color(accent)
                .size(14.0)
                .strong(),
        );
        if ui.small_button("Refresh").clicked() {
            let _ = app.cmd_tx.send(crate::ui::app::AppCommand::RefreshSources);
        }
    });
    ui.add_space(6.0);

    if app.audio_sources.is_empty() {
        ui.label(
            RichText::new("Start the server to see audio sources")
                .color(Color32::from_rgb(100, 100, 120))
                .italics(),
        );
    } else {
        for source in &app.audio_sources {
            let label = if source.pid == 0 {
                format!("System Audio (loopback)")
            } else {
                format!("{} (PID: {})", source.name, source.pid)
            };

            if ui
                .selectable_label(false, RichText::new(&label).color(Color32::LIGHT_GRAY))
                .clicked()
            {
                let _ = app.cmd_tx.send(crate::ui::app::AppCommand::SetAudioSource {
                    pid: source.pid,
                });
            }
        }
    }

    ui.add_space(16.0);

    // Volume groups
    if !app.volume_groups.is_empty() {
        ui.label(
            RichText::new("Volume Groups")
                .color(accent)
                .size(14.0)
                .strong(),
        );
        ui.add_space(6.0);

        let mut group_changes: Vec<(String, f32)> = Vec::new();

        for group in &app.volume_groups {
            egui::Frame::none()
                .fill(surface)
                .rounding(Rounding::same(6))
                .inner_margin(8.0)
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new(format!("{} ({} members)", group.name, group.member_count))
                                .color(Color32::WHITE),
                        );
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let mut vol = group.volume;
                            if ui
                                .add(egui::Slider::new(&mut vol, 0.0..=1.0).show_value(true))
                                .changed()
                            {
                                group_changes.push((group.name.clone(), vol));
                            }
                        });
                    });
                });
            ui.add_space(4.0);
        }

        for (name, volume) in group_changes {
            for g in &mut app.volume_groups {
                if g.name == name {
                    g.volume = volume;
                }
            }
            let _ = app.cmd_tx.send(crate::ui::app::AppCommand::SetGroupVolume {
                group_name: name,
                volume,
            });
        }
    }
}
