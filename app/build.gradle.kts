import groovy.json.JsonSlurper
import java.net.URI
import java.util.Base64

plugins {
    id("com.android.application")
    // Compose compiler plugin. Kotlin itself is built into AGP 9.
    id("org.jetbrains.kotlin.plugin.compose")
    // Turns @Serializable data classes into JSON and back (used for Supabase data).
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------
// Settings that are NOT written in the source code.
// GitHub Actions passes all repository secrets in as one JSON text in the
// environment variable ALL_SECRETS. For a local build you can instead set
// environment variables with the same names as the secrets.
// ---------------------------------------------------------------------
val allSecrets: Map<*, *> = try {
    (JsonSlurper().parseText(System.getenv("ALL_SECRETS") ?: "{}") as? Map<*, *>) ?: emptyMap<Any, Any>()
} catch (e: Exception) {
    emptyMap<Any, Any>()
}

fun secret(name: String): String =
    (System.getenv(name) ?: (allSecrets[name] as? String) ?: "").trim()

// ---------------------------------------------------------------------
// Font "Be Vietnam Pro" (free, Open Font License, made for Vietnamese).
// The four font files are fetched from Google's public font repository
// while the APK is being built and packed into the app, so nothing has to
// be uploaded by hand. If the download fails for any reason the build still
// succeeds and the app simply uses the phone's own font.
// ---------------------------------------------------------------------
val fontFolder = file("src/main/assets/fonts")
listOf("Regular", "Medium", "SemiBold", "Bold").forEach { weight ->
    val target = File(fontFolder, "BeVietnamPro-$weight.ttf")
    if (!target.exists()) {
        try {
            fontFolder.mkdirs()
            val connection = URI(
                "https://raw.githubusercontent.com/google/fonts/main/ofl/bevietnampro/BeVietnamPro-$weight.ttf",
            ).toURL().openConnection()
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            // Keep the file only if it really is a font: big enough, and
            // starting with the TrueType signature (00 01 00 00).
            val head = target.inputStream().use { it.readNBytes(4) }
            val isFont = target.length() > 20000 && head.size == 4 &&
                head[0].toInt() == 0 && head[1].toInt() == 1 && head[2].toInt() == 0 && head[3].toInt() == 0
            if (!isFont) {
                target.delete()
                println("MayChat: downloaded font $weight is not valid, using the system font.")
            }
        } catch (e: Exception) {
            target.delete()
            println("MayChat: could not download font $weight (${e.message}), using the system font.")
        }
    }
}

// ---------------------------------------------------------------------
// Stickers: pictures from Google's "Noto Emoji" (free to use, Apache 2.0
// licence), fetched while the APK is being built and packed into the app,
// like the font above. If a download fails the build still succeeds and
// the app shows that sticker as a large emoji instead.
// The list must match STICKERS in ui/chat/Extras.kt.
// ---------------------------------------------------------------------
val stickerFolder = file("src/main/assets/stickers")
val stickerCodes = listOf(
    "1f600", "1f602", "1f923", "1f60d", "1f970", "1f618", "1f60e", "1f914",
    "1f62d", "1f621", "1f631", "1f634", "1f97a", "1f644", "1f92d", "1f973",
    "1f60b", "1f92f", "1f975", "1f976", "1f44d", "1f44e", "1f44f", "1f64f",
    "1f4aa", "1f44c", "1f91d", "270c", "2764", "1f494", "1f495", "1f525",
    "2728", "1f389", "1f382", "1f339", "2615", "1f35c", "1f37b", "26bd",
    "1f3b5", "1f4af", "1f436", "1f431", "1f43c", "1f984", "1f308", "1f31f",
)
// When the first three stickers cannot be fetched from anywhere, the rest
// are not tried, so a build without network is not slowed down.
var stickerFailures = 0
var stickerSuccesses = 0
stickerCodes.forEach { code ->
    val target = File(stickerFolder, "emoji_u$code.png")
    if (!target.exists() && (stickerSuccesses > 0 || stickerFailures < 3)) {
        val addresses = listOf(
            "https://raw.githubusercontent.com/googlefonts/noto-emoji/main/png/512/emoji_u$code.png",
            "https://cdn.jsdelivr.net/gh/googlefonts/noto-emoji@main/png/512/emoji_u$code.png",
        )
        var done = false
        for (address in addresses) {
            if (done) break
            try {
                stickerFolder.mkdirs()
                val connection = URI(address).toURL().openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.getInputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                // Keep the file only if it really is a PNG picture.
                val head = target.inputStream().use { it.readNBytes(4) }
                done = target.length() > 2000 && head.size == 4 &&
                    head[0].toInt() == -119 && head[1].toInt() == 80 && head[2].toInt() == 78 && head[3].toInt() == 71
                if (!done) target.delete()
            } catch (e: Exception) {
                target.delete()
            }
        }
        if (done) {
            stickerSuccesses++
        } else {
            stickerFailures++
            println("MayChat: could not download sticker $code, the app will show it as an emoji.")
        }
    }
}

val supabaseUrl: String = secret("SUPABASE_URL")
val supabaseKey: String = secret("SUPABASE_KEY")

// Firebase (push notifications). The secret GOOGLE_SERVICES_JSON holds the
// whole content of google-services.json; the four values the app needs are
// picked out of it here. Without the secret the app builds without push.
val googleServicesText: String = secret("GOOGLE_SERVICES_JSON")
val googleServices: Map<*, *>? =
    if (googleServicesText.isBlank()) null
    else try {
        JsonSlurper().parseText(googleServicesText) as? Map<*, *>
    } catch (e: Exception) {
        throw GradleException("Secret GOOGLE_SERVICES_JSON is not valid JSON. Paste the whole content of google-services.json again.")
    }

val firebaseProjectInfo = googleServices?.get("project_info") as? Map<*, *>
val firebaseClient: Map<*, *>? = (googleServices?.get("client") as? List<*>)
    ?.mapNotNull { it as? Map<*, *> }
    ?.firstOrNull { client ->
        val info = client["client_info"] as? Map<*, *>
        val androidInfo = info?.get("android_client_info") as? Map<*, *>
        androidInfo?.get("package_name") == "com.maychat.app"
    }

if (googleServices != null && firebaseClient == null) {
    throw GradleException(
        "GOOGLE_SERVICES_JSON does not contain an Android app with package name com.maychat.app. " +
            "In Firebase, add an Android app with exactly that package name and download google-services.json again."
    )
}

val firebaseAppId: String =
    ((firebaseClient?.get("client_info") as? Map<*, *>)?.get("mobilesdk_app_id") as? String) ?: ""
val firebaseApiKey: String =
    (((firebaseClient?.get("api_key") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key") as? String) ?: ""
val firebaseProjectId: String = (firebaseProjectInfo?.get("project_id") as? String) ?: ""
val firebaseSenderId: String = (firebaseProjectInfo?.get("project_number") as? String) ?: ""

// Fixed signing key, so a new APK installs OVER the old one without
// uninstalling. The key file comes from the secret KEYSTORE_BASE64 and its
// password from KEYSTORE_PASSWORD. Without these two secrets the build
// signs with a throw-away key exactly as before.
val keystoreBase64: String = secret("KEYSTORE_BASE64")
val keystorePassword: String = secret("KEYSTORE_PASSWORD")
val hasFixedKey: Boolean = keystoreBase64.isNotBlank() && keystorePassword.isNotBlank()
val fixedKeystoreFile = layout.buildDirectory.file("signing/maychat.p12").get().asFile
if (hasFixedKey) {
    try {
        fixedKeystoreFile.parentFile.mkdirs()
        fixedKeystoreFile.writeBytes(Base64.getMimeDecoder().decode(keystoreBase64))
    } catch (e: Exception) {
        throw GradleException("Secret KEYSTORE_BASE64 is not valid. Paste the whole content of KEYSTORE_BASE64.txt again.")
    }
}

android {
    // IMPORTANT: this is the app's package name.
    namespace = "com.maychat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maychat.app"
        minSdk = 26        // Android 8.0 and newer
        targetSdk = 36
        versionCode = 63
        versionName = "0.35.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"$firebaseSenderId\"")

        // The call library contains native code for four processor types.
        // Keeping only the two used by real phones makes the APK much smaller.
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = fixedKeystoreFile
                storePassword = keystorePassword
                keyAlias = "maychat"
                keyPassword = keystorePassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        debug {
            if (hasFixedKey) signingConfig = signingConfigs.getByName("fixed")
        }
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

    // Firebase Cloud Messaging (push notifications). Free, no billing needed.
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-messaging")

    // WebRTC (voice calls): Google's WebRTC library, pre-built by Stream.
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    // Finds the person in the camera picture, on the phone itself (no
    // internet), for "Làm mờ nền" in video calls.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // Draws and reads QR codes (used for "add a friend by QR code").
    implementation("com.google.zxing:core:3.5.3")

    // CameraX: the live camera picture for scanning a QR code.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Farm game: serves the packaged game pages (assets/game) to the WebView.
    implementation("androidx.webkit:webkit:1.12.1")
}
