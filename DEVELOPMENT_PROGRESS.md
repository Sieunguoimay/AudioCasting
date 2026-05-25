# DeviceLink — Development Progress

## Phase 1: PC Server (Rust) — COMPLETED

**Status:** Done
**Date:** 2026-03-21

### Files Created
- `server/Cargo.toml` — Dependencies: cpal, opus, tokio, mdns-sd, axum, hostname, etc.
- `server/src/main.rs` — Entry point, wires all components together
- `server/src/protocol.rs` — Wire protocol: AudioFrame, ControlMessage, ClockSyncMessage serialization
- `server/src/config.rs` — CLI config via clap (port, codec, bitrate, sample rate, etc.)
- `server/src/audio_capture.rs` — WASAPI loopback audio capture via cpal, outputs AudioChunk
- `server/src/encoder.rs` — Opus and PCM encoding of audio chunks
- `server/src/server.rs` — TCP streaming server, client session management, control messages
- `server/src/discovery.rs` — mDNS/DNS-SD service advertisement (`_devicelink._tcp.local.`)
- `server/src/clock_sync.rs` — NTP-like clock synchronization per client
- `server/src/multicast.rs` — UDP multicast audio forwarder (optional)
- `server/src/web_ui.rs` — Axum web UI with real-time status dashboard (HTML/JS embedded)

### What Works
- System audio capture via WASAPI loopback (auto-detects loopback/stereo mix devices)
- Opus encoding at configurable bitrate (64-512kbps), PCM fallback
- TCP streaming to multiple clients simultaneously
- Binary wire protocol with sequence numbers and presentation timestamps
- mDNS service discovery advertisement (`_devicelink._tcp.local.`)
- Client join/leave/volume control messages (JSON over TCP)
- NTP-like clock synchronization per-client (EMA smoothed offset/RTT)
- Optional UDP multicast streaming (--multicast flag)
- Web control panel with live client list, stats, and codec info

### Build Status: VERIFIED
- Build tested on 2026-03-21 with Rust 1.94.0 + MSVC Build Tools 2022
- Requires: Rust, MSVC C++ Build Tools, Windows SDK, CMake
- Set `CMAKE_POLICY_VERSION_MINIMUM=3.5` env var for CMake compatibility
- MSVC bin and CMake must be in PATH (or use VS Developer Command Prompt)

### Build Instructions
```bash
cd server
# Set MSVC environment (or use VS Developer Command Prompt)
cargo build --release
cargo run -- --name "My PC" --port 4953 --codec opus --bitrate 192
# With multicast:
cargo run -- --multicast --multicast-addr 239.255.77.77 --multicast-port 4955
# With PIN auth:
cargo run -- --pin 1234
# With per-app audio (specify process ID):
cargo run -- --source-pid 12345
# Web UI at http://localhost:4954
```

---

## Phase 2: Android Client — COMPLETED

**Status:** Done
**Date:** 2026-03-21

### Files Created
- `android/build.gradle.kts` — Root Gradle config
- `android/settings.gradle.kts` — Project settings
- `android/gradle.properties` — Gradle properties
- `android/gradle/wrapper/gradle-wrapper.properties` — Gradle wrapper config
- `android/app/build.gradle.kts` — App module with Compose, Concentus Opus, coroutines
- `android/app/src/main/AndroidManifest.xml` — Permissions (internet, wifi, foreground service)
- `android/app/src/main/java/com/devicelink/MainActivity.kt` — Entry activity with Compose
- `android/app/src/main/java/com/devicelink/network/Protocol.kt` — Wire protocol (mirrors server)
- `android/app/src/main/java/com/devicelink/network/Discovery.kt` — mDNS/NSD server discovery
- `android/app/src/main/java/com/devicelink/network/AudioClient.kt` — TCP client with handshake
- `android/app/src/main/java/com/devicelink/network/ClockSync.kt` — Client-side clock sync
- `android/app/src/main/java/com/devicelink/audio/OpusDecoder.kt` — Opus decoding via Android MediaCodec
- `android/app/src/main/java/com/devicelink/audio/JitterBuffer.kt` — Sequence-ordered jitter buffer
- `android/app/src/main/java/com/devicelink/audio/AudioPlayer.kt` — AudioTrack playback engine
- `android/app/src/main/java/com/devicelink/viewmodel/MainViewModel.kt` — MVVM state management
- `android/app/src/main/java/com/devicelink/ui/MainScreen.kt` — Full Compose UI
- `android/app/src/main/java/com/devicelink/ui/theme/Theme.kt` — Dark theme
- `android/app/src/main/res/values/strings.xml` — String resources
- `android/app/src/main/res/values/themes.xml` — Android theme
- `android/app/proguard-rules.pro` — ProGuard rules

