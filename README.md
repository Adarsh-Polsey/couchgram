# Couchgram

An unofficial Telegram video player for Fire TV.

Pick any Telegram channel or group your account is in, and Couchgram turns its videos into a TV library: rails for what you're watching, favourites and the latest uploads, resume where you stopped, and playback that starts while the file is still downloading. You sign in by scanning a QR code with your phone, so there's nothing to type on the remote.

It's a player, nothing more: Couchgram has no content of its own and only plays what your Telegram account can already see. Read the [disclaimer](#disclaimer).

**Contents:** [Features](#features) · [What you need](#what-you-need) · [Install](#install) · [First run](#first-run) · [Using it](#using-it) · [Phone remote](#phone-remote-optional) · [Privacy](#privacy) · [Troubleshooting](#troubleshooting) · [FAQ](#faq) · [Limitations](#limitations) · [Build it yourself](#build-it-yourself) · [How it works](#how-it-works) · [Contributing](#contributing) · [Disclaimer](#disclaimer) · [License](#license)

## Features

- **Streams while it downloads.** Playback starts after a few seconds; seeking jumps the download to the new spot.
- **Made for small sticks.** A smart cache plays multi-GB films on devices with only a few GB free, and never fills the device.
- **Picks up where you left off**, near-instantly: a few minutes around the stopping point stay on the device.
- **A real TV library**: the pinned video on top, then Watching, Favourites and Latest rails, with a details band for the focused video. New uploads, deletions, pins and reactions from your phone show up live.
- **Reads detailed file names**, the way media servers do: "Monsoon Letters 2026 1080p HEVC 10bit DD+5.1" shows as the title plus clean details (resolution, codec, languages, audio, runtime). Plain names like "Holiday clip.mp4" stay as they are.
- **Remote-first controls**: jumps that speed up as you keep pressing, a preview of where you'll land, audio and subtitle tracks, picture modes.
- **Plays Dolby audio** (AC-3 / E-AC-3) on devices without a Dolby decoder, by decoding it in software.
- **Optional phone remote**: reply `/play` to a video in the channel and it starts on the TV.
- **Private by design**: talks only to Telegram, no analytics, and never posts anything unless you turn the phone remote on.

## What you need

- **A Fire TV device** (Fire OS 6 or later). Other Android TV devices that run 32-bit ARM apps should work, but aren't tested. It also runs on phones, but the screens are built for a landscape TV and a remote.
- **A Telegram account.** Couchgram plays the videos in any channel or group the account has joined. A private channel of your own makes a good personal library: family videos, recordings, your own uploads.
- **Videos it can read**:
  - Sent as videos or as files. Files count when Telegram marks them as video, or by extension: MP4, M4V, MOV, MKV, WebM, TS, 3GP.
  - Video: whatever the device decodes in hardware. Fire TV sticks play H.264, HEVC (including 10-bit) and VP9, up to 1080p or 4K depending on the model.
  - Audio: whatever the device decodes (AAC, MP3 and more), plus AC-3 / E-AC-3 in software.
  - Subtitle tracks inside the file.

## Install

There's no prebuilt release yet, so [build it yourself](#build-it-yourself), then sideload it:

1. On the Fire TV: Settings → My Fire TV → Developer options → turn on **ADB debugging**. (No Developer options? Open My Fire TV → About and click the device name 7 times.) Note the IP address under About → Network.
2. On your computer, with [adb](https://developer.android.com/tools/releases/platform-tools) installed:

   ```bash
   adb connect <fire-tv-ip>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   # Optional: Fire TV leaves adb installs uncompiled; this cuts a cold start from ~6 s to ~1.5 s
   adb shell cmd package compile -m speed-profile -f tv.couchgram
   ```

3. Couchgram shows up under Your Apps & Channels. Sideloaded apps get a square icon on a grey tile; that's Fire TV, not a bug.

To update, install the new APK the same way. Your sign-in, channel and watch progress are kept.

## First run

1. Open Couchgram. A QR code appears; it refreshes by itself every ~30 seconds.
2. On your phone: Telegram → Settings → Devices → **Link Desktop Device**, and scan it.
3. If your account has a cloud password (two-step verification), the TV asks for it. **Show password** lets you check what you typed.
4. Pick the channel or group that holds your videos. The ones you own or manage come first, channels before groups. You can change it any time in **Settings**.

After that, Couchgram opens straight into the library.

## Using it

### Library

- **Header**: how many videos there are and how much space is free, a status note (Waiting for network, Updating, Loading more), and three buttons: **Refresh**, **Storage**, **Settings**.
- **Details band**: everything about the focused video: title and year, languages, resolution and codec, source, audio, subtitles, runtime, size, when it was added, and why it's a favourite.
- **Rails**, top to bottom:
  - **Pinned**: the channel's latest pinned video, as one wide card.
  - **Watching**: unfinished videos, most recent first.
  - **Favourites**: videos pinned in the channel, reacted to by your account, or saved to your Saved Messages. Until there are any, it says how to add some.
  - **Latest**: every video you're not in the middle of, newest first.
- **Cards** show the title, year and version, the size, and one state chip: time left (with a progress bar), **✓ Watched**, **Over 1080p** (or whatever your TV's limit is), **New** (added in the last 3 days and never opened), or the languages. A ★ marks favourites.
- Coming back from a video puts focus on the card you played.

### Playing

Opening a video you've started asks **Resume from …** or **Start over**. Resume starts 10 seconds before where you stopped. A video counts as started after 10 seconds and as watched at 95%.

| Remote key | What it does |
|---|---|
| ◀ ▶ or ⏪ ⏩ | Jump back or ahead: 10 s per press, then 30 s, 1 min and 5 min steps as you keep pressing or hold. The jump lands 0.7 s after your last press. |
| OK or ⏯ | Play / pause. During a jump, lands it right away. |
| ▲ or ☰ | Playback options. |
| ▼ | Shows the progress bar and time. |
| Back | Cancels a jump, then hides the controls, then returns to the library. |
| Home | Pauses; the video is where you left it when you come back. |

While you jump, the bar previews where you'll land and says whether that part plays instantly (it's already on the TV) or loads from Telegram.

**Playback options**: **Picture** (Fit, Fill screen, Stretch), **Audio** (each track with its language, channels and codec) and **Subtitles** (Off, or each track; forced tracks are marked). Picture mode and subtitle language are remembered.

If a video is bigger than the TV's decoders, a warning shows for a few seconds; it may stutter.

### Storage

**Storage** shows free space, the video cache, and what's kept to resume unfinished videos, with a **Clear cache** button. Clearing never touches watch progress.

Streaming needs about 500 MB free (counting what the cache can give back). Below that, the player offers **Clear cache and play**.

### Settings

- **Channel**: the one the library shows, and **Change channel**.
- **Phone remote**: on or off (see below).
- **Sign out**: ends Couchgram's Telegram session on this TV, after a confirmation. It also deletes watch progress, cached video and Couchgram's settings, so the next account starts fresh. Your Telegram account and channels aren't touched.

## Phone remote (optional)

Off by default: unless you turn it on, Couchgram never posts anything. Turn it on in **Settings → Phone remote**. Your account needs permission to post in the channel. Then type these in the channel:

| Command | What the TV does |
|---|---|
| `/play` (as a reply to a video) | Plays it, resuming without asking. Only while Couchgram is open. |
| `/stop` or `/back` | Back to the library. Only while Couchgram is open. |
| `/storage` | Replies with free space and cache size. |
| `/clearcache` or `/clear` | Clears the video cache and replies with what it freed. Waits for `/stop` while a video plays. |

- Each command gets a one-line, silent reply, so you can see the TV heard it. `/play`'s reply names the video.
- `/storage` and `/clearcache` sent while the TV was off are answered the next time Couchgram opens, if they're still the channel's last message.
- `/play` and `/stop` older than 2 minutes are ignored, so an old command can't start a film later.
- While it's on, opening the Storage panel also posts the storage line (at most every 30 seconds), and a crash is posted as one line on the next launch.

## Privacy

- Couchgram talks only to Telegram's servers. No analytics, no ads, no servers of its own. The only permission it asks for is internet access.
- Like any Telegram client, it keeps your chat list and incoming updates on the device. It reads videos only from the chosen channel, and from your Saved Messages to find favourites.
- The Telegram session lives in the app's private storage on the TV and is left out of Android backups. It shows up in Telegram → Settings → Devices, where you can also end it from your phone.
- **Settings → Sign out** ends the session and deletes watch progress, cached video and settings.
- It only writes to your channel when the phone remote is on.

## Troubleshooting

| Problem | What to do |
|---|---|
| The sign-in code doesn't appear | Check the TV's Wi-Fi. After 15 seconds Couchgram offers **Get a new code**. |
| "Finish signing in on your phone" | Telegram asked for a step the TV can't show. Finish it in Telegram on your phone, or press **Start over**. |
| My channel isn't in the list | Your account has to have joined it (archived chats are included). Private chats and bots aren't listed. If you just joined, reopen the list with Settings → Change channel. |
| "Can't open your channel" | The channel was deleted, or the account left it. Press **Choose a channel**, or sign out and use the account that has it. |
| A video is missing from the library | Only videos and video files count (see [What you need](#what-you-need)). Press **Refresh**. Couchgram loads a channel's newest ~1,000 videos and ~1,000 files. |
| "This video can't play on this TV" | The device can't decode its video. Upload a version in H.264 or HEVC at or below the size the message names. |
| It stutters, or the card says "Over 1080p" | The file is bigger than the TV's decoders. Use a smaller version. |
| No sound | AC-3 / E-AC-3 are decoded in software. Other formats (DTS, TrueHD) play only if the device or your receiver handles them; pick another audio track under ▲. |
| An MKV restarts from 00:00 on every jump | The file has no seek index. Remux it (for example `mkvmerge -o fixed.mkv input.mkv`) and upload it again. |
| "Not enough space to stream" | Press **Clear cache and play**, or free up space on the device. |
| "Lost the connection to Telegram" | Check the Wi-Fi and press **Try again**; playback picks up where it stopped. |
| The first start after an adb install is slow | Run the `cmd package compile` line from [Install](#install). |
| The phone remote does nothing | Check it's on in Settings, that Couchgram is open on the TV (for `/play` and `/stop`), and that your account can post in the channel. |

Debug builds log what they do: `adb logcat -s Compat:I SmartCache:I Release:I Library:I`.

## FAQ

**Does it download the whole video?** No. It streams, keeping a cache of at most 40% of the device's usable space (200 MB–2 GB, always leaving 500 MB free). Finished videos are deleted from it automatically.

**Can I use a public channel?** Yes, once your account has joined it.

**Several channels at once?** One at a time; switch with Settings → Change channel. Watch progress is kept per video, so it survives switching.

**Several Telegram accounts?** One at a time: sign out, then sign in with the other.

**Can I watch Saved Messages?** Not as a library; Saved Messages is used to mark favourites.

**Why do I need my own API keys to build it?** Telegram requires every client app to have an API ID; you get one free at my.telegram.org.

**Is it free?** Yes. Couchgram is open-source under the GPL-3.0.

## Limitations

- Built for 32-bit ARM, which Fire TV devices run; there's no x86 build.
- English only, though every string is ready for translation.
- No search or sorting yet: the library is newest first, up to ~1,000 videos and ~1,000 files.
- No thumbnails: cards are coloured gradients, so nothing extra is downloaded.
- One channel or group at a time; secret chats aren't supported.
- Screens are designed for a TV and a D-pad remote, not touch.

## Build it yourself

1. Install Android Studio (or JDK 17+ and the Android SDK, platform 37). The FFmpeg module needs NDK `28.2.13676358`; Android Studio's SDK Manager installs it.
2. Get your own Telegram API keys: sign in at [my.telegram.org](https://my.telegram.org) → **API development tools** → create an app. Copy `telegram.properties.example` to `telegram.properties` (git-ignored) and fill them in.
3. Build and test:

   ```bash
   ./gradlew :app:assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
   ./gradlew :app:testDebugUnitTest     # file-name parser and phone commands
   ```

The APK contains your API keys, so keep your builds to your own devices.

For a signed release build (`./gradlew :app:assembleRelease`), create `keystore.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, release builds come out unsigned.

## How it works

- **TDLib** (Telegram's official client library, built from source) handles sign-in and all Telegram access.
- **`TdDataSource`** feeds Media3/ExoPlayer: it asks TDLib to download from the current play position and hands bytes over as they arrive. Seeking moves the download.
- **Smart cache** (`MediaCache.kt`): Media3's disk cache sits between the player and TDLib, so a 3 GB film plays on a stick with ~3 GB free.
  - TDLib downloads 5% of free space ahead (32–256 MB). Its copy is dropped once past 128 MB, when its window drains, because everything read is already cached.
  - The cache grows to 40% of usable space (200 MB–2 GB, always leaving 500 MB free) and only trims when it nears that cap. First to go: finished videos, then footage over 5 min behind the playhead, then other videos (least recent first), then the oldest resume islands. The playing video's protected window and each file's header and index are never trimmed.
  - Leaving a video keeps a resume island (2 min before, 3 min after) and its index, so Resume starts in ~1.6 s from disk. Reaching 95% marks it watched and deletes its cache.
  - Telegram's copies are deleted at every start-up, so a crash mid-film can't leave a large file behind.
- **Channel** (`ChannelPicker.kt`, `Library.kt`): picked after the first sign-in from the account's channels and groups (its own first) and remembered by chat id, so renaming the channel changes nothing.
- **Watch progress** (`WatchStore.kt`): saved every 5 s, keyed by Telegram's permanent file id. Resume (and a phone `/play`) starts 10 s before where it stopped.
- **Library rails** (`LibraryScreen.kt`, `MovieCard.kt`): built from the loaded videos, the watch store, pins, reactions and Saved Messages. The chat is opened in TDLib, so its updates arrive live.
- **File names** (`Release.kt`): the caption's first line (else the file name) is parsed the way media servers parse file names: title, year, then technical tags in any order (languages, resolution, codec, 10-bit, source, audio, subtitles). Caption lines in the common 🎬 / ⏳ / 🔊 format win when present, and ⏳ gives the runtime that file uploads lack. Debug builds log each parse: `adb logcat -s Release:I`.
- **Remote controls** (`PlayerControls.kt`) and **options** (`PlayerOptions.kt`): the keys and menus described in [Playing](#playing).
- **Device limits** (`DeviceVideo.kt`): the largest size the device's hardware decoders take (720p / 1080p / 1440p / 4K) drives the "Over …" chip and the player's warning.
- **MKV seeking** (`mkv/`, `Extractors.kt`): Media3 1.11.1's Matroska extractor, vendored with one patch (marked "Couchgram"): when the SeekHead only points to a second SeekHead (some muxers put it and the Cues entry at the end of the file), it follows it once. Media3's own treats those files as having no index, so every seek restarted at 00:00. When updating Media3, re-copy `androidx/media3/extractor/mkv` from its sources jar and re-apply the marked changes.
- **Dolby audio** (`decoder_ffmpeg/`): Media3's FFmpeg module, vendored because Google doesn't publish it, with FFmpeg built for AC-3 / E-AC-3 only. It steps in only when the device has no decoder of its own; Android downmixes to stereo for TVs that take only stereo PCM.
- **Channel posts** (`Reporter.kt`, `Commands.kt`): phone remote only. The library lists only videos, so these text posts never show up on the TV.
- **Design system** (`ui/`): screens use its colour, spacing and type roles, never raw values.
- **Launcher images** are rendered by `docs/logo.py` into `res/drawable-xhdpi/`. Fire TV ignores the banner for sideloaded apps and caches tile images even across reinstalls; after changing them: uninstall, install, `adb shell pm trim-caches 999G`, open Your Apps, reboot.

### Tested on

Fire TV Stick (`AFTSS`, Fire OS 7, 1080p, ~1 GB RAM):

| File | Video | Audio | Ready (cold) |
|---|---|---|---|
| 3.1 GB MKV | H.264 1080p (hardware) | E-AC-3 5.1 (FFmpeg) + AAC 2.0, subtitles | ~4 s, resume 1.6 s |
| 2.8 GB MKV | H.264 1912×800 (hardware) | E-AC-3 (FFmpeg) | 6.2 s |
| 2.6 GB MKV | 10-bit HEVC 1920×804 (hardware) | E-AC-3 (FFmpeg) | 5.3 s |
| 1.6 GB MKV | HEVC 1912×800 (hardware) | E-AC-3 (FFmpeg) | 2.8–3.1 s |

MKV start-up needs the header plus the seek index at the end of the file, so every new open downloads a 4 MB probe first. Debug builds log each file: `adb logcat -s Compat:I`.

### Stress-testing the cache

`./gradlew :app:assembleDebug -Pstress=true` builds with tiny limits: 48 MB cache cap, Telegram copy replaced every 24 MB, "watched" at 1%. It uses a separate cache dir and watch store, so real progress is untouched; the normal build deletes the test cache dir. Watch it work with `adb logcat -s SmartCache:I`.

### Rebuilding TDLib

The repo ships a prebuilt TDLib with OpenSSL 3.5 (versions in `THIRD_PARTY_NOTICES.md`). The compiler embeds the full source path in the binary, so clone [tdlib/td](https://github.com/tdlib/td) at that commit into a neutral folder (for example `/tmp/td`, not your home directory) and, from `example/android`:

```bash
./build-openssl.sh <android-sdk> 28.2.13676358 '' openssl-3.5.9    # once; builds every ABI, only armeabi-v7a is used
./build-tdlib.sh <android-sdk> 28.2.13676358 '' '' Java 21 armeabi-v7a
```

With 8 GB of RAM or less, run the second line with `CMAKE_BUILD_PARALLEL_LEVEL=3` in front: TDLib's largest files need about 1.5 GB each to compile.

Then copy `tdlib/libs/armeabi-v7a/libtdjni.so` to `app/src/main/jniLibs/armeabi-v7a/` and `tdlib/java/org/drinkless/tdlib/*.java` to `app/src/main/java/org/drinkless/tdlib/`.

### Rebuilding the Dolby decoder

Prebuilt FFmpeg libraries and headers are in `decoder_ffmpeg/src/main/jni/ffmpeg/` (see `VERSION.txt`). To rebuild: check out `androidx/media` at the Media3 version in use, clone FFmpeg `release/6.0` into `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`, run `./build_ffmpeg.sh <module>/src/main <android-ndk> <host-platform> 25 ac3 eac3` (host platform: `darwin-x86_64` or `linux-x86_64`), then copy `android-libs/armeabi-v7a` and the `*.h` files of `libavcodec`, `libavutil` and `libswresample` across.

## Contributing

Bug reports and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems privately: [SECURITY.md](SECURITY.md).

## Disclaimer

- Couchgram is an independent, unofficial Telegram client built on Telegram's open-source TDLib. It is not affiliated with, endorsed by or sponsored by Telegram or Amazon. Telegram and Fire TV are trademarks of their respective owners.
- Couchgram hosts, provides, indexes and links to no content. It plays only videos in Telegram chats that the signed-in account can already access, just as the official Telegram apps do, and it adds no way to find or reach other content.
- You are responsible for what you watch and share with it. Use it only with videos you own or have the right to view, and follow copyright law and Telegram's Terms of Service.
- The authors and contributors have no control over, and accept no responsibility for, how it is used or what any channel or group contains.
- It is provided "as is", without warranty of any kind, as set out in sections 15 and 16 of the GPL-3.0.

## License

[GPL-3.0](LICENSE). Bundled third-party code and its licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
