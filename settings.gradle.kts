// settings.gradle.kts

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
        // JitPack is rarely needed for plugins, so we keep it last and empty (or remove).
        // If you ever need a plugin from JitPack, add a proper content filter here.
        // maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // JitPack LAST and narrowly scoped to MPAndroidChart only
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.PhilJay") // MPAndroidChart
                // Add other JitPack-only groups here if you explicitly need them
            }
        }
    }
}

rootProject.name = "CardioScope"
include(":app")
