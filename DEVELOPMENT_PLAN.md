# DeviceLink — Development Plan

## Overview

A bidirectional audio streaming system over LAN:
1. **PC Server (Windows)** — Captures system audio and streams it over LAN
2. **Android Client** — Discovers the server, receives and plays the audio stream
3. **Android Sender** — Captures mic/system audio and streams to other devices
4. **PC Receiver** — Connects to any sender (PC or Android) and plays the audio

Key goals: **low latency**, **high quality (up to lossless)**, **multi-device sync**, **simple UX**, **any-to-any streaming**.

---

## Architecture

```
┌─────────────────────┐         LAN (Wi-Fi)         ┌──────────────────┐
│    Windows PC       │  ◄──── mDNS discovery ────►  │  Android Device  │
│                     │                              │                  │
│  WASAPI Loopback    │                              │                  │
│       ▼             │   UDP multicast / TCP        │                  │
│  Opus/FLAC Encoder  │ ─────── audio stream ──────► │  Decoder ► Play  │
│       ▼             │                              │                  │
│  Network Streamer   │ ◄─────── control chan ──────► │  Control Client  │
│                     │          (TCP/WebSocket)      │                  │
│  Web Control Panel  │                              │                  │
└─────────────────────┘                              └──────────────────┘
                                                      (× multiple devices)

Bidirectional (Phase 6):

┌──────────────────┐                    ┌──────────────────┐
│  Android Device  │                    │  Android Device  │
│  (Sender Mode)   │ ── TCP stream ──►  │  (Receiver Mode) │
│  Mic / System    │                    │  (existing)      │
│  Audio Capture   │                    │                  │
└──────────────────┘                    └──────────────────┘
        │
        └──── TCP stream ────►  ┌──────────────────┐
                                │  Windows PC      │
                                │  (Receiver Mode) │
                                │  cpal output     │
                                └──────────────────┘
```

---

## Phase 1 — Core Audio Capture & Streaming (PC Server) ✅

### 1.1 Audio Capture
- Use **WASAPI Loopback** to capture system audio (all desktop sound)
- Capture in the system's native format (typically 44.1/48 kHz, 16/24-bit, stereo)
- Language: **Rust** (for performance and low latency) or **C# / .NET** (faster dev, good WASAPI bindings via NAudio)
- Recommended: **Rust** with `cpal` crate for cross-platform audio capture

### 1.2 Audio Encoding
Support two codec modes:
| Mode | Codec | Bitrate | Latency | Use case |
|------|-------|---------|---------|----------|
| Low-latency | **Opus** | 128–320 kbps | ~5–20ms | General listening |
| Lossless | **FLAC** | ~800–1400 kbps | ~30–50ms | Audiophile / monitoring |

- Encode in small frames (10–20ms for Opus, small FLAC blocks)
- Use RTP-like framing: sequence number + timestamp + payload

### 1.3 Network Transport
- **Control channel**: TCP or WebSocket — handles discovery, session management, clock sync
- **Audio stream**: Two options:
  - **UDP Multicast** — efficient for many receivers, one stream serves all devices
  - **TCP per-client** — simpler, reliable, slightly higher latency
- Recommended: Start with **TCP per-client**, add UDP multicast in Phase 3
- Frame protocol: `[seq_u32][timestamp_u64][codec_u8][payload_len_u16][payload]`

### 1.4 Device Discovery
- **mDNS/DNS-SD** (via `mdns-sd` crate or similar) — advertise `_devicelink._tcp.local`
- Clients auto-discover server on LAN without manual IP entry

**Deliverable**: PC app that captures audio, encodes to Opus, and streams to a single TCP client.

---

## Phase 2 — Android Client ✅

### 2.1 Tech Stack
- **Kotlin** with Jetpack Compose for UI
- **Oboe** (C++ via JNI) or **AAudio** for low-latency playback
- Alternatively: `AudioTrack` in low-latency mode (simpler, adequate for most cases)

### 2.2 Core Features
- mDNS discovery — scan for `_devicelink._tcp.local` services
- TCP connection to server, receive framed audio packets
- Decode Opus (via `libopus` JNI) or FLAC (via `libFLAC` or built-in Android decoder)
- Jitter buffer (20–100ms configurable) to smooth network variance
- Playback via `AudioTrack` / Oboe

### 2.3 UI (Minimal)
- Server list (auto-discovered + manual IP entry)
- Connect/Disconnect button
- Volume control
- Latency / buffer indicator
- Codec & quality display

