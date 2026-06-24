# Zyna Android

**Zyna Android is an open-source native Android client for Matrix, focused on encrypted communication, durable messaging, native MatrixRTC calls, rich media, and a custom native UI.**

This repository contains the Android client. The current Android release is an early `0.1.0` closed-testing preview and is not expected to be at full feature parity with the iOS app yet.

<p align="center">
<img width="2048" height="2048" alt="zynaandroid" src="https://github.com/user-attachments/assets/c4bdc55a-5837-4abe-a8e6-36962a9fa1e2" />

</p>

## Features

- Matrix login, recovery key entry, session restore, sync, room lists, and live room timelines
- Text messaging with replies, edits, forwarding, redactions, read receipts, failed-send states, retry, and discard flows
- Durable local-first outgoing outbox for text, image, voice, edit, and redaction operations
- Rich media support for incoming and outgoing images, grouped photos, blurhash previews, fullscreen viewing, and upload checkpoints
- Voice messages with recording, local draft storage, upload, rendering, and playback
- Encrypted local app database for cached room summaries, timeline messages, outgoing envelopes, and local state
- Native MatrixRTC calling through LiveKit, including ringing state, call banners, audio route control, pickup timeout handling, peer leave handling, and video support
- Native Android shell with room list, chat navigation, warmed chat view, bottom tabs, high-refresh-rate support, and native/Compose screens
- Custom chat rendering with native message cells, context menus, photo viewer transitions, glass input surfaces, and Vulkan-backed chat glass effects
- Play closed testing metadata, launcher icons, Play icon, and feature graphic for the first Android release

## Architecture

Zyna Android separates Matrix protocol work, encrypted local storage, outgoing delivery, media handling, MatrixRTC calls, and native UI rendering into distinct subsystems.

- **Matrix Rust SDK** provides Matrix login, sync, rooms, timelines, media APIs, read receipts, and event operations.
- **Room + SQLCipher** store the encrypted on-device cache for room summaries, timeline messages, outgoing envelopes, upload checkpoints, and local presentation state.
- **Android Keystore** protects local Matrix session data, Matrix store passphrases, and database passphrases.
- **App-owned outgoing outbox** persists user intent before transport, retries safely after restarts, and reconciles local messages with accepted Matrix events.
- **Matrix media loaders** manage image/audio downloads, memory and disk caching, blurhash previews, and background decode work.
- **Native MatrixRTC + LiveKit** provide the Android calling stack, including membership lifecycle, key transport, LiveKit focus discovery, encrypted media keys, and native call UI state.
- **Native Views + Compose** are used together: Compose covers compact authentication/recovery screens while native Views handle the performance-sensitive room list, chat, call, and glass surfaces.
- **Vulkan + C++ shaders** power the chat glass and PaintSplash rendering pipeline through hardware buffer capture and native rendering.

This structure lets Zyna render cached timelines, keep pending sends stable, recover after network loss or app termination, protect local data, and keep the UI responsive while sync, media upload, encryption, calls, and retry work continue in the background.

## Implementation Notes

Detailed notes for specific Android subsystems live in the repo:

- [High refresh rate testing](docs/high-refresh-rate-testing.md)
- [Vulkan PaintSplash glass pipeline](docs/chat-vulkan-paintsplash-glass.md)
- [Vulkan glass performance summary](docs/chat-vulkan-glass-performance-summary.md)

## Requirements

- Android Studio with the bundled JDK
- Android SDK / build tools for target SDK 36
- Android NDK `29.0.14206865`
- A Matrix account on a compatible homeserver
- GitHub Packages credentials for the private Matrix/LiveKit package feeds used by this build

The app currently uses:

- Package: `com.zyna.app`
- Version: `0.1.0`
- Version code: `1`
- Minimum Android: API 26
- Target Android: API 36

## Build

Clone the repository and provide GitHub Packages credentials through Gradle properties:

```bash
git clone https://github.com/markovsdima/Zyna-Android.git
cd Zyna-Android
```

Add credentials to `~/.gradle/gradle.properties` or another Gradle-supported properties source:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PACKAGE_TOKEN
```

Then build and test:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

For a release bundle:

```bash
./gradlew :app:bundleRelease
```

Release signing keys and generated AAB artifacts are intentionally kept outside the repository.

## Current Limitations

- This is an early Android closed-testing preview and is not yet feature-complete against the iOS app.
- Release optimization/minification is disabled for the first closed testing build.
- Some dependencies are resolved from private GitHub Maven package feeds.
- Vulkan chat glass is enabled in the current build and should be tested across real Android devices for performance and compatibility.
- Google Play distribution is handled through closed testing; signed release bundles are not distributed through GitHub.

## Collaboration

Open to commercial collaboration around white-label Matrix clients, private deployments, and custom Android communication products.

Contact: [@markovsdima](https://t.me/markovsdima)

## License

AGPL-3.0-only
