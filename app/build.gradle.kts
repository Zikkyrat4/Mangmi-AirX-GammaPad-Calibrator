plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.airx.calibrator"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.airx.calibrator"
        minSdk = 29
        targetSdk = 33
        versionCode = 4
        versionName = "0.4.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
