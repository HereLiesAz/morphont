pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // HereLiesAz/convey isn't published to Maven Central -- JitPack builds straight from
        // the GitHub repo (see build.gradle.kts's dependency comment for the pinned commit).
        maven("https://jitpack.io")
    }
}

rootProject.name = "morphont"
