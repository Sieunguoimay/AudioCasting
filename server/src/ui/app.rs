use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use crossbeam_channel::{Receiver, Sender};
use egui::{Color32, RichText, Rounding, Vec2};
use log::{error, info};

use crate::audio::capture::AudioCapture;
use crate::config::Config;
use crate::protocol::AudioSourceInfo;
use crate::network::server::{ServerState, WebEvent};

// ─── Tabs ────────────────────────────────────────────────────────────────────

#[derive(Clone, PartialEq)]
pub enum Tab {
    Dashboard,
    Chat,
    Files,
    Settings,
}

// ─── Commands & Events ───────────────────────────────────────────────────────

pub enum AppCommand {
    StartServer(Config),
    StopServer,
    SetClientVolume { client_id: String, volume: f32 },
    SetGroupVolume { group_name: String, volume: f32 },
    SetAudioSource { pid: u32 },
    SendMessage { content: String },
    RefreshSources,
}

#[derive(Clone)]
pub enum AppEvent {
    ServerStarted(Arc<ServerState>),
    ServerStopped,
    Error(String),
    PeakLevel(f32),
    ClientsChanged,
    NewMessage {
        from_id: String,
        from_name: String,
        content: String,
        timestamp_us: u64,
    },
    TransferUpdate {
        transfer_id: String,
        status: String,
        chunks_received: u32,
        total_chunks: u32,
    },
    SourcesRefreshed(Vec<AudioSourceInfo>),
}

// ─── Cached UI data ──────────────────────────────────────────────────────────

pub struct CachedClient {
    pub client_id: String,
    pub client_name: String,
    pub addr: String,
    pub volume: f32,
    pub volume_group: Option<String>,
    pub rtt_us: Option<u64>,
}

pub struct CachedMessage {
    pub from_id: String,
    pub from_name: String,
    pub content: String,
    pub timestamp_us: u64,
}

pub struct CachedTransfer {
    pub transfer_id: String,
    pub from_name: String,
    pub file_name: String,
    pub file_size: u64,
    pub chunks_received: u32,
    pub total_chunks: u32,
    pub status: String,
}

pub struct CachedVolumeGroup {
    pub name: String,
    pub volume: f32,
    pub member_count: usize,
}

// ─── Main App ────────────────────────────────────────────────────────────────

pub struct DeviceLinkApp {
    // Runtime
    _runtime: tokio::runtime::Runtime,

    // Channels
    pub cmd_tx: Sender<AppCommand>,
    event_rx: Receiver<AppEvent>,

    // Server state (set when running)
    server_state: Option<Arc<ServerState>>,
    pub server_running: bool,

    // Cached UI state
    peak_level: f32,
    pub smoothed_peak: f32,
    pub clients: Vec<CachedClient>,
    pub messages: Vec<CachedMessage>,
    pub transfers: Vec<CachedTransfer>,
    pub audio_sources: Vec<AudioSourceInfo>,
    pub volume_groups: Vec<CachedVolumeGroup>,

    // UI state
    active_tab: Tab,
    pub chat_input: String,
    pub status_message: String,

    // Settings (editable)
    pub settings_name: String,
    pub settings_port: String,
    pub settings_codec: String,
    pub settings_bitrate: u32,
    pub settings_pin: String,
    pub settings_mode: String,
    pub settings_receiver_addr: String,
    pub settings_receiver_name: String,
}

