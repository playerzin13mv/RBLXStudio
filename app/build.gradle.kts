plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.rblxstudio"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.rblxstudio"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.luaj:luaj-jse:3.0.1")
}