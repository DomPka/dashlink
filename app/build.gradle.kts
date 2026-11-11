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
        versionCode = 2
        versionName = "0.2"
        // Shown in the Diagnostics report, so a report says exactly which build it came from.
        resValue("string", "build_commit", (System.getenv("GITHUB_SHA") ?: "local build").take(12))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Every emulator test starts from a freshly installed app: no saved settings, no granted permissions.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
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

    testOptions {
        // One process per emulator test, so a crash in one cannot hide the others.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    // The fake camera (FakeCam) is shared by the JVM tests and the emulator tests.
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The app itself ships with no libraries. Everything below is compiled into tests only.
dependencies {
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestUtil("androidx.test:orchestrator:1.6.1")
}

// Fails the build if anything other than the Kotlin standard library would be packed into the APK.
tasks.register("checkNoRuntimeDependencies") {
    val runtime = configurations.named("debugRuntimeClasspath")
    doLast {
        val extra = runtime.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filterNot { it.group == "org.jetbrains.kotlin" || it.group == "org.jetbrains" }
            .map { "${it.group}:${it.name}:${it.version}" }
        check(extra.isEmpty()) { "The app must not have runtime dependencies, found: $extra" }
    }
}
