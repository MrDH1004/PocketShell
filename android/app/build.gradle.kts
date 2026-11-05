import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single version source (DESIGN.md §11.1): repo-root version.properties.
val versionProps = Properties().apply {
    rootProject.file("../version.properties").inputStream().use { load(it) }
}
val mptaVersionName: String = versionProps.getProperty("versionName")
    ?: error("version.properties: versionName missing")
val mptaVersionCode: Int = versionProps.getProperty("versionCode")?.trim()?.toIntOrNull()
    ?: error("version.properties: versionCode missing or not an integer")

android {
    namespace = "com.mpta.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketshell.app"
        minSdk = 26
        targetSdk = 35
        versionCode = mptaVersionCode
        versionName = mptaVersionName
    }

    buildTypes {
        release {
            // Update-in-place requires the same signing key as v0.1.0, which was signed with the default
            // debug keystore (~/.android/debug.keystore). Release reuses that signing config.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":terminal-view"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
