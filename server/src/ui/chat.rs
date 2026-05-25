use egui::{Color32, RichText, Rounding, Vec2};

use crate::ui::app::DeviceLinkApp;

pub fn show(app: &mut DeviceLinkApp, ui: &mut egui::Ui) {
    let accent = Color32::from_rgb(0, 212, 255);
    let surface = Color32::from_rgb(26, 26, 46);

    ui.add_space(8.0);
    ui.label(
        RichText::new("Messages")
            .color(accent)
            .size(16.0)
            .strong(),
    );
    ui.add_space(8.0);

    // Message list
    let available_height = ui.available_height() - 50.0; // Reserve space for input bar
    egui::Frame::none()
        .fill(surface)
        .rounding(Rounding::same(6))
        .inner_margin(8.0)
        .show(ui, |ui| {
            egui::ScrollArea::vertical()
                .max_height(available_height)
                .stick_to_bottom(true)
                .show(ui, |ui| {
                    if app.messages.is_empty() {
                        ui.label(
                            RichText::new("No messages yet. Send one below!")
                                .color(Color32::from_rgb(100, 100, 120))
                                .italics(),
                        );
                    } else {
                        for msg in &app.messages {
                            ui.horizontal_wrapped(|ui| {
                                // Timestamp
                                let secs = (msg.timestamp_us / 1_000_000) as i64;
                                let dt = chrono::DateTime::from_timestamp(secs, 0)
                                    .unwrap_or_default();
                                let time_str = dt.format("%H:%M").to_string();

                                ui.label(
                                    RichText::new(format!("[{}]", time_str))
                                        .color(Color32::from_rgb(80, 80, 100))
                                        .size(11.0),
                                );

                                // Sender
                                let sender_color = if msg.from_id == "server" {
                                    Color32::from_rgb(0, 255, 128)
                                } else {
                                    accent
                                };
                                ui.label(
                                    RichText::new(&msg.from_name)
                                        .color(sender_color)
                                        .strong()
                                        .size(12.0),
                                );

                                // Content
                                ui.label(
                                    RichText::new(&msg.content)
                                        .color(Color32::LIGHT_GRAY)
                                        .size(12.0),
                                );
                            });
                            ui.add_space(2.0);
                        }
                    }
                });
        });

    ui.add_space(8.0);

    // Input bar
    ui.horizontal(|ui| {
        let response = ui.add(
            egui::TextEdit::singleline(&mut app.chat_input)
                .desired_width(ui.available_width() - 70.0)
                .hint_text("Type a message..."),
        );

        let send_clicked = ui
            .add_sized(
                Vec2::new(60.0, 24.0),
                egui::Button::new(RichText::new("Send").color(Color32::WHITE))
                    .fill(accent),
            )
            .clicked();

        let enter_pressed = response.lost_focus()
            && ui.input(|i| i.key_pressed(egui::Key::Enter));

        if (send_clicked || enter_pressed) && !app.chat_input.trim().is_empty() {
            let content = app.chat_input.trim().to_string();
            app.chat_input.clear();
            let _ = app.cmd_tx.send(crate::ui::app::AppCommand::SendMessage { content });
            // Re-focus the text input
            response.request_focus();
        }
    });
}
