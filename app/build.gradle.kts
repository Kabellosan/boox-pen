plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kabellosan.penkeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kabellosan.penkeyboard"
        minSdk = 29
        // The Onyx SDK reaches hidden Android APIs; it needs targetSdk <= 29.
        targetSdk = 29
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Checked-in debug key, so every CI build has the same signature and
        // installs as an update instead of needing an uninstall first.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { pickFirsts.add("**/*.so") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.1") { exclude(group = "com.android.support") }
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.2") { exclude(group = "com.android.support") }
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.3") { exclude(group = "com.android.support") }
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")

    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
