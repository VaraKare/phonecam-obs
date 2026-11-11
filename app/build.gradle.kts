import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key lives outside the repository (never commit it). tools/release.py --create-key
// makes one; back it up, because updates must be signed with the same key.
val signingFile = file(System.getenv("PHONECAM_SIGNING") ?: "${System.getProperty("user.home")}/.phonecam-obs/keystore.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

android {
    namespace = "dev.phonecam.obs"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.phonecam.obs"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.0-alpha.5"
    }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = false }
    testOptions { unitTests.isReturnDefaultValues = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    // QR decoding for Wi-Fi pairing (Apache-2.0, pure Java, no network or Google services).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
