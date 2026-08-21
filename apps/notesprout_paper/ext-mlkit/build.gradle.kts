plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesprout.ext.mlkit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.notesprout.ext.mlkit"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    // The one engine dependency of the whole project — in THIS module only (never :app, never
    // :extension-api). Same artifact + version the original Notesprout ships.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    testImplementation("junit:junit:4.13.2")
}
