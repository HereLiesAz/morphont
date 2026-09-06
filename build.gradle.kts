import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.1.20"
    // 1.8.2, not 1.7.3: matches HereLiesAz/convey's own pin (see the dependency below) --
    // two different Compose Multiplatform versions on the same wasmJs classpath risk a
    // runtime ABI mismatch, not just a compile error.
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.hereliesaz.morphont"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
    // HereLiesAz/convey isn't published to Maven Central -- published here instead (see the
    // convey dependency's own comment below for why this, not JitPack, and for the pinned
    // commit). This project-level block, not settings.gradle.kts's centralized one, is what
    // actually applies here: a project that declares its own `repositories {}` shadows
    // dependencyResolutionManagement by default, so the repo has to be listed in both places
    // to behave predictably either way.
    maven("https://maven.pkg.github.com/HereLiesAz/convey") {
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "morphont"
        browser {
            commonWebpackConfig {
                outputFileName = "morphont.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
                // HereLiesAz/convey, the Compose Multiplatform implementation of the
                // Conveyance manifesto -- not published to Maven Central. Was resolved
                // through JitPack (see git history) straight from a commit on that repo;
                // switched to GitHub Packages (HereLiesAz/convey#31) because JitPack's
                // coordinate-relocation step rewrites Gradle Module Metadata's `files[].url`
                // for a classified artifact -- this module's wasmJs Compose-resources zip,
                // published as `convey-wasm-js-<version>-kotlin_resources.kotlin_resources.zip`
                // -- down to a generic `convey-wasm-js-<version>.zip` that doesn't exist.
                // Gradle trusts that metadata and never requests the real file, so a
                // JitPack-resolved build here compiled clean and then threw
                // MissingResourceException at runtime loading Azrienoch, with no build-time
                // signal anything was wrong -- confirmed directly against JitPack's own
                // server (the real classified file 200s; the .module-declared name 404s),
                // not a guess. GitHub Packages serves exactly what convey's own build
                // produces, with no relocation step to mangle it.
                //
                // Note the group: `compose.conveyance`, not `com.github.HereLiesAz.convey`
                // -- that JitPack-specific group only ever existed because JitPack relocates
                // whatever a project publishes into its own `com.github.<owner>.<repo>`
                // namespace. GitHub Packages publishes convey's own real Maven coordinates
                // verbatim (see convey's own build.gradle.kts). Pinned to a real commit's
                // short SHA (convey's own CI publishes under that on every push to its
                // main -- see HereLiesAz/convey's publish-packages.yml) rather than a
                // branch/tag so this build stays reproducible regardless of what happens on
                // convey's own default branch afterward; bump deliberately, not automatically.
                implementation("compose.conveyance:convey:5cd5334")
            }
        }
    }
}
