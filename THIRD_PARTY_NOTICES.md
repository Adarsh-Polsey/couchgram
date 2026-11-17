# Third-party code

Couchgram itself is licensed under the GNU General Public License v3.0 (see `LICENSE`). It bundles or vendors the following, each under its own license.

| Component | Where | License |
|---|---|---|
| [TDLib](https://github.com/tdlib/td) 1.8.69 (commit `ea3f69ce`), Java bindings and `libtdjni.so` | `app/src/main/java/org/drinkless/tdlib/`, `app/src/main/jniLibs/` | [Boost Software License 1.0](https://www.boost.org/LICENSE_1_0.txt) |
| [OpenSSL](https://www.openssl.org) 3.5.9 (long-term support), statically linked into `libtdjni.so` | `app/src/main/jniLibs/` | [Apache License 2.0](https://www.openssl.org/source/license.html) |
| [Media3](https://github.com/androidx/media) 1.11.1 Matroska extractor, with one marked patch | `app/src/main/java/tv/couchgram/mkv/` | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| Media3 1.11.1 FFmpeg decoder module | `decoder_ffmpeg/` | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [FFmpeg](https://ffmpeg.org) `release/6.0`, AC-3 / E-AC-3 decoders only, prebuilt static libraries and headers | `decoder_ffmpeg/src/main/jni/ffmpeg/` | LGPL 2.1 or later (`LICENSE.md`, `COPYING.LGPLv2.1` there). Version in `VERSION.txt`; rebuild steps in the README |

Libraries fetched by Gradle at build time (AndroidX, Jetpack Compose, Compose for TV, Media3, kotlinx.coroutines, ZXing) are under the Apache License 2.0.
