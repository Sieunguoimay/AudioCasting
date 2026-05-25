# DeviceLink - Feature Ideas

## Quick wins (low effort, high value)

| Feature                     | Description                                                              |
| --------------------------- | ------------------------------------------------------------------------ |
| **Audio visualizer**        | Waveform or VU meter on the desktop GUI showing live audio levels        |
| **Latency display**         | Show end-to-end latency (clock sync data is already there)               |
| **Stream recording**        | Record streamed audio to WAV/FLAC file on receiver                       |
| **Hotkey controls**         | Global keyboard shortcuts to mute/volume/stop without opening the window |
| **Dark/light theme toggle** | Theme switcher in the GUI                                                |
| **Connection history**      | Remember and quick-connect to previously used servers                    |

## Medium effort, high value

| Feature | Description |
|---|---|
| **Audio EQ/filters** | Equalizer, bass boost, compressor on the receiver side |
| **Multiple audio sources** | Mix audio from multiple apps simultaneously |
| **Notification sounds** | Play a sound when clients connect/disconnect |
| **Bandwidth monitor** | Show real-time bitrate/bandwidth usage in the GUI |
| **Auto-start on boot** | Windows service mode (already in installer, but could be deeper) |
| **Multi-room zones** | Name rooms and drag clients between zones for grouped playback |

## Bigger features

| Feature | Description |
|---|---|
| **TLS encryption** | Encrypted transport for use beyond LAN (rustls is easy to add) |
| **Proper Opus on Android** | JNI wrapper for libopus - massive bandwidth savings vs PCM |
| **Chromecast/AirPlay output** | Stream to Chromecast or AirPlay speakers as targets |
| **PC-to-PC streaming** | Desktop receiver GUI (you have the Rust receiver, just needs GUI) |
| **Spotify/media integration** | Show now-playing info (artist, track) from the audio source |
| **Web client** | Browser-based receiver using WebSocket + Web Audio API |
| **Linux server support** | ALSA/PulseAudio capture (partially planned, WASAPI is Windows-only) |

## Technical debt worth fixing

| Item | Why |
|---|---|
| **Android `ControlMessage.serialize()`** | Missing - breaks Android sender responding to control messages |
| **Opus decoder on Android** | Currently a stub; TODO in code says use JNI wrapper |
| **PIN hashing** | sha2 is already in Cargo.toml but unused; PINs sent in plaintext |
| **Unit tests** | Zero tests currently; protocol serialization round-trips would be a good start |
