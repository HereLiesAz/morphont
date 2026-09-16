plugins {
    id("com.android.application")
}

android {
    namespace = "com.hereliesaz.morphont"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.morphont"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":"))
}
