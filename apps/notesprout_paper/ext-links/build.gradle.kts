plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesprout.ext.links"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesprout.ext.links"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            // The dev extension serves the dev core.
            buildConfigField("String", "HOST_PACKAGE", "\"com.symmetricalpalmtree.notesprout.dev\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "HOST_PACKAGE", "\"com.symmetricalpalmtree.notesprout\"")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // TrailStoreTest drives the trail over a fake IExtensionStore; Slog's android.util.Log must not
    // throw "not mocked" on the JVM.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The Onyx SDK (via g-paper, which rides in on :paper-screen) ships libc++_shared.so in three
    // artifacts — same rule as :app.
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The extension owns a full-screen PICKER (arc 7): the contract + the shared screen module, which
    // is where the design system lives (g-paper rides along unused — the picker draws no paper).
    // Never :app; no Room / SQLCipher / serialization — its trail lives in the host's extension store.
    implementation(project(":extension-api"))
    implementation(project(":paper-screen"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
