pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tuya's standard repositories
        maven { url = uri("https://maven-other.tuya.com/repository/maven-releases/") }
        maven { url = uri("https://maven-other.tuya.com/repository/maven-commercial-releases/") }
        // Tuya's Snapshot repository (Fixes the modularCampAnno SNAPSHOT error)
        maven { url = uri("https://maven-other.tuya.com/repository/maven-snapshots/") }
        // Required for Tuya's background dependencies
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Clara Smart Home"
include(":app")
