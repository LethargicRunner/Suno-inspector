plugins {
    id("com.android.application")
}

android {
    namespace = "com.lethargicrunner.sunoinspector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lethargicrunner.sunoinspector"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
}
