plugins {
    id("com.android.application")
    // Compose compiler plugin. Kotlin itself is built into AGP 9.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // IMPORTANT: this is the app's package name. Firebase (Phase 2) is tied to it.
    namespace = "com.maychat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maychat.app"
        minSdk = 26        // Android 8.0 and newer
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The Compose BOM picks matching versions for all Compose libraries below.
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
