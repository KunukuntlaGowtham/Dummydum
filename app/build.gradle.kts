plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.checkboxticker"
    compileSdk = 34

    // A fixed debug key, checked into the repo, so every build signs the same way. Without
    // this, the Android build tool makes up a new one whenever no keystore exists yet - which
    // on a fresh CI machine is every single build, and Android then refuses to install the new
    // APK over the old one ("app not installed") because their signatures do not match; the
    // only way on was to uninstall first. Signing every build alike lets an update install
    // straight over the old app from here on.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.example.checkboxticker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // BoxLook is plain Kotlin and is checked on every build.
    testImplementation("junit:junit:4.13.2")
}

// Name each test in the build log, so a green build shows what was checked.
tasks.withType<Test>().configureEach {
    testLogging { events("passed", "skipped", "failed") }
}
