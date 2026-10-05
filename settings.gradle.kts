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
        maven("https://maven.pkg.github.com/HereLiesAz/convey") {
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "HereLiesAz"
                password = System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
            }
        }
    }
}

rootProject.name = "morphont"
include(":androidApp")
