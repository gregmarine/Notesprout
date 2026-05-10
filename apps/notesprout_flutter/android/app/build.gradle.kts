plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.notesprout.notesprout_flutter"
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
        applicationId = "com.notesprout.notesprout_flutter"
        minSdk = 29  // BOOX devices are Android 10+; Onyx SDK requires API 29
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    packaging {
        jniLibs {
            // Onyx SDK ships native libs that conflict with Flutter's own copies
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
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

flutter {
    source = "../.."
}

dependencies {
    // Onyx BOOX SDK — versions pinned to match BOOXDemo (proven working on NoteAir5C, Palma2 Pro)
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.3")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")

    // Bypasses Android 14+ JNI enforcement so the BOOX SDK can call hidden system APIs
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
