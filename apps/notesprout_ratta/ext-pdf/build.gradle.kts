plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesproutsn.ext.pdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesproutsn.ext.pdf"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        // Host lockstep (N0 wizard): same stamp as :app, bumped together at arc freezes.
        versionName = "0.1.0-ratta"
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
    implementation(project(":extension-api"))
    // The arc's one new dependency (Apache-2.0), explicitly discussed and approved 2026-08-30 —
    // and it lives HERE and nowhere else: the host and every other module stay clean, and a plain
    // PDF is assembled with the framework's own PdfDocument. pdfbox is used for exactly one thing,
    // the password path (arc 18 / D2), which the framework cannot do at all.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
