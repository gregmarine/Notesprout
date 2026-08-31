plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesproutsn.ext.document"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesproutsn.ext.document"
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

    sourceSets {
        // The proofread JVM tests load the real gzipped dictionary this APK ships, so the asset
        // directory doubles as a test-resource root (classpath: proofread/en_82765.dict).
        getByName("test") { resources.srcDir("src/main/assets") }
    }

    // The editor's production code logs through Slog → android.util.Log; on the JVM the framework
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
    // The extension owns the DOCUMENT EDITOR screen (arc 19): the contract + the shared screen
    // library (g-paper arrives through its `api`), plus `:markdown` (the shared markdown engine)
    // because the editor renders the Preview through it. Never :app; no Room / SQLCipher /
    // serialization — its data lives in the host's extension store, and every `.soil` byte stays
    // host-side.
    implementation(project(":extension-api"))
    implementation(project(":sn-screen"))
    implementation(project(":markdown"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // SymSpellKt — pure-Kotlin spell checker for the editor's proofread (arc 19 / M10; approved
    // 2026-08-30, module-local like :ext-pdf's pdfbox — it never leaks into another module).
    // The bundled dictionary asset is assets/proofread/en_82765.dict (gzip content, opaque
    // extension on purpose — AAPT gunzips any `.gz` asset and strips the extension) with its
    // attribution in NOTICE.txt beside it.
    implementation("com.darkrockstudios:symspellkt:3.4.0")
    testImplementation("junit:junit:4.13.2")
}
