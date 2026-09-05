// `:ext-cloud` — **NSE · Cloud Storage**, the one extension on the CLOUD_STORAGE point (arc 25).
// The module, package and label are deliberately **generic over a provider** (the user's call,
// 2026-09-05, renaming arc 25's `:ext-drive` / `NSE · Google Drive`): a second provider is baked in
// HERE, beside Google Drive, never as a second extension. What stays provider-named is the
// implementation — `Drive*` is Google Drive's OAuth flow and REST v3 client and is honestly named;
// a second provider arrives as `Dropbox*.kt` next to it, behind the same `CloudService`. The one
// name a person sees is `CloudService.PROVIDER_NAME` ("Google Drive"), which crosses the seam in
// `CloudStatus.providerName` and is what the host's rows print.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // V2: the token endpoint and Drive's REST v3 speak JSON; kotlinx.serialization is the repo's one
    // JSON rule (zero reflection, code-generated) and is already on the graph through :app — no
    // new library. The V1 note about "hand-rolled JSON" is superseded by this.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.symmetricalpalmtree.notesproutsn.ext.cloud"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesproutsn.ext.cloud"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        // Host lockstep: the same stamp as :app, bumped together at arc freezes.
        versionName = "0.1.0-ratta"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // Drive OAuth client (Desktop-app type). Set DRIVE_CLIENT_ID / DRIVE_CLIENT_SECRET in your
        // shell profile — the same variables og Notesprout reads. Compiled ONLY into this extension
        // APK; the host never sees them. Blank → the provider reports `configured = false` and the
        // host dialogs.
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"${System.getenv("DRIVE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "DRIVE_CLIENT_SECRET", "\"${System.getenv("DRIVE_CLIENT_SECRET") ?: ""}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // The dev extension serves the dev host.
            buildConfigField("String", "HOST_PACKAGE", "\"com.symmetricalpalmtree.notesproutsn.dev\"")
            // Separate root so a dev build never mingles test files under the release tree
            // (DRIVE_PLAN.md decision 9).
            buildConfigField("String", "ROOT_FOLDER_NAME", "\"Notesprout SN Dev\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "HOST_PACKAGE", "\"com.symmetricalpalmtree.notesproutsn\"")
            buildConfigField("String", "ROOT_FOLDER_NAME", "\"Notesprout SN\"")
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
    // The extension owns the CLOUD_STORAGE point (arc 25): the contract, plus `:sn-screen` for the
    // design system its V2 connect screen will draw from (colors, dimens, styles, `Dialogs`,
    // `TopGuard`, `Slog`) — the same reason every other tier-2 extension takes it. Never `:app`: the
    // host stays without INTERNET permission and without OAuth of its own; this module is the only
    // networked process in the app. No OkHttp, no Gson — V2's REST core is HttpsURLConnection plus
    // kotlinx.serialization (the same artifact :app already uses, so nothing new on the graph).
    implementation(project(":extension-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation(project(":sn-screen"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
