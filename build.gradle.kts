import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

group = "com.hereliesaz.morphont"
// Same source as the installers and the Android fallback: version.properties (major.minor.patch).
version = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.let { p -> listOf("versionMajor", "versionMinor", "versionPatch").joinToString(".") { p.getProperty(it, "0").trim() } }

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
        // Robolectric (androidHostTest) needs the merged resources and manifest.
        withHostTest { isIncludeAndroidResources = true }
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
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
                // Version = a convey commit's short SHA; every push to convey main publishes all
                // its variants (android, desktop, wasmJs) to GitHub Packages through HereLiesAz/workflows.
                implementation("compose.conveyance:convey:68ca8d5")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
                implementation("com.juul.indexeddb:core:0.12.0")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
        val androidHostTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Android's framework classes on the JVM, for the project-folder storage tests.
                implementation("junit:junit:4.13.2")
                implementation("org.robolectric:robolectric:4.14.1")
                implementation("androidx.test:core:1.6.1")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.13.0")
                // Play's in-app update API: whether a Play-installed copy has an update waiting.
                implementation("com.google.android.play:app-update-ktx:2.1.0")
            }
        }
    }
}

// The desktop build's full version (major.minor.patch.build). The central release build exports the
// canonical one as RELEASE_VERSION (the same number its installer files are named with); it is built
// from the commit *before* the version bump, so version.properties there is one release behind and
// only serves local builds.
val desktopFullVersion: String = (providers.environmentVariable("RELEASE_VERSION").orNull
    ?: providers.gradleProperty("releaseVersion").orNull)
    ?.trim()?.takeIf { Regex("""\d+\.\d+\.\d+\.\d+""").matches(it) }
    ?: Properties().apply {
        rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.let { p -> listOf("versionMajor", "versionMinor", "versionPatch", "versionBuild").map { p.getProperty(it, "0").trim().toIntOrNull() ?: 0 } }
        .joinToString(".")

// Installer version: jpackage takes major.minor.patch.
val desktopVersion: String = desktopFullVersion.split(".").take(3).joinToString(".")

// The desktop app's own version, for the update check: the full version its installers are named with.
val generateDesktopVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/morphontVersion/kotlin")
    val version = desktopFullVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/hereliesaz/morphont/MorphontVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.hereliesaz.morphont\n\n/** Generated from version.properties. */\ninternal const val DESKTOP_VERSION = \"$version\"\n")
    }
}
kotlin.sourceSets.getByName("desktopMain").kotlin.srcDir(generateDesktopVersion)

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