impl DeviceLinkApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        // Dark visuals
        cc.egui_ctx.set_visuals(egui::Visuals::dark());

        // Create tokio runtime
        let runtime = tokio::runtime::Runtime::new().expect("Failed to create tokio runtime");

        // Create crossbeam channels
        let (cmd_tx, cmd_rx) = crossbeam_channel::unbounded::<AppCommand>();
        let (event_tx, event_rx) = crossbeam_channel::unbounded::<AppEvent>();

        // Spawn the command loop on the runtime
        runtime.spawn(command_loop(cmd_rx, event_tx));

        DeviceLinkApp {
            _runtime: runtime,
            cmd_tx,
            event_rx,
            server_state: None,
            server_running: false,
            peak_level: 0.0,
            smoothed_peak: 0.0,
            clients: Vec::new(),
            messages: Vec::new(),
            transfers: Vec::new(),
            audio_sources: Vec::new(),
            volume_groups: Vec::new(),
            active_tab: Tab::Dashboard,
            chat_input: String::new(),
            status_message: String::new(),
            settings_name: "DeviceLink Server".to_string(),
            settings_port: "4953".to_string(),
            settings_codec: "pcm".to_string(),
            settings_bitrate: 192,
            settings_pin: String::new(),
            settings_mode: "server".to_string(),
            settings_receiver_addr: String::new(),
            settings_receiver_name: "PC Receiver".to_string(),
        }
    }

    /// Refresh cached client list from server state
    fn refresh_clients(&mut self) {
        if let Some(ref state) = self.server_state {
            let clients_map = state.clients.read();
            let sync_states = state.clock_sync.get_all_states();

            self.clients = clients_map
                .values()
                .map(|c| {
                    let rtt_us = sync_states
                        .get(&c.client_id)
                        .and_then(|s| if s.rtt_us > 0 { Some(s.rtt_us) } else { None });

                    CachedClient {
                        client_id: c.client_id.clone(),
                        client_name: c.client_name.clone(),
                        addr: c.addr.to_string(),
                        volume: c.volume,
                        volume_group: c.volume_group.clone(),
                        rtt_us,
                    }
                })
                .collect();

            // Refresh volume groups
            let groups = state.get_volume_groups();
            self.volume_groups = groups
                .values()
                .map(|g| {
                    let member_count = clients_map
                        .values()
                        .filter(|c| c.volume_group.as_deref() == Some(&g.name))
                        .count();
                    CachedVolumeGroup {
                        name: g.name.clone(),
                        volume: g.volume,
                        member_count,
                    }
                })
                .collect();
        } else {
            self.clients.clear();
            self.volume_groups.clear();
        }
    }

    /// Refresh cached transfers from server state
    fn refresh_transfers(&mut self) {
        if let Some(ref state) = self.server_state {
            let transfers = state.active_transfers.read();
            self.transfers = transfers
                .values()
                .map(|t| CachedTransfer {
                    transfer_id: t.transfer_id.clone(),
                    from_name: t.from_name.clone(),
                    file_name: t.file_name.clone(),
                    file_size: t.file_size,
                    chunks_received: t.chunks_received,
                    total_chunks: t.total_chunks,
                    status: format!("{:?}", t.status),
                })
                .collect();
        } else {
            self.transfers.clear();
        }
    }
}

