plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// The release workflow passes these; local and debug builds fall back to the defaults.
val releaseBuild = System.getenv("SHARESCAN_VERSION_CODE")?.toIntOrNull()
val signingPassword = System.getenv("SHARESCAN_KEY_PASSWORD")
// Firebase login config from the FIREBASE_* repository secrets (docs/LOGIN_SETUP.md). An unset one is "", and the
// app then builds and runs with login switched off, so CI stays green before the Firebase project exists.
val firebaseConfig = listOf(
    "FIREBASE_API_KEY", "FIREBASE_APP_ID", "FIREBASE_PROJECT_ID",
    "FIREBASE_WEB_CLIENT_ID", "FIREBASE_STORAGE_BUCKET", "FIREBASE_SENDER_ID",
).associateWith { System.getenv(it)?.trim().orEmpty() }

android {
    namespace = "com.rohit.share_scan"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rohit.share_scan"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseBuild ?: 2
        versionName = if (releaseBuild != null) "1.2.$releaseBuild" else "1.1.0"
        // Escaped into Java string literals, so a stray quote or backslash in a secret cannot break the build.
        firebaseConfig.forEach { (name, value) ->
            buildConfigField("String", name, "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
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
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
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
    // The swipe layout's cards slide aside to show their buttons (SwipeRevealLayout).
    implementation("androidx.customview:customview:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Live mode: scans through market hours in the background (LiveScanWorker).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Login (AccountManager.kt). No google-services plugin: Firebase is configured in code from BuildConfig.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    // The signed-in user's watchlist in the cloud (CloudWatchlist.kt).
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for AppUpdateTest; the android.jar used by local unit tests only has stubs.
    testImplementation("org.json:json:20240303")
}
