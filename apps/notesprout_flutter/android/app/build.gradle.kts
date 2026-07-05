plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.symmetricalpalmtree.notesprout"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // Distinct from the native app (com.notesprout.android / .android.dev) so this spike
        // installs alongside them.
        applicationId = "com.symmetricalpalmtree.notesprout"
        // Onyx SDK requires API 29+; all target BOOX devices are 64-bit ARM.
        minSdk = 29
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // onyxsdk-base and other native deps each ship libc++_shared.so — keep the first.
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Onyx (BOOX) EPD raw-drawing SDK — the whole point of the spike.
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.3")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")
    // Lets the Onyx SDK reach hidden Android APIs on API 28+ (VMRuntime/RawInputManager).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}

flutter {
    source = "../.."
}