impl eframe::App for DeviceLinkApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Drain events (collect first to avoid borrow conflict)
        let events: Vec<AppEvent> = self.event_rx.try_iter().collect();
        for event in events {
            match event {
                AppEvent::ServerStarted(state) => {
                    self.server_state = Some(state);
                    self.server_running = true;
                    self.status_message = "Server started successfully".to_string();
                    self.refresh_clients();
                    info!("UI: Server started");
                }
                AppEvent::ServerStopped => {
                    self.server_state = None;
                    self.server_running = false;
                    self.peak_level = 0.0;
                    self.smoothed_peak = 0.0;
                    self.clients.clear();
                    self.volume_groups.clear();
                    self.status_message = "Server stopped".to_string();
                    info!("UI: Server stopped");
                }
                AppEvent::Error(msg) => {
                    self.status_message = format!("Error: {}", msg);
                    error!("UI: {}", msg);
                }
                AppEvent::PeakLevel(_level) => {
                    // We read peak directly from AtomicU32 below
                }
                AppEvent::ClientsChanged => {
                    self.refresh_clients();
                }
                AppEvent::NewMessage {
                    from_id,
                    from_name,
                    content,
                    timestamp_us,
                } => {
                    self.messages.push(CachedMessage {
                        from_id,
                        from_name,
                        content,
                        timestamp_us,
                    });
                    // Cap at 200
                    if self.messages.len() > 200 {
                        let excess = self.messages.len() - 200;
                        self.messages.drain(0..excess);
                    }
                }
                AppEvent::TransferUpdate {
                    transfer_id,
                    status,
                    chunks_received,
                    total_chunks,
                } => {
                    if let Some(t) = self
                        .transfers
                        .iter_mut()
                        .find(|t| t.transfer_id == transfer_id)
                    {
                        t.status = status;
                        t.chunks_received = chunks_received;
                        t.total_chunks = total_chunks;
                    } else {
                        self.refresh_transfers();
                    }
                }
                AppEvent::SourcesRefreshed(sources) => {
                    self.audio_sources = sources;
                }
            }
        }

        // Read peak level directly from atomic
        if let Some(ref state) = self.server_state {
            let raw = state.peak_level.load(Ordering::Relaxed);
            self.peak_level = raw as f32 / 10000.0;
        }

        // Smooth peak level (exponential decay)
        let target = self.peak_level;
        let alpha = if target > self.smoothed_peak {
            0.4 // Fast attack
        } else {
            0.08 // Slow decay
        };
        self.smoothed_peak += (target - self.smoothed_peak) * alpha;

        let accent = Color32::from_rgb(0, 212, 255);
        let bg_dark = Color32::from_rgb(15, 15, 35);

        // Top bar with tabs
        egui::TopBottomPanel::top("top_bar").show(ctx, |ui| {
            egui::Frame::none()
                .fill(Color32::from_rgb(20, 20, 45))
                .inner_margin(8.0)
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new("DeviceLink")
                                .color(accent)
                                .size(18.0)
                                .strong(),
                        );

                        ui.add_space(24.0);

                        let tab_btn = |ui: &mut egui::Ui,
                                       current: &mut Tab,
                                       target: Tab,
                                       label: &str| {
                            let is_active = *current == target;
                            let text_color = if is_active {
                                accent
                            } else {
                                Color32::from_rgb(160, 160, 180)
                            };
                            let btn = ui.add(
                                egui::Button::new(
                                    RichText::new(label).color(text_color).size(13.0),
                                )
                                .fill(if is_active {
                                    Color32::from_rgb(30, 30, 60)
                                } else {
                                    Color32::TRANSPARENT
                                })
                                .rounding(Rounding::same(4)),
                            );
                            if btn.clicked() {
                                *current = target;
                            }
                        };

                        tab_btn(ui, &mut self.active_tab, Tab::Dashboard, "Dashboard");
                        tab_btn(ui, &mut self.active_tab, Tab::Chat, "Chat");
                        tab_btn(ui, &mut self.active_tab, Tab::Files, "Files");
                        tab_btn(ui, &mut self.active_tab, Tab::Settings, "Settings");
                    });
                });
        });

        // Central panel
        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(bg_dark).inner_margin(16.0))
            .show(ctx, |ui| {
                egui::ScrollArea::vertical().show(ui, |ui| {
                    match self.active_tab {
                        Tab::Dashboard => crate::ui::dashboard::show(self, ui),
                        Tab::Chat => crate::ui::chat::show(self, ui),
                        Tab::Files => crate::ui::files::show(self, ui),
                        Tab::Settings => crate::ui::settings::show(self, ui),
                    }
                });
            });

        // Request repaint for animations on Dashboard
        if self.active_tab == Tab::Dashboard && self.server_running {
            ctx.request_repaint_after(Duration::from_millis(50));
        }
    }
}

// ─── Command Loop (async, runs on tokio) ─────────────────────────────────────

