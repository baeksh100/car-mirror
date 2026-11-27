// Google's copy of Maven Central first: CI runners often get "429 Too Many Requests" from
// repo.maven.apache.org. Maven Central stays as the fallback.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { google(); maven(centralMirror); mavenCentral() }
}
rootProject.name = "MirrorDrive"
include(":app")
