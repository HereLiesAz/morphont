import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("com.android.kotlin.multiplatform.library") version "9.3.2"
    id("org.jetbrains.compose") version "1.12.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.hereliesaz.morphont"
version = "0.4.3"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.github.com/HereLiesAz/convey") {
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

kotlin {
    android {
        namespace = "com.hereliesaz.morphont.shared"
        compileSdk = 37
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
        }
        withHostTest {}
    }

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
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("compose.conveyance:convey:6f467bb")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
                implementation("com.juul.indexeddb:core:0.12.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.13.0")
            }
        }
    }
}

tasks.register("parityCheck") {
    group = "verification"
    // Not currently invoked by any CI workflow (its former caller,
    // platform-parity.yml, was removed pending re-sync from HereLiesAz/workflows) --
    // run it manually until a workflow calls it again.
    description = "Builds Android and wasm and runs the shared test suite on both targets."
    dependsOn(
        "wasmJsBrowserDistribution",
        ":androidApp:assembleDebug",
        "testAndroidHostTest",
        "wasmJsBrowserTest",
    )
}
