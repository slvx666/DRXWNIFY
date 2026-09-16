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
[![Release](https://img.shields.io/github/v/release/DrxwnSlvt/Drxwnify?style=for-the-badge&color=c62828)](https://github.com/DrxwnSlvt/Drxwnify/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-455a64?style=for-the-badge)](LICENSE)

[RU · Русский](README.md) &nbsp;|&nbsp; **EN · English**

</div>

---

## What it is

**Drxwnify** is an Android music player built around your own account: library, playlists, Liked Songs, albums, artists and search. For every track it finds the audio by itself, across several sources at once. No Premium or subscription needed.

<table>
<tr>
<td width="50%" valign="top">

### 📚 Your library
- Sign in to **Spotify** or **Yandex Music** right in the app
- Playlists, albums, artists and Liked Songs as you know them
- Likes, saved albums and followed artists sync both ways
- Search puts exact matches first

</td>
<td width="50%" valign="top">

### 🎧 Audio
- Each track is searched on several sources in parallel, usually in a couple of seconds
- Strict matching by title, artist and length
- Wrong recording? Pick the right one from the track menu
- If a source is down, the next one plays

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 💾 Downloads
- Albums, playlists and Liked Songs in one tap
- MP3 with cover art and tags from your service
- A playlist goes into one folder, an album into the artist's folders
- Shows how much is already downloaded and fetches only what's missing

</td>
<td width="50%" valign="top">

### ✨ And more
- Artist pages with the full discography and similar artists
- "Up next" built from similar artists
- Listen together with friends
- Lyrics, equalizer, sleep timer, widgets, music recognition

</td>
</tr>
</table>

## 🔊 Audio sources

| Source | Good for | Sign-in |
|:--|:--|:--:|
| **YouTube** | The largest catalog | — |
| **SoundCloud** | Independent artists, remixes | — |
| **VK Music** | Lots of Russian-language music | ✔ |
| **Bandcamp** | Independent labels and releases | — |
| **Audius** | Open music platform | — |
| **Soulseek** | Rare releases and lossless, last resort | ✔ |

Turn sources on or off and set their order in **Settings → Integrations → Music sources**.

## 🔒 Privacy

- **No own server, no ads, no analytics.** The app collects nothing about you.
- **Everything stays on the phone:** accounts, likes, settings and downloads.
- **Requests go straight** to Spotify, Yandex Music, YouTube and the other sources, like a browser does.
- **Listen Together, Discord and Soulseek** only work if you turn them on.
- **Open source:** you can read the code and build the app yourself.

## 📥 Install

1. Download `Drxwnify.apk` from [**Releases**](https://github.com/DrxwnSlvt/Drxwnify/releases/latest).
2. Open it and allow installing from that source.
3. On first launch, connect Spotify or Yandex Music.

Updates: **Settings → Check for updates**. The new version downloads and installs over the old one, keeping your data.

> Requires Android 8.0 or newer.

## 🛠 Build from source

Requires JDK 21 and the Android SDK.

```bash
git clone https://github.com/DrxwnSlvt/Drxwnify.git
cd Drxwnify
./gradlew :app:assembleFossRelease
```

The APK ends up in `app/build/outputs/apk/foss/release/`.

## ❓ FAQ

<details>
<summary><b>Do I need Spotify Premium or Yandex Plus?</b></summary>
<br/>
No. Only your library and track information come from the account; the audio is found on other sources.
</details>

<details>
<summary><b>The wrong version of a track plays. What now?</b></summary>
<br/>
Open the track menu (⋯) → "Choose audio source" and pick the right recording. The choice is remembered.
</details>

<details>
<summary><b>Where do downloads go?</b></summary>
<br/>
To <code>Music</code> on the phone by default. You can pick another folder in the storage settings.
</details>

<details>
<summary><b>Can I connect both Spotify and Yandex Music?</b></summary>
<br/>
Yes. The accounts are kept separate; in settings you choose which one provides the library and search.
</details>

## ⚖️ License

Released under the [GPL-3.0](LICENSE) license.

Drxwnify is an unofficial app, not affiliated with Spotify, Yandex, Google, VK or any other service. All trademarks belong to their owners. Use it for personal listening and respect artists' rights.
