pluginManagement {
    repositories {
        // Google Maven 仓库
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
        // Shizuku / libsu 等依赖托管在 jitpack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "QAlbum"
include(":app")
