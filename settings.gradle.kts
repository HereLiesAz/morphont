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
        // HereLiesAz/convey isn't published to Maven Central -- published here instead
        // (see build.gradle.kts's dependency comment for why this, not JitPack, and for
        // the pinned commit). GitHub Packages requires authentication even to download a
        // public package; GITHUB_ACTOR/GITHUB_TOKEN are set automatically in this repo's own
        // CI (see .github/workflows/*.yml) and read from the environment for a local build.
        maven("https://maven.pkg.github.com/HereLiesAz/convey") {
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "morphont"
