use axum::{
    extract::State,
    response::{Html, Json},
    routing::{get, post},
    Router,
};
use log::info;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::server::ServerState;

#[derive(Serialize)]
struct ServerStatus {
    name: String,
    codec: String,
    sample_rate: u32,
    channels: u8,
    bitrate: u32,
    buffer_ms: u32,
    multicast_enabled: bool,
    requires_pin: bool,
    client_count: usize,
    source_pid: u32,
    clients: Vec<ClientStatus>,
    volume_groups: Vec<VolumeGroupStatus>,
}

#[derive(Serialize)]
struct ClientStatus {
    client_id: String,
    client_name: String,
    addr: String,
    connected_since_us: u64,
    volume: f32,
    volume_group: Option<String>,
    clock_offset_us: Option<i64>,
    clock_rtt_us: Option<u64>,
}

#[derive(Serialize)]
struct VolumeGroupStatus {
    name: String,
    volume: f32,
    member_count: usize,
}

#[derive(Serialize)]
struct AudioSourceStatus {
    pid: u32,
    name: String,
    window_title: String,
}

#[derive(Deserialize)]
struct SetGroupVolumeRequest {
    group_name: String,
    volume: f32,
}

#[derive(Deserialize)]
struct SetAudioSourceRequest {
    pid: u32,
}

