// Gradle settings for the Android client (ADR 0009).
//
// This project is deliberately NOT part of the root Maven reactor:
// Android builds with AGP/Gradle, Maven has no Android packaging
// lifecycle. It is a sibling of `front/` — same shape, same reason
// (a different toolchain owns its own build), same CI independence.
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ticketapp-android"
include(":app")