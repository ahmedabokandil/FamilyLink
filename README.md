# FamilyLink — Serverless Hybrid P2P Android Communication App

A 100% native Android (Kotlin + Jetpack Compose) peer-to-peer communication app for **1-to-1 and family group chat, voice calls, video conferencing, screen sharing, and media sharing** with **no central application server**.

## Download Debug APK

Download the prebuilt debug APK directly from the **[GitHub Releases](../../releases/latest)** page and install it on Android 8.0+ (API 26–35) devices.

## Key Features

- **Hybrid Serverless P2P Networking** (`:core:p2p`):
  - **LAN (Android NSD / mDNS)**: Zero-internet automatic discovery and direct TCP sockets on the same Wi-Fi network or hotspot.
  - **Wi-Fi Direct (`WifiP2pManager`)**: Off-grid, routerless peer-to-peer communication when nearby.
  - **Decentralized Public Relays**: Multi-relay Nostr-compatible WebSocket fallback with ephemeral BIP-340 Schnorr signing and blinded inbox tags so relays act as zero-knowledge dumb pipes across 4G/5G NATs.
  - **Peer-Assisted Gossip Sync**: Vector-clock anti-entropy catch-up so any online family member delivers missed signed messages to offline members when they reconnect.
- **QR & Remote Family Onboarding**:
  - Signed Contact QR (`famlink://peer/v1?...`) and Family Group Invite QR (`famlink://group/v1?...`) with CameraX live scanner + shareable invite URI fallback.
- **End-to-End Encryption & Local Security** (`:core:crypto`, `:core:data`):
  - Ed25519 signing identity + X25519 ECDH + ChaCha20-Poly1305 AEAD.
  - Group Sender Keys with automatic epoch rotation when a member is removed.
  - Room + SQLCipher encrypted local database keyed via Android Keystore.
  - Argon2id + ChaCha20-Poly1305 `.flbackup` export/import and 33-word checksummed master seed recovery phrase.
- **Rich Messaging**:
  - Resumable 64 KB SHA-256-verified chunked photo, video, and file sharing.
  - Push-to-talk AAC voice notes with inline playback.
  - Emoji reactions, typing indicators, delivery & read receipts.
- **Full-Mesh Voice, Video & Screen Sharing** (`:core:webrtc`):
  - 1-to-1 and family group voice/video calls (2–6 video callers, up to ~10 voice) with DTLS-SRTP encryption, deterministic glare resolution, and adaptive video resolution/bitrate scaling.
  - Live `MediaProjection` screen sharing with auto-pinning on remote screens.
  - Persistent Android Foreground Service + lock-screen full-screen incoming call notifications (zero Firebase/FCM required).

## Build From Source

```bash
./gradlew :core:crypto:test :core:p2p:testDebugUnitTest :core:webrtc:testDebugUnitTest :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
