plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.checkboxticker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.checkboxticker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
