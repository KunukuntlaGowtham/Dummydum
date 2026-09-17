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
    // On-device text recognition, model included - reads labels on screens that
    // publish no text of their own. Nothing is uploaded.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
