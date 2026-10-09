# EvenLyrics Companion

Android companion for the **EvenLyrics** plugin on Even Realities G2 smart glasses.
It lets EvenLyrics show synced lyrics for the song currently playing in **YouTube Music** or **Spotify**.

## Download
**[⬇ Latest APK](https://github.com/litas615/evenlyrics-companion/releases/latest/download/evenlyrics-companion.apk)** · Android 10+ only

## How it works
1. Uses Android **Notification Access** (`NotificationListenerService` + `MediaSessionManager`) to read the now-playing title, artist, duration and position.
2. Looks up synced lyrics from the public **[LRCLIB](https://lrclib.net)** API (title, artist and duration only).
3. Streams playback state and lyrics to the EvenLyrics plugin over a local WebSocket bound to `127.0.0.1:5288` — nothing leaves the phone except the LRCLIB query.

No microphone, no audio recording, no account, no analytics.

## Setup
1. Install the APK (allow installs from your browser if Android asks).
2. Open **EvenLyrics Companion** → tap **前往啟用通知存取權限** → allow *EvenLyrics*.
3. Play a song in YouTube Music or Spotify, then open EvenLyrics on Even G2.

## 中文說明
EvenLyrics Companion 讓 Even G2 的 EvenLyrics 插件，跟著手機 YouTube Music／Spotify 正在播放的歌曲顯示同步歌詞。
透過「通知存取權」讀取歌名、歌手、長度與播放進度，向 LRCLIB 查詢同步歌詞，只在手機本機（127.0.0.1）傳給插件。
不使用麥克風、不錄音、無帳號、無追蹤。僅支援 Android 10 以上。

## Build
```bash
./gradlew assembleRelease   # needs JDK 17+ and Android SDK 35
```
Release signing reads `release-signing/keystore.properties` (not included in this repo).

## Privacy
See [PRIVACY.md](PRIVACY.md).

## License
MIT — see [LICENSE](LICENSE). Lyrics are provided by LRCLIB contributors; rights remain with their owners.
