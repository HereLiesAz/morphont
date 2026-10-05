import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
            // GitHub Packages needs a token even for a public package. Local CI exports
            // GITHUB_ACTOR/GITHUB_TOKEN; the central HereLiesAz/workflows builders expose GH_TOKEN.
            username = System.getenv("GITHUB_ACTOR") ?: "HereLiesAz"
            password = System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
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

    // Desktop (Windows, macOS, Linux): Compose for Desktop on the JVM, packaged by
    // compose.desktop below.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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
                implementation(compose.components.resources)
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
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.13.0")
            }
        }
    }
}

// Version for the desktop installers, from the same version.properties the Android release uses.
val desktopVersion: String = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.let { p -> listOf("versionMajor", "versionMinor", "versionPatch").map { p.getProperty(it, "0").trim().toIntOrNull() ?: 0 } }
    .joinToString(".")

compose.desktop {
    application {
        mainClass = "com.hereliesaz.morphont.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Morphont"
            packageVersion = desktopVersion
            description = "Variable-font glyph editor: three drawings per axis, the rest is arithmetic."
            vendor = "HereLiesAz"
            macOS {
                bundleID = "com.hereliesaz.morphont"
                iconFile.set(rootProject.file("branding/desktop/morphont.icns"))
                // macOS rejects a 0.x bundle version; the DMG carries 1.x of the same minor.patch.
                packageVersion = desktopVersion.split(".").let { v -> listOf(maxOf(1, v[0].toInt()), v[1], v[2]).joinToString(".") }
            }
            windows {
                iconFile.set(rootProject.file("branding/desktop/morphont.ico"))
                menuGroup = "Morphont"
                upgradeUuid = "7b0c3a8e-5f1d-4c62-9a4e-2d7e9c51b6f3"
                perUserInstall = true
            }
            linux {
                iconFile.set(rootProject.file("branding/desktop/morphont.png"))
                packageName = "morphont"
            }
        }
        buildTypes.release.proguard {
            // ProGuard on desktop buys little here and risks the serialization model; ship unshrunk.
            isEnabled.set(false)
        }
    }
}

compose.resources {
    packageOfResClass = "com.hereliesaz.morphont.resources"
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
