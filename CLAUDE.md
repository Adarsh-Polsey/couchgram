# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Couchgram: a Kotlin Fire TV app (Compose for TV, Media3) that streams the videos in a Telegram channel through TDLib. `README.md` explains how it works, the build, and the install steps.

## Commands

```bash
./gradlew :app:testDebugUnitTest                                     # unit tests (release-name parser, phone commands)
./gradlew :app:testDebugUnitTest --tests tv.couchgram.ReleaseTest    # one class
./gradlew :app:assembleDebug                                         # then install: README "Install"
```

## Rules

- `telegram.properties` holds the Telegram API keys and `keystore.properties` the signing key. Both are git-ignored; never read, print or copy them. Built APKs contain the API keys, so they stay private.
- Don't install on a device unless asked: someone may be watching something on it.
- Test fixtures and examples use made-up titles, never real films or release groups.
- Nothing posts to the user's channel unless the phone remote is on (`Prefs.phoneRemote`); keep it that way.
