plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fortlaufende Versionsnummer aus GitHub Actions, damit Android jedes Release als Update erkennt
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.meineplaylists"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.meineplaylists"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"
    }

    signingConfigs {
        // Fester Schlüssel: Ohne ihn hätte jede APK eine andere Signatur und Updates würden scheitern.
        create("fixed") {
            storeFile = rootProject.file("keystore/release.p12")
            storePassword = "meineplaylists"
            keyAlias = "meineplaylists"
            keyPassword = "meineplaylists"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// Keine externen Bibliotheken – nur Android-Framework + Kotlin-Stdlib.
