import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Telegram API keys live in telegram.properties (git-ignored), never in source. See README "Build it yourself".
val telegram = Properties().apply {
    val f = rootProject.file("telegram.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
if (telegram.getProperty("TELEGRAM_API_ID").isNullOrBlank()) {
    logger.warn("telegram.properties is missing TELEGRAM_API_ID / TELEGRAM_API_HASH: the app builds, but sign-in will fail.")
}

// Release signing, optional: keystore.properties (git-ignored) with storeFile, storePassword, keyAlias, keyPassword.
// Without it, release builds come out unsigned.
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "tv.couchgram"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tv.couchgram"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 5
        versionName = "0.5.0"

        // TDLib is built for 32-bit ARM only (see README); every Fire TV runs 32-bit apps.
        ndk { abiFilters += "armeabi-v7a" }

        buildConfigField("int", "TELEGRAM_API_ID", telegram.getProperty("TELEGRAM_API_ID", "0").ifBlank { "0" })
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${telegram.getProperty("TELEGRAM_API_HASH", "")}\"")
        // ./gradlew assembleDebug -Pstress=true: tiny cache limits so every eviction rule fires within
        // minutes, in a separate cache dir and watch store (see MediaCache.kt). Never ship it.
        buildConfigField("boolean", "STRESS", (project.findProperty("stress") == "true").toString())
    }
    signingConfigs {
        if (keystore.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(project(":decoder_ffmpeg"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    // Nullness annotations in the vendored MKV extractor (tv.couchgram.mkv).
    compileOnly(libs.checker.qual)
    testImplementation(libs.junit)
}
