plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "nl.example.whatsappautoreply"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.example.whatsappautoreply"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}