plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.notesprout.android_poc"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.notesprout.android_poc"
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
    implementation("com.onyx.android.sdk:onyxsdk-device:1.2.0") {
        exclude(group = "com.android.support")
    }
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.4.11") {
        exclude(group = "com.android.support")
    }
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
