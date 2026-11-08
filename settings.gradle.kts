import org.gradle.api.artifacts.repositories.MavenArtifactRepository

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
    }
}

// Code on the Go (the on-device Android IDE, package com.itsaky.androidide) puts its offline
// Maven repository in front of the repositories above. That repository has some library
// versions as a .pom without the .jar, and Gradle only downloads a jar from the repository that
// supplied its .pom, so app dependencies can fail with "Could not find <x>.jar ...
// localMvnRepository". Build plugins (AGP, Kotlin) still resolve from it first; for app
// dependencies, move it behind google() and mavenCentral(). This runs after Code on the Go's
// own settingsEvaluated hook. Outside Code on the Go there is no such repository: no effect.
gradle.settingsEvaluated {
    val repos = dependencyResolutionManagement.repositories
    repos.filterIsInstance<MavenArtifactRepository>()
        .filter { repo ->
            val url = "${repo.url}"
            url.startsWith("file:") && "/com.itsaky.androidide/" in url
        }
        .forEach { repo ->
            repos.remove(repo)
            repos.add(repo) // appends at the end
        }
}

rootProject.name = "Wine Tracker"
include(":app", ":opencv-minimal", ":gliner-workflow")
