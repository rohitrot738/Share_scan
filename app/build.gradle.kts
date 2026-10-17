plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// The release workflow passes these; local and debug builds fall back to the defaults.
val releaseBuild = System.getenv("SHARESCAN_VERSION_CODE")?.toIntOrNull()
val signingPassword = System.getenv("SHARESCAN_KEY_PASSWORD")

android {
    namespace = "com.rohit.share_scan"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rohit.share_scan"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseBuild ?: 2
        versionName = if (releaseBuild != null) "1.2.$releaseBuild" else "1.1.0"
    }
    signingConfigs {
        // Permanent key so every release installs over the previous one. The keystore is encrypted;
        // its password lives only in the SHARESCAN_KEY_PASSWORD repository secret.
        create("release") {
            storeFile = file("sharescan-release.p12")
            storeType = "pkcs12"
            storePassword = signingPassword
            keyAlias = "sharescan"
            keyPassword = signingPassword
        }
    }
    buildTypes {
        release {
            if (signingPassword != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for AppUpdateTest; the android.jar used by local unit tests only has stubs.
    testImplementation("org.json:json:20240303")
}
