plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    // Deliberately NOT the app's namespace: the R/BuildConfig classes would collide. The Kotlin
    // packages of everything that moved here are unchanged (`…notesproutsn.core` / `.notebook`), so
    // the move needed no import sweep in `:app` — only the `R` imports of the two helpers that
    // reference resources.
    namespace = "com.symmetricalpalmtree.notesproutsn.screen"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        // Slog gates on this module's own BuildConfig.DEBUG. The app's debug build consumes the
        // library's debug variant, so the gate means exactly what it meant in `:app`.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // PageGestures and anything else that logs runs through Slog → android.util.Log; on the JVM
        // the framework stubs return defaults instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // `:sn-screen` depends on g-paper + androidx only — NEVER on `:app`, and NEVER on
    // `:extension-api`: keeping the contract out of here is what makes the host's `TransferCaps`
    // and the extension's own ink mapping deliberate twins rather than one shared class.
    // g-paper is `api` because both consumers write against PaperView / Stroke.
    api("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.6")
    api("com.symmetricalpalmtree.gpaper:gpaper-ratta:0.1.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
