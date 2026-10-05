// Root build file: only declares plugin VERSIONS. Nothing is applied here.
//
// Version set (checked against the official AGP 9.2.0 release notes):
//   Android Gradle Plugin 9.2.0  -> needs Gradle 9.4.1 or newer
//   Kotlin 2.3.10                -> AGP 9 has built-in Kotlin support
//
// AGP 9 compiles Kotlin by itself, so the app module does NOT apply
// "org.jetbrains.kotlin.android". It is listed here (apply false) only to pin
// the Kotlin version, so it always matches the Compose compiler plugin below.
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
