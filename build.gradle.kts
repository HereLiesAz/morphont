import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.1.20"
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.hereliesaz.morphont"
version = "0.2.0"

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
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
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
                // One dependency, both platforms. Convey already publishes android + wasmJs
                // variants from the same KMP module; keeping it in commonMain prevents the
                // Android surface from drifting into a second design system.
                implementation("compose.conveyance:convey:5cd5334")
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
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.10.1")
            }
        }
    }
}

android {
    namespace = "com.hereliesaz.morphont"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hereliesaz.morphont"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = project.version.toString()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The one command that must stay green before either surface can claim parity.
tasks.register("parityCheck") {
    group = "verification"
    description = "Builds both the wasm/PWA and Android applications from the shared editor core."
    dependsOn("wasmJsBrowserDistribution", "assembleDebug")
}
