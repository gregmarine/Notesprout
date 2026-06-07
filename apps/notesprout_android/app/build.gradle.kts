plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.notesprout.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.notesprout.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Every target device (BOOX, Wacom Movink, Supernote) is 64-bit ARM.
            // Shipping only arm64-v8a drops the unused x86/x86_64/armeabi-v7a libs —
            // including the one 4 KB-aligned native lib that fails Play's 16 KB
            // page-size check (mmkv's x86_64 .so). The shipped arm64-v8a libs
            // (mmkv 1.0.19, onyxsdk-pen 1.5.4) are already ≥16 KB-aligned. See M-5.
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // Generates BuildConfig.DEBUG — used by core/Slog to strip verbose logging
        // from release builds (M-4). isMinifyEnabled is false, so R8 cannot strip
        // Log calls; the BuildConfig.DEBUG guard is the actual stripping mechanism.
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Onyx SDK ships native libs that conflict with other deps
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Onyx BOOX SDK — same versions as proven in notesprout_flutter
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.3")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")

    // Bypass Android 14+ JNI enforcement so the BOOX SDK can call hidden system APIs
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Room — one instance per open .soil notebook file, managed by DrawingActivity
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // Coroutines — Dispatchers.IO for DB work, Dispatchers.Main for UI updates.
    // kotlinx-coroutines-android provides Dispatchers.Main on Android.
    // lifecycle-runtime-ktx provides lifecycleScope on Activity/Fragment.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // kotlinx.serialization — replaces org.json for stroke data encoding/decoding.
    // Code-generated at compile time (no reflection), significantly faster than org.json
    // for large point arrays.  Wire format is identical JSON so no DB migration is needed.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ML Kit Digital Ink Recognition — general-purpose handwriting-to-text layer.
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
