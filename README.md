<p align="center"><img src="assets/icon/hoard_icon.png" width="128" alt="Hoard icon"></p>

# Hoard

Save videos from Instagram, TikTok, X and more without leaving the app. Tap **Share**, pick
**Hoard**, and the video downloads in the background to `Movies/Hoard/`.

Android 10+ only. No backend: downloads run on your device using
[yt-dlp](https://github.com/yt-dlp/yt-dlp) through
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android).

## Features

- Share-sheet download that never takes you out of the source app
- Progress and completion notifications with thumbnail, file name and uploader
- Paste-a-link box and a clipboard banner for copied links
- Works with any site yt-dlp supports; unsupported links get a clear notification
- Optional in-app login for Instagram and TikTok (cookies stored encrypted on the device)
- Library grid and in-app player for everything in `Movies/Hoard/`
- yt-dlp updates itself, with a manual update button in the app

## Install

Download the APK from [Releases](../../releases). Android's Play Protect may warn about an
unknown app because Hoard is not distributed through Google Play.

## Build

Requires Flutter (stable) and the Android SDK.

```
flutter pub get
flutter analyze
flutter build apk --release
```

The APK lands in `build/app/outputs/flutter-apk/`.

## Disclaimer

For personal use, rewatching and not losing content you care about. Respect creators and each
platform's terms.