**Deliverable**: Android app that discovers server, connects, and plays audio with acceptable latency.

---

## Phase 3 — Multi-Device Synchronization ✅

### 3.1 Clock Synchronization
- Implement **NTP-like clock sync** between server and each client
- Target: <2ms clock accuracy

### 3.2 Synchronized Playback
- Each audio frame carries an **absolute presentation timestamp**
- Clients schedule playback at `presentation_time + configured_buffer`

### 3.3 Transport Upgrade
- Move audio to **UDP multicast** — one stream, all clients receive
- Keep TCP control channel for session management and clock sync

**Deliverable**: Multiple Android devices playing audio in audible sync (<5ms drift).

---

## Phase 4 — Polish & Advanced Features ✅

- System tray, web control panel, background playback, adaptive buffer, auto-reconnect, etc.

---

## Phase 5 — Advanced Features ✅

- Per-app audio source selection, FLAC codec, PIN auth, saved profiles, volume groups, adaptive jitter buffer, auto-reconnect, system tray.

---

## Phase 6 — Bidirectional Streaming (Android ↔ Android, Android → PC)

### 6A. Protocol Extensions
- Add `AudioFrame.serialize()` to Android `Protocol.kt` (currently only has deserialize)
- Add `ServerInfo` and `ClientAccepted` serialization to Android `ControlMessage` (so Android can act as server)
- Rust protocol already handles all variants bidirectionally via serde

### 6B. Android Audio Capture

#### 6B.1 AudioCaptureSource Interface
- Common interface for all capture sources
- Methods: `start(sampleRate, channels, onChunk)`, `stop()`, `release()`

#### 6B.2 Microphone Capture (`MicCapture.kt`)
- Uses `AudioRecord` with `MediaRecorder.AudioSource.MIC`
- Requires `RECORD_AUDIO` permission
- Works on all API levels >= 24
- Reads PCM 16-bit in 20ms chunks

#### 6B.3 System Audio Capture (`SystemAudioCapture.kt`)
- Uses `MediaProjection` + `AudioPlaybackCapture` (API 29+ only)
- Requires user consent via `MediaProjectionManager.createScreenCaptureIntent()`
- Captures system audio mix (music, videos, calls)
- UI hides this option on devices < API 29

#### 6B.4 Audio Encoder (`AudioEncoder.kt`)
- Converts `ShortArray` → `AudioFrame` with PCM codec
- Assigns monotonic sequence numbers and timestamps
- PCM first (reliable), Opus via native libopus later

### 6C. Android Server Mode

#### 6C.1 TCP Server (`AudioServer.kt`)
- Binds `ServerSocket` on configurable port (default 4953)
- Accepts TCP clients, performs handshake (ClientJoin → ClientAccepted)
- Broadcasts encoded audio frames to all clients via `MutableSharedFlow`
- Tracks connected clients, handles control messages
- Validates optional PIN

#### 6C.2 mDNS Registration (`ServerDiscovery.kt`)
- Registers `_devicelink._tcp.` via `NsdManager.registerService()`
- Includes TXT records: codec, sample_rate, channels
- Existing Android/PC receivers auto-discover it

#### 6C.3 Foreground Service (`AudioCaptureService.kt`)
- `foregroundServiceType="mediaProjection|microphone"`
- Holds wake lock + wifi lock
- Manages AudioServer lifecycle
- Notification: "Broadcasting audio to N clients"

