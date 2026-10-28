plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.nightknight.scale.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.nightknight.scale.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-prototype"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
