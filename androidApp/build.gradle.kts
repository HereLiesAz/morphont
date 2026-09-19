plugins {
    id("com.android.application")
}

val morphontKeystoreFile = providers.environmentVariable("MORPHONT_KEYSTORE_FILE").orNull
val morphontKeystorePassword = providers.environmentVariable("MORPHONT_KEYSTORE_PASSWORD").orNull
val morphontKeyAlias = providers.environmentVariable("MORPHONT_KEY_ALIAS").orNull
val morphontKeyPassword = providers.environmentVariable("MORPHONT_KEY_PASSWORD").orNull

android {
    namespace = "com.hereliesaz.morphont"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.morphont"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.3"
    }

    signingConfigs {
        if (
            !morphontKeystoreFile.isNullOrBlank() &&
            !morphontKeystorePassword.isNullOrBlank() &&
            !morphontKeyAlias.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(morphontKeystoreFile)
                storePassword = morphontKeystorePassword
                keyAlias = morphontKeyAlias
                keyPassword = morphontKeyPassword ?: morphontKeystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
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
