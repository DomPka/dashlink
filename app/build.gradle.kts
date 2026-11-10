import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dompka.dashlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dompka.dashlink"
        minSdk = 31          // Android 12: first release with dual-Wi-Fi (STA+STA) support
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // One fixed key in the repo, so every build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
