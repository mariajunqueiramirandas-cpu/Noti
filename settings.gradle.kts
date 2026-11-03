pluginManagement {
    repositories {
        // Content filters: Gradle asks only the repository that can have a module,
        // instead of probing every repository for every artifact.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's mirror of Maven Central first: Maven Central itself rate-limits (HTTP 429)
        // shared CI/cloud IPs. Maven Central stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.settings") version "9.5.0"
}

// R8 runs in its own JVM with its own heap instead of sharing the Gradle daemon's.
// Heap size: -Pnoti.r8.maxHeap=… (CI sizes it to the runner), default 8 GB.
android {
    execution {
        profiles {
            create("release") {
                r8 {
                    runInSeparateProcess = true
                    jvmOptions += listOf(
                        "-Xmx" + providers.gradleProperty("noti.r8.maxHeap").getOrElse("8g"),
                        "-XX:+UseParallelGC",
                    )
                }
            }
        }
        defaultProfile = "release"
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "Noti"
include(":app")
