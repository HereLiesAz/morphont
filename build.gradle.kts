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
    // HereLiesAz/convey isn't published to Maven Central -- JitPack builds straight from the
    // GitHub repo (see the convey dependency's own comment below for the pinned commit). This
    // project-level block, not settings.gradle.kts's centralized one, is what actually applies
    // here: a project that declares its own `repositories {}` shadows dependencyResolutionManagement
    // by default, so the repo has to be listed in both places to behave predictably either way.
    maven("https://jitpack.io")
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
                // Conveyance manifesto -- not published to Maven Central, so this resolves
                // through JitPack (see settings.gradle.kts) straight from a commit on that
                // repo. Pinned to a real commit rather than a branch/tag so this build stays
                // reproducible regardless of what happens on convey's own default branch
                // afterward; bump deliberately, not automatically.
                //
                // Must be 265cdde or later: earlier commits (including 11b4ca7, this
                // dependency's first pin) have no jitpack.yml, so JitPack's default build
                // dies on convey's androidTarget (no Android SDK on JitPack's runner)
                // before ever publishing the wasmJs target's Compose resources artifact --
                // the app compiles clean against those commits and then throws
                // MissingResourceException at runtime trying to load Azrienoch. See
                // HereLiesAz/convey#26.
                implementation("com.github.HereLiesAz.convey:convey:265cdde")
            }
        }
    }
}
