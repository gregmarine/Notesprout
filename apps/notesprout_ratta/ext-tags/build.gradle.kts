plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesproutsn.ext.tags"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesproutsn.ext.tags"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        // Host lockstep: the same stamp as :app, bumped together at arc freezes.
        versionName = "0.1.0-ratta"

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
    // The extension owns the TAG MANAGER screen (arc 21): the contract, plus `:sn-screen` for the
    // design system its chrome is drawn from (colors, dimens, styles, `Dialogs`, `TopGuard`,
    // `Slog`). It is the FIRST tier-2 screen with **no paper on it** — g-paper arrives through
    // `:sn-screen`'s `api` and is deliberately never touched here: no `PaperView`, no engine, and
    // therefore no EPD handoff (M3's measured answer — stop-behind is enough behind a non-drawing
    // child screen, cross-process included).
    // Never :app; no Room / SQLCipher / serialization — the tag index lives in the host's extension
    // store, because an extension writes nothing to disk itself, ever.
    implementation(project(":extension-api"))
    implementation(project(":sn-screen"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
