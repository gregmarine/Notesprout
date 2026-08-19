plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesprout.paperscreen"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        // Slog gates on this module's own BuildConfig.DEBUG (the app's debug build consumes the
        // library's debug variant, so gating is unchanged).
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val gpaperVersion = "0.1.3"

dependencies {
    // :paper-screen depends on g-paper + androidx only — NEVER on :app, :extension-api, Room,
    // SQLCipher or serialization (PAPER_SCRATCHPAD_PLAN.md Appendix B). g-paper is `api` because
    // both consumers (:app, :ext-scratchpad) write against PaperView / Stroke.
    api("com.symmetricalpalmtree.gpaper:gpaper-core:$gpaperVersion")
    api("com.symmetricalpalmtree.gpaper:gpaper-onyx:$gpaperVersion")
    api("com.symmetricalpalmtree.gpaper:gpaper-ratta:$gpaperVersion")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