/// Start the web UI server
pub async fn start_web_ui(
    state: Arc<ServerState>,
    port: u16,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let app = Router::new()
        .route("/", get(index_page))
        .route("/api/status", get(get_status))
        .route("/api/clients", get(get_clients))
        .route("/api/sources", get(get_audio_sources))
        .route("/api/sources/set", post(set_audio_source))
        .route("/api/groups/volume", post(set_group_volume))
        .with_state(state);

    let addr = format!("0.0.0.0:{}", port);
    info!("Web UI available at http://localhost:{}", port);

    let listener = tokio::net::TcpListener::bind(&addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

async fn get_status(State(state): State<Arc<ServerState>>) -> Json<ServerStatus> {
    let clients_list = state.get_clients();
    let clients: Vec<ClientStatus> = clients_list
        .iter()
        .map(|c| {
            let clock = state.clock_sync.get_client_state(&c.client_id);
            ClientStatus {
                client_id: c.client_id.clone(),
                client_name: c.client_name.clone(),
                addr: c.addr.to_string(),
                connected_since_us: c.connected_at,
                volume: c.volume,
                volume_group: c.volume_group.clone(),
                clock_offset_us: clock.as_ref().map(|cs| cs.offset_us),
                clock_rtt_us: clock.as_ref().map(|cs| cs.rtt_us),
            }
        })
        .collect();

    // Build volume group status
    let groups = state.get_volume_groups();
    let volume_groups: Vec<VolumeGroupStatus> = groups.iter().map(|(name, group)| {
        let member_count = clients_list.iter()
            .filter(|c| c.volume_group.as_deref() == Some(name.as_str()))
            .count();
        VolumeGroupStatus {
            name: group.name.clone(),
            volume: group.volume,
            member_count,
        }
    }).collect();

    Json(ServerStatus {
        name: state.config.name.clone(),
        codec: state.config.codec.clone(),
        sample_rate: state.config.sample_rate,
        channels: state.config.channels,
        bitrate: state.config.bitrate,
        buffer_ms: state.config.buffer_ms,
        multicast_enabled: state.config.multicast,
        requires_pin: state.config.requires_auth(),
        client_count: clients.len(),
        source_pid: *state.source_pid.read(),
        clients,
        volume_groups,
    })
}

async fn get_clients(State(state): State<Arc<ServerState>>) -> Json<Vec<ClientStatus>> {
    let clients: Vec<ClientStatus> = state
        .get_clients()
        .into_iter()
        .map(|c| {
            let clock = state.clock_sync.get_client_state(&c.client_id);
            ClientStatus {
                client_id: c.client_id,
                client_name: c.client_name,
                addr: c.addr.to_string(),
                connected_since_us: c.connected_at,
                volume: c.volume,
                volume_group: c.volume_group,
                clock_offset_us: clock.as_ref().map(|cs| cs.offset_us),
                clock_rtt_us: clock.as_ref().map(|cs| cs.rtt_us),
            }
        })
        .collect();

    Json(clients)
}

async fn get_audio_sources(State(state): State<Arc<ServerState>>) -> Json<Vec<AudioSourceStatus>> {
    let sources = state.list_audio_sources();
    Json(sources.into_iter().map(|s| AudioSourceStatus {
        pid: s.pid,
        name: s.name,
        window_title: s.window_title,
    }).collect())
}

async fn set_audio_source(
    State(state): State<Arc<ServerState>>,
    Json(req): Json<SetAudioSourceRequest>,
) -> Json<serde_json::Value> {
    *state.source_pid.write() = req.pid;
    info!("Audio source changed to PID {}", req.pid);
    Json(serde_json::json!({ "ok": true, "pid": req.pid }))
}

async fn set_group_volume(
    State(state): State<Arc<ServerState>>,
    Json(req): Json<SetGroupVolumeRequest>,
) -> Json<serde_json::Value> {
    let affected = state.set_group_volume(&req.group_name, req.volume);
    info!("Group '{}' volume set to {:.2}, {} clients affected", req.group_name, req.volume, affected.len());
    Json(serde_json::json!({ "ok": true, "affected": affected.len() }))
}

async fn index_page() -> Html<String> {
    Html(WEB_UI_HTML.to_string())
}

const WEB_UI_HTML: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AudioCast Server</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #0f0f23;
            color: #e0e0e0;
            min-height: 100vh;
            padding: 2rem;
        }
        h1 { color: #00d4ff; margin-bottom: 0.5rem; font-size: 1.8rem; }
        h2 { color: #888; margin-bottom: 1.5rem; font-size: 0.9rem; font-weight: normal; }
        h3 { color: #00d4ff; margin-bottom: 1rem; font-size: 1rem; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.5rem; margin-bottom: 2rem; }
        .card {
            background: #1a1a2e;
            border-radius: 12px;
            padding: 1.5rem;
            border: 1px solid #2a2a4a;
        }
        .card h3 { margin-bottom: 1rem; }
        .stat { display: flex; justify-content: space-between; padding: 0.5rem 0; border-bottom: 1px solid #2a2a4a; }
        .stat:last-child { border-bottom: none; }
        .stat-label { color: #888; }
        .stat-value { color: #fff; font-weight: 500; }
        .client-card {
            background: #1a1a2e;
            border-radius: 12px;
            padding: 1.5rem;
            border: 1px solid #2a2a4a;
            margin-bottom: 1rem;
        }
        .client-name { color: #00d4ff; font-weight: 600; margin-bottom: 0.5rem; }
        .badge {
            display: inline-block;
            padding: 0.2rem 0.6rem;
            border-radius: 4px;
            font-size: 0.75rem;
            font-weight: 600;
        }
        .badge-green { background: #0a3d0a; color: #4caf50; }
        .badge-blue { background: #0a2d4a; color: #4a9eff; }
        .no-clients { color: #666; text-align: center; padding: 2rem; }
        #error { color: #ff4444; margin: 1rem 0; display: none; }
        .section { margin-bottom: 2rem; }
        select, input[type=range] { background: #2a2a4a; color: #e0e0e0; border: 1px solid #3a3a5a; border-radius: 6px; padding: 0.5rem; width: 100%; }
        select:focus { outline: 1px solid #00d4ff; }
        .source-item { display: flex; justify-content: space-between; align-items: center; padding: 0.5rem; border-bottom: 1px solid #2a2a4a; cursor: pointer; }
        .source-item:hover { background: #2a2a4a; }
        .source-item.active { background: #0a2d4a; border-left: 3px solid #00d4ff; }
        .group-card { background: #22223a; border-radius: 8px; padding: 1rem; margin-bottom: 0.5rem; }
        .group-name { color: #89cff0; font-weight: 600; }
        .volume-slider { width: 100%; margin-top: 0.5rem; }
        button { background: #00d4ff; color: #000; border: none; padding: 0.5rem 1rem; border-radius: 6px; cursor: pointer; font-weight: 600; }
        button:hover { background: #33ddff; }
    </style>
</head>
<body>
    <h1>AudioCast Server</h1>
    <h2 id="subtitle">Loading...</h2>
    <div id="error"></div>

    <div class="grid">
        <div class="card">
            <h3>Server Info</h3>
            <div class="stat"><span class="stat-label">Codec</span><span class="stat-value" id="codec">-</span></div>
            <div class="stat"><span class="stat-label">Sample Rate</span><span class="stat-value" id="sampleRate">-</span></div>
            <div class="stat"><span class="stat-label">Channels</span><span class="stat-value" id="channels">-</span></div>
            <div class="stat"><span class="stat-label">Bitrate</span><span class="stat-value" id="bitrate">-</span></div>
            <div class="stat"><span class="stat-label">Buffer</span><span class="stat-value" id="buffer">-</span></div>
            <div class="stat"><span class="stat-label">Multicast</span><span class="stat-value" id="multicast">-</span></div>
            <div class="stat"><span class="stat-label">PIN Auth</span><span class="stat-value" id="pinAuth">-</span></div>
        </div>
        <div class="card">
            <h3>Connections</h3>
            <div style="text-align:center; padding:1rem;">
                <div style="font-size:3rem; color:#00d4ff; font-weight:700;" id="clientCount">0</div>
                <div style="color:#888;">Connected Devices</div>
            </div>
        </div>
    </div>

    <!-- Audio Source Selection -->
    <div class="section">
        <h3>Audio Source</h3>
        <div class="card">
            <div id="sources"><div class="no-clients">Loading audio sources...</div></div>
        </div>
    </div>

    <!-- Volume Groups -->
    <div class="section">
        <h3>Volume Groups</h3>
        <div id="volumeGroups"><div class="no-clients">No volume groups</div></div>
    </div>

    <!-- Connected Clients -->
    <div class="section">
        <h3>Connected Clients</h3>
        <div id="clients"><div class="no-clients">No clients connected</div></div>
    </div>

    <script>
        let currentSourcePid = 0;

        async function refresh() {
            try {
                const res = await fetch('/api/status');
                const data = await res.json();

                document.getElementById('subtitle').textContent = data.name;
                document.getElementById('codec').textContent = data.codec.toUpperCase();
                document.getElementById('sampleRate').textContent = data.sample_rate + ' Hz';
                document.getElementById('channels').textContent = data.channels === 2 ? 'Stereo' : 'Mono';
                document.getElementById('bitrate').textContent = data.bitrate + ' kbps';
                document.getElementById('buffer').textContent = data.buffer_ms + ' ms';
                document.getElementById('multicast').textContent = data.multicast_enabled ? 'Enabled' : 'Disabled';
                document.getElementById('pinAuth').textContent = data.requires_pin ? 'Enabled' : 'Disabled';
                document.getElementById('clientCount').textContent = data.client_count;
                currentSourcePid = data.source_pid;

                // Clients
                const container = document.getElementById('clients');
                if (data.clients.length === 0) {
                    container.innerHTML = '<div class="no-clients">No clients connected</div>';
                } else {
                    container.innerHTML = data.clients.map(c => `
                        <div class="client-card">
                            <div class="client-name">${c.client_name} <span class="badge badge-green">Connected</span>
                                ${c.volume_group ? `<span class="badge badge-blue">${c.volume_group}</span>` : ''}
                            </div>
                            <div class="stat"><span class="stat-label">Address</span><span class="stat-value">${c.addr}</span></div>
                            <div class="stat"><span class="stat-label">Volume</span><span class="stat-value">${Math.round(c.volume * 100)}%</span></div>
                            <div class="stat"><span class="stat-label">Clock Offset</span><span class="stat-value">${c.clock_offset_us !== null ? c.clock_offset_us + ' us' : 'Syncing...'}</span></div>
                            <div class="stat"><span class="stat-label">RTT</span><span class="stat-value">${c.clock_rtt_us !== null ? c.clock_rtt_us + ' us' : 'Syncing...'}</span></div>
                        </div>
                    `).join('');
                }

                // Volume Groups
                const groupsContainer = document.getElementById('volumeGroups');
                if (data.volume_groups.length === 0) {
                    groupsContainer.innerHTML = '<div class="no-clients">No volume groups configured</div>';
                } else {
                    groupsContainer.innerHTML = data.volume_groups.map(g => `
                        <div class="group-card">
                            <div class="group-name">${g.name} <span style="color:#888; font-weight:normal;">(${g.member_count} devices)</span></div>
                            <input type="range" class="volume-slider" min="0" max="100" value="${Math.round(g.volume * 100)}"
                                onchange="setGroupVolume('${g.name}', this.value / 100)">
                            <div style="text-align:right; color:#888; font-size:0.8rem;">${Math.round(g.volume * 100)}%</div>
                        </div>
                    `).join('');
                }

                document.getElementById('error').style.display = 'none';
            } catch (e) {
                document.getElementById('error').textContent = 'Connection error: ' + e.message;
                document.getElementById('error').style.display = 'block';
            }
        }

        async function refreshSources() {
            try {
                const res = await fetch('/api/sources');
                const sources = await res.json();
                const container = document.getElementById('sources');
                container.innerHTML = sources.map(s => `
                    <div class="source-item ${s.pid === currentSourcePid ? 'active' : ''}"
                         onclick="setSource(${s.pid})">
                        <span>${s.name}${s.window_title ? ' - ' + s.window_title : ''}</span>
                        <span style="color:#888;">PID: ${s.pid}</span>
                    </div>
                `).join('');
            } catch (e) {
                console.error('Failed to load sources:', e);
            }
        }

        async function setSource(pid) {
            try {
                await fetch('/api/sources/set', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ pid: pid })
                });
                currentSourcePid = pid;
                refreshSources();
            } catch (e) {
                console.error('Failed to set source:', e);
            }
        }

        async function setGroupVolume(groupName, volume) {
            try {
                await fetch('/api/groups/volume', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ group_name: groupName, volume: volume })
                });
            } catch (e) {
                console.error('Failed to set group volume:', e);
            }
        }

        refresh();
        refreshSources();
        setInterval(refresh, 2000);
        setInterval(refreshSources, 10000);
    </script>
</body>
</html>
"#;
