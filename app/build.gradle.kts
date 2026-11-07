plugins { id("com.android.application") }

android {
    // Local debug keystore uses Android SDK defaults.

    namespace = "com.downloadanything.pro"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.downloadanything.pro"
        minSdk = 24
        targetSdk = 36
        versionCode = 6017
        versionName = "6.0.17"
        testInstrumentationRunner = "com.downloadanything.pro.ReleaseAcceptanceRunner"
        // UI-only: safe system insets, navigation clearance, responsive buttons.
        // Historical versionCode = 569; versionName = "5.6.9"
        // Oct 10 plan: existing download engine and extraction paths preserved.
        // Historical versionCode = 568; versionName = "5.6.8"
        // Audio converter persistent actions, file confirmation, tool labels, FFmpeg verification intact.
        // Historical versionCode = 567; versionName = "5.6.7"
        // Fast-preview first + safer TikTok Store share parsing and Facebook same-ID fallback.
        // Historical versionCode = 566; versionName = "5.6.6"
        // UI TikTok short-link fallback; historical versionCode = 565, versionName = "5.6.5"
        // LIVE user links: public yt-dlp UA defaults + Facebook share direct extractor fallback.
        // Historical versionCode = 564; versionName = "5.6.4"
        // Facebook /share link recovery, asynchronous social redirects
        // Historical 5.6.3: versionCode = 563; versionName = "5.6.3"
        // Source and debug-signed candidate, not production-approved without Android live acceptance.
        // Historical compatibility: versionCode = 562; versionName = "5.6.2"
        // Historical compatibility: versionCode = 561; versionName = "5.6.1"
        // Historical compatibility: versionCode = 560; versionName = "5.6.0"
        // Historical compatibility: versionCode = 551; versionName = "5.5.1"
        // 5.5.1 Facebook post identity safety + Telegram public permalinks
        // 5.5.0 Android writable tempfile + TikTok short-link recovery + A-Z repair
        // Historical regression baseline: versionCode = 540; versionName = "5.4.0"
        // 5.4.0 Social Reliability + Performance + Players + Audio Converter
        // Historical 5.3.1 baseline: versionCode = 531; versionName = "5.3.1"
        // 5.3.0 TikTok/Facebook Reliability + Swipe + Multi-download/UI
        // 5.2.1 TikTok Lite OneLink + Swipe Navigation Final Hotfix
        // 5.2.0 Full App Audit + UI/Preview/Platform Reliability
        // 5.1.0 Background Media + Smart Browser Download
        // Historical 4.9.7 baseline: versionCode = 497; versionName = "4.9.7"
        // Historical quick-tools large-cards baseline: versionCode = 496; versionName = "4.9.6"
        // Historical UI polish baseline: versionCode = 495; versionName = "4.9.5"
        // Historical TikTok live diagnostics baseline: versionCode = 494; versionName = "4.9.4"
        // Historical TikTok hardening baseline: versionCode = 493; versionName = "4.9.3"
        // Historical repair baseline: versionCode = 492; versionName = "4.9.2"
        // Historical UI Phase 7 baseline retained: versionCode = 491; versionName = "4.9.1"
        // Historical regression baselines retained: versionCode = 490; versionName = "4.9.0"; versionCode = 489; versionName = "4.8.9"; versionCode = 488; versionName = "4.8.8"; versionCode = 487; versionName = "4.8.7"; versionCode = 486; versionName = "4.8.6"; versionCode = 485; versionName = "4.8.5"; versionCode = 484; versionName = "4.8.4"
        vectorDrawables { useSupportLibrary = true }
        // ABI splitting replaces legacy ndk abiFilters, producing three
        // device-specific APKs plus a universal compatibility APK.
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    val releaseKeystore = System.getenv("DA_KEYSTORE")
    val releaseStorePassword = System.getenv("DA_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("DA_KEY_ALIAS") ?: "downloadanything"
    val releaseKeyPassword = System.getenv("DA_KEY_PASSWORD")

    signingConfigs {
        if (!releaseKeystore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    testBuildType = "release"
    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".protech6"
        }
        release {
            // Preserve all previously working runtime/FFmpeg reflection features.
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".protech6"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release credential means an unsigned, non-publishable output.
            // A debug certificate must never be silently presented as release.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core:1.15.0") // kept for AGP 8.10.1 / compileSdk 36 compatibility
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Optional Media3 player; original VideoView remains available.
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    val ytdlp = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlp")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlp")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdlp")
}
