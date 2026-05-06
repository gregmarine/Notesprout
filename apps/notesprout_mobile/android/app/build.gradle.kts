plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.notesprout.notesprout_mobile"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.notesprout.notesprout_mobile"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 26
        targetSdk = 33
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes.add("META-INF/*.kotlin_module")
    }
}

configurations.all {
    resolutionStrategy {
        force("commons-io:commons-io:2.11.0")
        force("com.tencent:mmkv:1.3.9")
    }
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.apache.commons.io:commonsIO:2.5.0"))
            .using(module("commons-io:commons-io:2.11.0"))
    }
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-device:1.1.11") {
        exclude(group = "com.android.support")
    }
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.2.1") {
        exclude(group = "com.android.support")
    }
}

flutter {
    source = "../.."
}