### What Works
- Auto-discovery of DeviceLink servers via mDNS/NSD
- Manual connection via IP address and port
- TCP handshake (ClientJoin → ClientAccepted)
- Opus decoding via Android MediaCodec (built-in, no external libraries)
- PCM passthrough decoding
- Jitter buffer with sequence ordering and pre-buffering
- AudioTrack low-latency playback
- Volume control (local + sends to server)
- Buffer size adjustment (10-200ms)
- Live playback statistics (depth, received, played, lost, dropped)
- Dark themed Compose UI with server cards, connected view, stats

### Build Status: VERIFIED
- Build tested on 2026-03-21 with JDK 17.0.18 + Gradle 8.5 + Android SDK 34
- APK size: 15MB (debug), output at `app/build/outputs/apk/debug/app-debug.apk`
- Requires: JDK 17, Android SDK (platform 34, build-tools 34)

### Build Instructions
```bash
cd android
export JAVA_HOME="/path/to/jdk-17"
./gradlew assembleDebug
# Install: adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Phase 3: Multi-Device Synchronization — COMPLETED

**Status:** Done
**Date:** 2026-03-21

### Files Created
- `android/app/src/main/java/com/devicelink/network/MulticastReceiver.kt` — UDP multicast receiver
- `android/app/src/main/java/com/devicelink/audio/SyncPlayer.kt` — Timestamp-synchronized player

### What Works
- **Clock Synchronization**: NTP-like protocol between server and each client
  - Server sends T1 timestamp, client responds with T2/T3
  - EMA-smoothed offset calculation: `((T2-T1) + (T3-T4)) / 2`
  - Continuous sync every 5 seconds, target <2ms accuracy
- **Synchronized Playback** (SyncPlayer):
  - Converts server presentation timestamps to local clock domain
  - Schedules frame playback at `server_time + buffer + delay_offset`
  - Per-device delay offset for speaker distance compensation
  - Purges stale frames (>500ms behind)
- **UDP Multicast Transport**:
  - Server: `MulticastStreamer` sends to `239.255.77.77:4955` (configurable)
  - Client: `MulticastReceiver` joins multicast group with Wi-Fi multicast lock
  - Single stream serves all devices simultaneously
  - TCP control channel retained for session management and clock sync

### Integration Notes
- `AudioPlayer` (Phase 2) = basic jitter buffer playback, good for single-device
- `SyncPlayer` (Phase 3) = clock-synced playback, required for multi-device sync
- ViewModel can switch between them based on sync state

---

## Phase 4: Polish & Advanced Features — COMPLETED

**Status:** Done
**Date:** 2026-03-21

### What Was Built (integrated into earlier phases)
- **Web Control Panel** (`server/src/web_ui.rs`):
  - Real-time dashboard at `http://localhost:4954`
  - Server info: codec, sample rate, bitrate, buffer, multicast status
  - Connected clients list with names, addresses, volumes, clock sync stats
  - Auto-refreshes every 2 seconds via `/api/status` REST endpoint
  - Dark themed responsive design
- **Foreground Service** (`AudioService.kt`):
  - Background audio playback with persistent notification
  - Wi-Fi lock (WIFI_MODE_FULL_HIGH_PERF) prevents Wi-Fi sleep
  - Partial wake lock keeps CPU running for decoding
  - Notification shows server name, codec, and sample rate
- **Android UI Polish**:
  - Server auto-discovery with loading spinner
  - Manual IP/port connection
  - Device naming
  - Volume slider with real-time update
  - Buffer size slider (10-200ms)
  - Live stats dashboard
  - Error handling with dismissible banners
  - Connect/disconnect flow
- **Project Setup**:
  - `.gitignore` for Rust and Android artifacts

---

## Phase 5: Advanced Features — COMPLETED

**Status:** Done
**Date:** 2026-03-21

### Features Implemented

#### 1. Per-App Audio Source Selection (Windows 11)
**Server Files Modified:** `config.rs`, `server.rs`, `web_ui.rs`
- **Process listing** via `sysinfo` crate: server scans running processes and identifies audio-producing apps (browsers, media players, communication apps, games)
- **`--source-pid` CLI flag**: specify a process ID to capture audio from a specific app instead of system-wide loopback
- **`/api/sources` REST endpoint**: web UI can query available audio sources
- **`/api/sources/set` REST endpoint**: web UI can switch audio source at runtime
- **Web UI integration**: interactive audio source selector showing all running processes with PID, click to switch
- **`SetAudioSource` protocol message**: clients can request audio source changes

