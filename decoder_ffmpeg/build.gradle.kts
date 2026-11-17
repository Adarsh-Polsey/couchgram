// Media3's FFmpeg audio decoder (androidx/media 1.11.1, libraries/decoder_ffmpeg), vendored because
// Google doesn't publish it. FFmpeg is prebuilt with only the AC-3 / E-AC-3 decoders: most Fire TV
// Sticks have no Dolby decoder, and many TVs take only stereo PCM over HDMI. See src/main/jni/ffmpeg/VERSION.txt.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += "armeabi-v7a" }
        consumerProguardFiles("proguard-rules.txt")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
}
