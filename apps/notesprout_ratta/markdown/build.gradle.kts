plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.notesproutsn.markdown"
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

    // No returnDefaultValues on purpose: everything JVM-tested here is pure Kotlin, and a test
    // that strays into android.text should fail loudly ("not mocked") rather than pass against
    // stubs that lie. The renderer and MarkdownDraw are exercised through their consumers.
}

dependencies {
    // `:markdown` is the shared markdown engine — one engine, no drift. It depends on NOTHING in
    // this project (never `:app`, `:sn-screen`, or `:extension-api`) and on no library beyond the
    // Kotlin stdlib + the android SDK the renderer's spans come from. `:app` and `:ext-document`
    // both consume it; anything that would pull a dependency in here belongs in a consumer.
    testImplementation("junit:junit:4.13.2")
}
