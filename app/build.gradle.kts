plugins {
    id("com.android.application")
    // Compose compiler plugin. Kotlin itself is built into AGP 9.
    id("org.jetbrains.kotlin.plugin.compose")
    // Turns @Serializable data classes into JSON and back (used for Supabase data).
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Supabase connection settings. They are NOT written in the source code.
// GitHub Actions passes them in from GitHub Secrets as environment variables.
// For a local build, set the same two environment variables on your computer.
val supabaseUrl: String = (System.getenv("SUPABASE_URL") ?: "").trim()
val supabaseKey: String = (System.getenv("SUPABASE_KEY") ?: "").trim()

android {
    // IMPORTANT: this is the app's package name.
    namespace = "com.maychat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maychat.app"
        minSdk = 26        // Android 8.0 and newer
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
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
        buildConfig = true
    }
}

// supabase-kt is built on the Ktor network library and needs one Ktor "engine"
// added by the app. The engine must have the same version as the Ktor that
// supabase-kt brings in. This rule tells Gradle that all "io.ktor" libraries
// belong together, so it lifts every one of them to the same (highest) version.
abstract class KtorAlignmentRule : ComponentMetadataRule {
    override fun execute(context: ComponentMetadataContext) {
        context.details.run {
            if (id.group == "io.ktor") {
                belongsTo("io.ktor:ktor-virtual-platform:${id.version}")
            }
        }
    }
}

dependencies {
    components.all<KtorAlignmentRule>()

    // The Compose BOM picks matching versions for all Compose libraries below.
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Supabase (managed backend): accounts, database, realtime.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")

    // Ktor engine (supports WebSockets, which Realtime needs).
    // The version written here is only a minimum, see KtorAlignmentRule above.
    implementation("io.ktor:ktor-client-okhttp:3.0.3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