async fn command_loop(cmd_rx: Receiver<AppCommand>, event_tx: Sender<AppEvent>) {
    let mut server_state: Option<Arc<ServerState>> = None;
    let mut _capture: Option<AudioCapture> = None;
    let mut _discovery: Option<crate::network::discovery::Discovery> = None;
    let mut _web_event_task: Option<tokio::task::JoinHandle<()>> = None;

    loop {
        match cmd_rx.try_recv() {
            Ok(AppCommand::StartServer(config)) => {
                info!("Command loop: starting server...");

                // Step 1: Create audio capture
                let frame_samples = config.frame_size_samples();
                let (mut capture, audio_rx) = AudioCapture::new(
                    config.sample_rate,
                    config.channels as u16,
                    frame_samples,
                );

                // Step 2: Start capture
                match capture.start() {
                    Ok(()) => {
                        info!(
                            "Audio capture started on: {}",
                            capture.device_name()
                        );
                    }
                    Err(e) => {
                        let msg = format!("Failed to start audio capture: {}", e);
                        error!("{}", msg);
                        event_tx.send(AppEvent::Error(msg)).ok();
                        continue;
                    }
                }

                // Step 3: Start server (TCP listener, encoder, clock sync)
                match crate::network::server::start_server(config.clone(), audio_rx).await {
                    Ok(state) => {
                        // Step 4: Start mDNS discovery
                        match crate::network::discovery::Discovery::new() {
                            Ok(mut discovery) => {
                                if let Err(e) = discovery.register(
                                    &config.name,
                                    config.port,
                                    &config.codec,
                                    config.sample_rate,
                                    config.channels,
                                ) {
                                    error!("mDNS registration failed: {}", e);
                                }
                                _discovery = Some(discovery);
                            }
                            Err(e) => {
                                error!("mDNS init failed: {}", e);
                            }
                        }

                        // Subscribe to web_events and forward them as AppEvents
                        let web_event_tx = event_tx.clone();
                        let mut web_rx = state.web_events.subscribe();
                        let task = tokio::spawn(async move {
                            loop {
                                match web_rx.recv().await {
                                    Ok(web_event) => {
                                        let app_event = match web_event {
                                            WebEvent::PeakLevel { level } => {
                                                AppEvent::PeakLevel(level)
                                            }
                                            WebEvent::ClientChanged => AppEvent::ClientsChanged,
                                            WebEvent::NewMessage {
                                                from_id,
                                                from_name,
                                                content,
                                                timestamp_us,
                                                ..
                                            } => AppEvent::NewMessage {
                                                from_id,
                                                from_name,
                                                content,
                                                timestamp_us,
                                            },
                                            WebEvent::TransferUpdate {
                                                transfer_id,
                                                status,
                                                chunks_received,
                                                total_chunks,
                                            } => AppEvent::TransferUpdate {
                                                transfer_id,
                                                status,
                                                chunks_received,
                                                total_chunks,
                                            },
                                        };
                                        if web_event_tx.send(app_event).is_err() {
                                            break;
                                        }
                                    }
                                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                                        log::warn!("Web event forwarder lagged by {} events", n);
                                    }
                                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                                        break;
                                    }
                                }
                            }
                        });
                        _web_event_task = Some(task);

                        event_tx.send(AppEvent::ServerStarted(state.clone())).ok();
                        server_state = Some(state);
                        _capture = Some(capture);
                    }
                    Err(e) => {
                        let msg = format!("Failed to start server: {}", e);
                        error!("{}", msg);
                        capture.stop();
                        event_tx.send(AppEvent::Error(msg)).ok();
                    }
                }
            }
            Ok(AppCommand::StopServer) => {
                info!("Command loop: stopping server...");
                // Stop web event forwarder
                if let Some(task) = _web_event_task.take() {
                    task.abort();
                }
                // Stop capture
                if let Some(ref cap) = _capture {
                    cap.stop();
                }
                _capture = None;
                // Unregister mDNS
                if let Some(ref mut disc) = _discovery {
                    disc.unregister();
                }
                _discovery = None;
                // Drop server state
                server_state = None;
                event_tx.send(AppEvent::ServerStopped).ok();
            }
            Ok(AppCommand::SetClientVolume { client_id, volume }) => {
                if let Some(ref state) = server_state {
                    let mut clients = state.clients.write();
                    if let Some(client) = clients.get_mut(&client_id) {
                        client.volume = volume.clamp(0.0, 1.0);
                        let msg = crate::protocol::ControlMessage::SetVolume { volume };
                        let _ = client.tx.send(msg.serialize());
                    }
                }
            }
            Ok(AppCommand::SetGroupVolume { group_name, volume }) => {
                if let Some(ref state) = server_state {
                    let affected = state.set_group_volume(&group_name, volume);
                    // Send volume update to each affected client
                    let clients = state.clients.read();
                    for id in affected {
                        if let Some(client) = clients.get(&id) {
                            let msg = crate::protocol::ControlMessage::SetVolume { volume };
                            let _ = client.tx.send(msg.serialize());
                        }
                    }
                    event_tx.send(AppEvent::ClientsChanged).ok();
                }
            }
            Ok(AppCommand::SetAudioSource { pid }) => {
                if let Some(ref state) = server_state {
                    *state.source_pid.write() = pid;
                    info!("Audio source changed to PID {}", pid);
                }
            }
            Ok(AppCommand::SendMessage { content }) => {
                if let Some(ref state) = server_state {
                    let stored = state.send_server_message(content);
                    // The message will come back via web_events, but also add directly
                    // for immediate display (the web_event may arrive after a small delay)
                    event_tx
                        .send(AppEvent::NewMessage {
                            from_id: stored.from_id,
                            from_name: stored.from_name,
                            content: stored.content,
                            timestamp_us: stored.timestamp_us,
                        })
                        .ok();
                }
            }
            Ok(AppCommand::RefreshSources) => {
                if let Some(ref state) = server_state {
                    let sources = state.list_audio_sources();
                    event_tx.send(AppEvent::SourcesRefreshed(sources)).ok();
                }
            }
            Err(_) => {}
        }

        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
    }
}
