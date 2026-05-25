use egui::{Color32, RichText, Rounding, Vec2};

use crate::ui::app::DeviceLinkApp;

pub fn show(app: &mut DeviceLinkApp, ui: &mut egui::Ui) {
    let accent = Color32::from_rgb(0, 212, 255);
    let surface = Color32::from_rgb(26, 26, 46);

    ui.add_space(8.0);
    ui.horizontal(|ui| {
        ui.label(
            RichText::new("File Transfers")
                .color(accent)
                .size(16.0)
                .strong(),
        );

        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui
                .add(
                    egui::Button::new(RichText::new("Upload File").color(Color32::WHITE))
                        .fill(accent),
                )
                .clicked()
            {
                // Open file dialog (non-blocking)
                if let Some(path) = rfd::FileDialog::new().pick_file() {
                    log::info!("Selected file for upload: {:?}", path);
                    // TODO: implement file sending from server to clients
                }
            }
        });
    });
    ui.add_space(12.0);

    if app.transfers.is_empty() {
        ui.label(
            RichText::new("No file transfers yet.")
                .color(Color32::from_rgb(100, 100, 120))
                .italics(),
        );
    } else {
        for transfer in &app.transfers {
            egui::Frame::none()
                .fill(surface)
                .rounding(Rounding::same(6))
                .inner_margin(10.0)
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(
                                RichText::new(&transfer.file_name)
                                    .color(Color32::WHITE)
                                    .strong(),
                            );
                            ui.label(
                                RichText::new(format!(
                                    "From: {} | Size: {}",
                                    transfer.from_name,
                                    format_size(transfer.file_size)
                                ))
                                .color(Color32::GRAY)
                                .size(11.0),
                            );
                        });

                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            // Status badge
                            let (status_text, status_color) = match transfer.status.as_str() {
                                "Completed" => ("Done", Color32::from_rgb(0, 255, 128)),
                                "InProgress" => ("Transferring", accent),
                                "Error" => ("Error", Color32::from_rgb(255, 60, 60)),
                                "Pending" => ("Pending", Color32::from_rgb(255, 200, 0)),
                                "Accepted" => ("Accepted", accent),
                                "Rejected" => ("Rejected", Color32::from_rgb(255, 80, 80)),
                                other => (other, Color32::GRAY),
                            };
                            ui.label(RichText::new(status_text).color(status_color).strong());
                        });
                    });

                    // Progress bar
                    if transfer.total_chunks > 0 {
                        let progress =
                            transfer.chunks_received as f32 / transfer.total_chunks as f32;
                        ui.add_space(4.0);
                        let bar_size = Vec2::new(ui.available_width(), 8.0);
                        let (rect, _) = ui.allocate_exact_size(bar_size, egui::Sense::hover());
                        let painter = ui.painter();
                        painter.rect_filled(
                            rect,
                            Rounding::same(4),
                            Color32::from_rgb(40, 40, 60),
                        );
                        let filled_rect = egui::Rect::from_min_size(
                            rect.min,
                            Vec2::new(rect.width() * progress, rect.height()),
                        );
                        painter.rect_filled(filled_rect, Rounding::same(4), accent);
                        ui.add_space(2.0);
                        ui.label(
                            RichText::new(format!(
                                "{}/{}  ({:.0}%)",
                                transfer.chunks_received,
                                transfer.total_chunks,
                                progress * 100.0
                            ))
                            .color(Color32::GRAY)
                            .size(10.0),
                        );
                    }
                });
            ui.add_space(4.0);
        }
    }
}

fn format_size(bytes: u64) -> String {
    if bytes < 1024 {
        format!("{} B", bytes)
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else if bytes < 1024 * 1024 * 1024 {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    } else {
        format!("{:.2} GB", bytes as f64 / (1024.0 * 1024.0 * 1024.0))
    }
}
