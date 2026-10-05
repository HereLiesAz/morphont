import java.util.Properties

plugins {
    id("com.android.application")
}

// Version: the central Android releases (HereLiesAz/workflows android-play-release.yml and
// android-github-release.yml) pass -PversionCodeOverride/-PversionNameOverride (also
// -PversionCode/-PversionName). Local builds fall back to version.properties. Nothing here
// increments anything.
val versionProps = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(vararg names: String): String? = names.firstNotNullOfOrNull { (findProperty(it) as String?)?.takeIf(String::isNotBlank) }
val appVersionCode = prop("versionCodeOverride", "releaseVersionCode", "versionCode")?.toIntOrNull()
    ?: versionProps.getProperty("versionCode")?.trim()?.toIntOrNull()
    ?: 6
val appVersionName = prop("versionNameOverride", "releaseVersionName", "versionName")
    ?: versionProps.getProperty("versionName")?.trim()?.takeIf { it.isNotEmpty() }
    ?: listOf("versionMajor", "versionMinor", "versionPatch").joinToString(".") { versionProps.getProperty(it, "0").trim() }

// Signing: the central releases inject the upload key as android.injected.signing.* properties,
// which override this config. KEYSTORE_* (or MORPHONT_KEYSTORE_*) in the environment sign
// local release builds.
fun env(name: String): String? =
    (providers.environmentVariable(name).orNull ?: providers.environmentVariable("MORPHONT_$name").orNull)
        ?.takeIf { it.isNotBlank() }
val keystoreFile = env("KEYSTORE_FILE")
val keystorePassword = env("KEYSTORE_PASSWORD")
val keyAlias = env("KEY_ALIAS")
val keyPassword = env("KEY_PASSWORD")

android {
    namespace = "com.hereliesaz.morphont"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.morphont"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystoreFile != null && keystorePassword != null && keyAlias != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword ?: keystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 shrinks the release and emits the mapping.txt Google Play publication requires.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":"))
}
