plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesproutsn.ext.sketch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesproutsn.ext.sketch"
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
    // The extension owns the SKETCH point (arc 43 / K5) — SN's TENTH point, the sixth screen-owning
    // one and the third with paper: a raster sketch surface over g-paper (through :sn-screen) with
    // the chrome/handoff base from :ext-ink's PaperScreenActivity and its InkWire for the "Bring in
    // ink" door. Never :app; no Room / SQLCipher / serialization — the pixels live in the host's
    // .soil and cross the seam as chunked PNG; there is no store at all.
    implementation(project(":extension-api"))
    implementation(project(":sn-screen"))
    implementation(project(":ext-ink"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
