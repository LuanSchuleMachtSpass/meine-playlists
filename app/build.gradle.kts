plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.meineplaylists"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.meineplaylists"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Privat-App: mit Debug-Key signiert, damit die APK direkt installierbar ist
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// Keine externen Bibliotheken – nur Android-Framework + Kotlin-Stdlib.
