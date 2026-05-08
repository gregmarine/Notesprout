plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.notesprout.notesprout"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.notesprout.notesprout"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // onyxsdk-pen 1.5.x and onyxsdk-pennative both ship libc++_shared.so — pick one.
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
                "lib/x86/libc++_shared.so"
            )
        }
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
        substitute(module("com.tencent:mmkv:1.0.15"))
            .using(module("com.tencent:mmkv:1.3.9"))
    }
}

dependencies {
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.5") {
        exclude(group = "com.android.support")
    }
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4") {
        exclude(group = "com.android.support")
    }
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
