# Contributing

Bug reports and pull requests are welcome.

## Reporting a bug

Open an issue with:

- the device (for example "Fire TV Stick 4K Max, Fire OS 8") and the Couchgram version (the device's app settings show it);
- what you did, what you expected, and what happened;
- for playback problems, the file's container, video codec, resolution and audio format (MediaInfo shows them). Please don't attach the video or link to it.

A debug build logs what it does: `adb logcat -s Compat:I SmartCache:I Release:I Library:I`.

## Pull requests

1. Build and test first: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (setup in the README).
2. Keep changes focused; one feature or fix per pull request.
3. Follow what's there:
   - UI uses the roles in `app/src/main/java/tv/couchgram/ui/` (colours, spacing, type, buttons), never raw values.
   - Every user-facing string goes in `res/values/strings.xml`.
   - Everything must work with a D-pad remote: every screen sets initial focus, and Back always does something sensible.
   - Couchgram never posts to the user's channel unless the phone remote is on.
   - Test fixtures and examples use made-up titles, never real films or release groups.
4. Never commit `telegram.properties`, `keystore.properties`, signing keys, or an APK.

## Updating the prebuilt libraries

TDLib (`libtdjni.so`) and FFmpeg (`decoder_ffmpeg/src/main/jni/ffmpeg/`) are prebuilt; the README has the rebuild steps. Compilers embed source paths, so before committing a rebuilt binary, check it doesn't contain your home directory:

```bash
strings -a app/src/main/jniLibs/armeabi-v7a/libtdjni.so | grep "$HOME"
```

## License

By contributing, you agree that your contributions are licensed under the GPL-3.0, like the rest of the project.
