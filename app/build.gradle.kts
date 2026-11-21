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
        versionCode = 2
        versionName = "0.2.0"
    }

    // Clé de debug fixe (dans le dépôt) : chaque build GitHub est signé pareil,
    // donc la mise à jour s installe par-dessus l ancienne version sans la désinstaller.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
