plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesproutsn.ext.bible"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesproutsn.ext.bible"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        // Host lockstep: the same stamp as :app, bumped together at arc freezes.
        versionName = "0.1.0-sn"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // The dev extension serves the dev host.
            buildConfigField("String", "HOST_PACKAGE", "\"com.symmetricalpalmtree.notesproutsn.dev\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "HOST_PACKAGE", "\"com.symmetricalpalmtree.notesproutsn\"")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // The bundled Berean Standard Bible (`assets/bible/bsb.bible`, a read-only SQLite file built
    // by `tools/bible/build_bible_db.py --slim`) must not be compressed: it is copied out of the
    // APK byte-for-byte on first run and opened as a database (the one sanctioned "extension
    // writes to disk" exception — re-derivable APK content, never user data).
    androidResources {
        noCompress += "bible"
    }

    // The screen's production code logs through Slog → android.util.Log; on the JVM the framework
    // stubs must return defaults instead of throwing "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // No `tools:replace` and no libc++ `pickFirsts`: those exist in Paper only because the Onyx SDK
    // arrives through its shared screen module. SN has no Onyx.
}

dependencies {
    // The extension owns the BIBLE point (arc 37 / B0): the contract, plus `:sn-screen` for the
    // design system its chrome is drawn from (colors, dimens, styles, `Dialogs`, `TopGuard`,
    // `Slog`). It is the tag manager's shape — the fifth tier-2 screen and the second with
    // **no paper on it**: no `PaperView`, no engine, and therefore no EPD handoff.
    // Never :app; no Room / SQLCipher / serialization — the reader's one row of state lives in the
    // host's extension store, because an extension writes nothing to disk itself, ever.
    implementation(project(":extension-api"))
    implementation(project(":sn-screen"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
