plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    // Its own namespace, like `:sn-screen`: an R/BuildConfig collision with a consumer is the one
    // thing a library module must never risk. There are no resources here anyway.
    namespace = "com.symmetricalpalmtree.notesproutsn.ink"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // InkDocument logs through Slog → android.util.Log; on the JVM the framework stubs return
        // defaults instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // `:ext-ink` (arc 23 / Y1) is the ink-on-rows library shared by every extension that owns a
    // paper surface over the host's extension store — the scratch pad and the calendar. It depends
    // on the contract (`Statement`, `Row`, `WireStroke`, the store caps) and on `:sn-screen`
    // (g-paper's `Stroke` through its `api`, `StrokeCodec`, `Slog`) — and NEVER on `:app`: it
    // is extension-side code, and the host's own twins (`TransferCaps`) stay deliberately apart.
    // No manifest components, no resources: it is pure Kotlin over the two libraries.
    api(project(":extension-api"))
    api(project(":sn-screen"))
    // `InkScreenActivity` (arc 23) is an `AppCompatActivity` a consumer extends, so appcompat is
    // `api` — the same version both consumers already declare, no new library on the graph.
    api("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