#### 2. FLAC Lossless Codec Support
**Server Files Modified:** `encoder.rs`, `Cargo.toml` (flac-bound already present)
**Android Files Created:** `FlacDecoder.kt`
**Android Files Modified:** `AudioPlayer.kt`, `SyncPlayer.kt`
- **Server encoding**: `flac-bound` crate encodes each audio chunk as a complete FLAC stream (header + data + footer) for independent decoding
- **Compression**: 16-bit PCM → FLAC with configurable compression level (default 5)
- **Android decoding**: `FlacDecoder` uses Android's built-in `MediaCodec` FLAC decoder
- **Usage**: `cargo run -- --codec flac` for lossless streaming
- **Both AudioPlayer and SyncPlayer** support FLAC decoding transparently

#### 3. PIN/Password Pairing for Security
**Server Files Modified:** `config.rs`, `protocol.rs`, `server.rs`, `web_ui.rs`
**Android Files Modified:** `Protocol.kt`, `AudioClient.kt`, `MainViewModel.kt`, `MainScreen.kt`
- **Server-side**: `--pin <value>` CLI flag sets a PIN; all clients must provide matching PIN in `ClientJoin`
- **`AuthRequired` protocol message**: server rejects clients with wrong/missing PIN
- **Android PIN dialog**: modal dialog with PIN input (password-masked) when server requires authentication
- **Saved PINs**: per-server PIN persistence via `ProfileManager` (SharedPreferences)
- **Web UI**: shows PIN auth status (enabled/disabled)

#### 4. Client Naming and Saved Profiles
**Android Files Created:** `data/ProfileManager.kt`
**Android Files Modified:** `MainViewModel.kt`, `MainScreen.kt`
- **`ProfileManager`**: SharedPreferences-backed storage for all user preferences
- **Persisted settings**: device name, default volume, default buffer, auto-reconnect, adaptive buffer, volume group
- **Saved servers**: automatically saves connection profiles (up to 20) with last-connected timestamps
- **"Saved Servers" UI section**: shows previously connected servers with one-tap reconnect and delete
- **Last connected server**: tracked for quick reconnection
- **Settings survive app restarts**: all preferences loaded on ViewModel init

#### 5. Volume Grouping (Link Multiple Devices)
**Server Files Modified:** `server.rs`, `protocol.rs`, `web_ui.rs`
**Android Files Modified:** `Protocol.kt`, `AudioClient.kt`, `MainViewModel.kt`, `MainScreen.kt`
- **Server-side volume groups**: `VolumeGroup` struct with name and shared volume level
- **`SetVolumeGroup` protocol message**: clients can join a named group
- **`set_group_volume()`**: setting a group's volume updates all member clients simultaneously
- **`/api/groups/volume` REST endpoint**: web UI can adjust group volumes
- **Web UI**: shows volume groups with slider controls and member count
- **Android UI**: volume group input field in Settings section; saved per-device
- **Group badges**: connected view shows current volume group

#### 6. Adaptive Jitter Buffer Based on Network Conditions
**Android Files Modified:** `JitterBuffer.kt`, `AudioPlayer.kt`, `MainViewModel.kt`, `MainScreen.kt`
- **Jitter tracking**: measures inter-arrival time variance using a sliding window (100 samples)
- **95th percentile jitter**: uses p95 for conservative buffer sizing
- **Loss rate tracking**: factors packet loss into buffer size decisions
- **Adaptive algorithm**: target = 2x p95_jitter + loss_penalty, smoothed at 25% per window
- **Buffer range**: auto-adjusts between 10ms (low latency) and 200ms (high jitter)
- **Window-based**: adapts every 50 frames for stability
- **Toggle**: can be enabled/disabled in Settings; preference saved
- **Stats display**: shows network jitter and adaptive target in connected view

#### 7. Auto-Reconnect on Network Change
**Android Files Created:** `network/NetworkMonitor.kt`
**Android Files Modified:** `MainViewModel.kt`, `MainScreen.kt`, `AndroidManifest.xml`
- **`NetworkMonitor`**: uses Android `ConnectivityManager.NetworkCallback` to detect Wi-Fi changes
- **Automatic reconnection**: when connection drops (ERROR state) and auto-reconnect is enabled
- **Wi-Fi restore detection**: immediately attempts reconnect when Wi-Fi becomes available again
- **Exponential backoff**: reconnect delay increases with each attempt (3s, 6s, 9s, 12s, 15s)
- **Max 5 attempts**: gives up after 5 failed reconnects with error message
- **Reconnecting UI**: shows progress indicator with attempt counter
- **Cancellable**: user can disconnect to cancel auto-reconnect
- **Toggle**: can be enabled/disabled in Settings; preference saved

