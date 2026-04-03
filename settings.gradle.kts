pluginManagement {
    plugins {
        kotlin("jvm") version "1.9.23"
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "flowbus-core"
