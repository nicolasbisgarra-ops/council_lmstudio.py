// Trazo — cuaderno matemático manuscrito, app Android nativa.
//
// `-Ptrazo.coreOnly=true` compila solo el motor puro Kotlin (:core) sin el Android
// Gradle Plugin. Sirve en entornos sin acceso a Google Maven / Android SDK.
val coreOnly = providers.gradleProperty("trazo.coreOnly").orNull == "true"

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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Trazo"
include(":core")
include(":ai")
if (coreOnly) {
    rootProject.buildFileName = "build-core.gradle.kts"
} else {
    include(":app")
}
