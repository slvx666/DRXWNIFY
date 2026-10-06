<div align="center">

<img src="assets/icon.png" width="160" alt="Drxwnify" />

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/logo_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="assets/logo_light.png">
  <img src="assets/logo_dark.png" width="360" alt="DRXWNIFY">
</picture>

### Your Spotify or Yandex Music library: listen and download without a subscription

[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#-install)
[![Release](https://img.shields.io/github/v/release/slvx666/DRXWNIFY?style=for-the-badge&color=c62828)](https://github.com/slvx666/DRXWNIFY/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-455a64?style=for-the-badge)](LICENSE)

[RU · Русский](README.md) &nbsp;|&nbsp; **EN · English**

</div>

---

> [!NOTE]
> 💻 **The PC / Desktop version (Windows / Linux / macOS) is currently in active development!**

---

## ⚡ About Drxwnify

**Drxwnify** is an uncompromising music player for Android built around your favorite streaming catalog: library, playlists, Liked Songs, albums, artists, and search. For every track, the app resolves audio streams across multiple independent sources in parallel. No Premium subscription required.

<table>
<tr>
<td width="50%" valign="top">

### 📚 Your Library & Sync
- Sign in to **Spotify** or **Yandex Music** directly in the app
- Playlists, albums, artists, and Liked Songs faithfully synchronized
- Two-way sync: likes, saved albums, and followed artists sync to your account
- **Playlist Import**: easily import external playlists
- Search prioritizing exact matches

</td>
<td width="50%" valign="top">

### 🎧 Audio & Diagnostics
- Tracks resolved across multiple sources in parallel within seconds
- Strict matching by title, artist, duration, and ISRC code
- **Track Spectrogram Viewer**: built-in spectrogram analyzer to verify genuine audio bitrates and frequency cutoffs
- Manual fallback override if an unexpected recording is matched
- Seamless failover to subsequent providers

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 💾 Downloads & Export
- Download albums, playlists, and Liked Songs in one tap as tagged MP3s with covers
- **YouTube Music Video Downloader**: download video clips directly in-app
- **Photo / Cover Downloader**: save album art and artist pictures in original high resolution
- **Share Playlist / Album via 2 buttons**: quickly send all downloaded audio files in a single batch

</td>
<td width="50%" valign="top">

### ✨ Recommendations & Features
- **High-Quality Recommendations**: smart "For You" endless radio with taste-aware curation and fresh track rotation
- Artist profiles with full discographies
- Real-time Listen Together with friends
- Time-synced lyrics with AI translations, equalizer, sleep timer, widgets

</td>
</tr>
</table>

---

## 🔊 Supported Audio Sources

| Source | Highlights | Account Needed |
|:--|:--|:--:|
| **YouTube & YouTube Music** | Vast catalog of official songs and music videos | — |
| **SoundCloud** | Underground artists, remixes, live sets | — |
| **VK Music** | Huge CIS and international audio library | ✔ |
| **Bandcamp** | Independent creators and label releases | — |
| **Audius** | Decentralized Web3 streaming network | — |
| **Soulseek** | P2P network for rare and lossless gems | ✔ |

Source priorities and toggles can be configured under **Settings → Integrations → Music sources**.

---

## 🔒 Privacy

- **No proprietary servers, advertisements, or tracking telemetry.** The app collects zero user data.
- **Everything is stored locally on device:** credentials, tokens, cache, and downloads.
- **Direct requests** to Spotify, Yandex Music, YouTube, and fallback providers.
- **Open Source:** fully inspectable and reproducible builds.

---

## 📥 Install

1. Download the latest `Drxwnify.apk` from [**Releases**](https://github.com/slvx666/DRXWNIFY/releases/latest).
2. Open the APK and allow installation.
3. On first launch, link your Spotify or Yandex Music account.

In-app updates: **Settings → Check for updates**. Updates install cleanly over existing builds while preserving your data.

> Requires Android 8.0 or newer.

---

## 🛠 Building from source

Requirements: JDK 21 and Android SDK.

```bash
git clone https://github.com/slvx666/DRXWNIFY.git
cd DRXWNIFY
./gradlew :app:assembleFossRelease
```

Output APK will be located at: `app/build/outputs/apk/foss/release/`.

---

## ⚖️ License

Distributed under the [GPL-3.0](LICENSE) license.

*Drxwnify is an independent open-source client and is not affiliated with Spotify AB, Google LLC, Yandex, or VK.*
