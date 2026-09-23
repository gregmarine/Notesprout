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
    // 0.1.36 is Phase 23 "pencil preview tones" — RattaInkMap.pencilPreviewFor: a PENCIL previews
    // in the nearest usable firmware tone rather than always DARK_GRAY (arc 44 / T1). 0.1.37 is
    // Phase 24 "the lead goes wide" — RattaEmr.EMR_MAX 1200 → 9600, so a lead up to 96 px previews
    // at the width it bakes (arc 44 / T3, the hand's walk). 0.1.38 is Phase 25 "graphite on
    // paper" — the 96 px lead baked as a comb of bars across the mark (the travel direction's
    // wobble times a 48 px lever arm); GraphiteGrain now loosens the rim flecks along the
    // stroke, streaks the skate along it, and mottles by a page-space tooth field. Core only,
    // no API change. 0.1.39 is Phase 26 "two rasters" — a raster page holds TWO images,
    // `RasterLayer { GRAPHITE, INK }`, routed by style at the one site `RasterLayer.of` (PENCIL →
    // graphite, every other style → ink); the rubber rubs graphite only and never reads, allocates
    // or announces ink; the committed layer is a `DARKEN` flatten of the two, which is
    // order-independent and therefore has no top and no bottom; `load/get/copy/read/swapPageRaster`
    // and both `onRaster*` callbacks gained layered forms, with the un-layered ones meaning
    // GRAPHITE so a pencil-only host compiles and behaves unchanged (arc 45 "Ink" / G1). 0.1.40
    // is Phase 27 — a WHITE pencil lead previews LIGHT_GRAY on Ratta (every grey still tops out
    // at GRAY), for the four-tone palette of 2026-09-18; ratta only, no API change.
    api("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.57")
    api("com.symmetricalpalmtree.gpaper:gpaper-ratta:0.1.57")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