#### 8. System Tray Integration (Windows)
**Server Files Created:** `system_tray.rs`
**Server Files Modified:** `main.rs`, `config.rs`, `Cargo.toml`
- **Windows system tray icon**: uses Win32 `Shell_NotifyIcon` API via the `windows` crate
- **Tooltip**: shows "DeviceLink Server"
- **Context menu** (right-click):
  - Status line showing connected client count and codec
  - "Open Web UI" — launches browser to `http://localhost:<web_port>`
  - "Exit" — graceful shutdown
- **Runs in background thread**: separate OS thread with Windows message loop, non-blocking to tokio runtime
- **`--tray` flag**: enabled by default, can be disabled with `--tray false`
- **Automatic cleanup**: tray icon removed on shutdown
- **Conditional compilation**: `#[cfg(windows)]` — no-op on non-Windows platforms

---

## Project Structure

```
DeviceLink/
├── DEVELOPMENT_PLAN.md
├── DEVELOPMENT_PROGRESS.md          ← you are here
├── .gitignore
├── server/                           # Rust PC Server
│   ├── Cargo.toml
│   └── src/
│       ├── main.rs                   # Entry point + system tray wiring
│       ├── protocol.rs               # Wire protocol + new messages
│       ├── config.rs                 # CLI configuration + PIN/source options
│       ├── audio_capture.rs          # WASAPI loopback capture
│       ├── encoder.rs                # Opus/FLAC/PCM encoder
│       ├── server.rs                 # TCP server + PIN auth + volume groups
│       ├── discovery.rs              # mDNS advertisement
│       ├── clock_sync.rs             # NTP-like sync
│       ├── multicast.rs              # UDP multicast
│       ├── system_tray.rs            # Windows system tray icon
│       └── web_ui.rs                 # Web control panel + source/group APIs
└── android/                          # Android Client
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/devicelink/
            │   ├── MainActivity.kt
            │   ├── audio/
            │   │   ├── AudioPlayer.kt      # Basic playback + FLAC
            │   │   ├── SyncPlayer.kt       # Synced playback + FLAC
            │   │   ├── OpusDecoder.kt      # Opus decoder
            │   │   ├── FlacDecoder.kt      # FLAC decoder (MediaCodec)
            │   │   └── JitterBuffer.kt     # Adaptive jitter buffer
            │   ├── data/
            │   │   └── ProfileManager.kt   # Saved profiles & preferences
            │   ├── network/
            │   │   ├── Protocol.kt         # Wire protocol + PIN/groups
            │   │   ├── AudioClient.kt      # TCP client + PIN auth
            │   │   ├── Discovery.kt        # mDNS discovery
            │   │   ├── ClockSync.kt        # Clock sync
            │   │   ├── MulticastReceiver.kt # UDP multicast
            │   │   └── NetworkMonitor.kt   # Wi-Fi change detection
            │   ├── service/
            │   │   └── AudioService.kt     # Foreground service
            │   ├── viewmodel/
            │   │   └── MainViewModel.kt    # State + auto-reconnect
            │   └── ui/
            │       ├── MainScreen.kt       # Full UI + PIN dialog + settings
            │       └── theme/Theme.kt      # Dark theme
            └── res/
                └── values/
                    ├── strings.xml
                    └── themes.xml
```

---

## Setup Prerequisites

### PC Server (Rust)
1. Install Rust: https://rustup.rs
2. On Windows, ensure MSVC build tools are installed
3. `cd server && cargo build --release`

### Android Client
1. Install Android Studio (or Android SDK + JDK 17)
2. `cd android && ./gradlew assembleDebug`
3. Install APK via `adb install`

---

## All Features Complete

All planned features have been implemented:

| Feature | Server | Android | Status |
|---------|--------|---------|--------|
| Per-app audio source selection | `server.rs`, `web_ui.rs` | — | Done |
| FLAC lossless codec | `encoder.rs` | `FlacDecoder.kt` | Done |
| PIN/password pairing | `server.rs`, `config.rs` | `AudioClient.kt`, PIN dialog | Done |
| Client naming & saved profiles | — | `ProfileManager.kt` | Done |
| Volume grouping | `server.rs`, `web_ui.rs` | `MainViewModel.kt` | Done |
| Adaptive jitter buffer | — | `JitterBuffer.kt` | Done |
| Auto-reconnect on network change | — | `NetworkMonitor.kt` | Done |
| System tray (Windows) | `system_tray.rs` | — | Done |
