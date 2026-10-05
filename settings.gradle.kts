// Where Gradle looks for build plugins (Android Gradle Plugin, Kotlin, ...)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Where Gradle looks for app libraries (Compose, AndroidX, later Firebase, ...)
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MayChat"
include(":app")