#### 6C.4 Manifest Changes
- Add `RECORD_AUDIO` permission
- Add `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission
- Add `FOREGROUND_SERVICE_MICROPHONE` permission
- Register `AudioCaptureService`

### 6D. PC Receiver Mode (Rust)

#### 6D.1 Receiver (`receiver.rs`)
- Connects to specified `host:port` via TCP
- Sends `ClientJoin` (with optional PIN)
- Receives `ClientAccepted` or `AuthRequired`
- Read loop: receives `AudioFrame`s, decodes, plays
- Handles clock sync responses
- Includes jitter buffer for smooth playback

#### 6D.2 Decoder (`decoder.rs`)
- Inverse of `encoder.rs`
- PCM: i16 LE bytes → f32 samples (trivial)
- Opus: `opus` crate `Decoder` (already a dependency)
- FLAC: `claxon` crate for decoding

#### 6D.3 Audio Playback (`audio_playback.rs`)
- Uses `cpal` output stream (already a dependency)
- Opens default output device
- Ring buffer feeds decoded samples to output callback

#### 6D.4 Config Changes
- `--mode <serve|receive>` (default: serve)
- `--connect <host:port>` (required in receive mode)
- `--client-name <name>` (name sent in ClientJoin)

#### 6D.5 Main Entry Point
- If `mode == serve`: existing server flow
- If `mode == receive`: run `receiver::start_receiver()`

### 6E. Android Sender UI

#### 6E.1 Mode Toggle
- Segmented button at top: "Receive" | "Send"
- Persisted in ProfileManager

#### 6E.2 Sender View
- Audio source selector: Microphone / System Audio (API 29+ only)
- Start/Stop Broadcasting button
- Connected clients list (name, address)
- Server port configuration
- PIN configuration

#### 6E.3 Permission Handling
- Runtime permission request for `RECORD_AUDIO`
- `MediaProjection` consent dialog for system audio
- Graceful fallback if permissions denied

### 6F. Discovery Integration
- Android sender advertises same `_devicelink._tcp.` service type
- PC receiver can browse mDNS or connect directly via `--connect`
- Add `browse()` method to Rust `discovery.rs`

---

## Tech Stack Summary

| Component | Technology |
|-----------|-----------|
| PC Server | Rust (`cpal`, `opus`, `flacenc`, `mdns-sd`, `tokio`, `axum`) |
| PC Receiver | Rust (`cpal` output, `opus` decoder, `claxon` FLAC decoder) |
| Android Client | Kotlin + Jetpack Compose, `AudioTrack`, `MediaCodec` |
| Android Sender | Kotlin, `AudioRecord`, `MediaProjection`, `ServerSocket` |
| Audio Codec | Opus (lossy) / FLAC (lossless) / PCM (raw) |
| Transport | TCP (control + audio), UDP multicast (optional) |
| Discovery | mDNS/DNS-SD |
| Clock Sync | Custom NTP-like protocol over TCP |

---

## Milestone Timeline

| Phase | Scope | Status |
|-------|-------|--------|
| **Phase 1** | PC captures & streams audio to one client | ✅ Done |
| **Phase 2** | Android app receives & plays audio | ✅ Done |
| **Phase 3** | Multi-device sync + UDP multicast | ✅ Done |
| **Phase 4** | UI polish, source selection, production features | ✅ Done |
| **Phase 5** | FLAC, PIN, profiles, adaptive buffer, auto-reconnect, tray | ✅ Done |
| **Phase 6A** | Protocol extensions for bidirectional | Pending |
| **Phase 6B** | Android audio capture (mic + system) | Pending |
| **Phase 6C** | Android server mode | Pending |
| **Phase 6D** | PC receiver mode | Pending |
| **Phase 6E** | Android sender UI | Pending |
| **Phase 6F** | Discovery integration | Pending |

---

## New Files for Phase 6

### Android (New)
| File | Purpose |
|------|---------|
| `audio/AudioCaptureSource.kt` | Interface for capture sources |
| `audio/MicCapture.kt` | Microphone capture via AudioRecord |
| `audio/SystemAudioCapture.kt` | System audio via MediaProjection (API 29+) |
| `audio/AudioEncoder.kt` | PCM encoder → AudioFrame |
| `network/AudioServer.kt` | TCP server for sender mode |
| `network/ServerDiscovery.kt` | mDNS service registration |
| `service/AudioCaptureService.kt` | Foreground service for capture+streaming |

### Rust (New)
| File | Purpose |
|------|---------|
| `server/src/receiver.rs` | TCP client, receives + plays audio |
| `server/src/decoder.rs` | PCM/Opus/FLAC decoder |
| `server/src/audio_playback.rs` | cpal output stream |

### Files to Modify
| File | Changes |
|------|---------|
| `Protocol.kt` | Add AudioFrame.serialize(), ServerInfo/ClientAccepted serialize |
| `MainViewModel.kt` | Add sender mode state, broadcasting control |
| `MainScreen.kt` | Add mode toggle, sender UI, permission dialogs |
| `ProfileManager.kt` | Save sender preferences |
| `AndroidManifest.xml` | Add RECORD_AUDIO, FOREGROUND_SERVICE_MEDIA_PROJECTION perms |
| `server/src/main.rs` | Add mode branching (serve vs receive) |
| `server/src/config.rs` | Add --mode, --connect, --client-name flags |
| `server/Cargo.toml` | Add claxon for FLAC decoding, cpal for output |
