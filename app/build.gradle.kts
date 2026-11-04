plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.velkyra.yarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.velkyra.yarvis"
        minSdk = 31
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
        versionName = "2.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // La clave de IA llega desde el secreto GEMINI_API_KEY de GitHub (nunca se guarda en el código)
        buildConfigField("String", "GEMINI_KEY", "\"" + (System.getenv("GEMINI_API_KEY") ?: "").trim() + "\"")
    }

    signingConfigs {
        create("velkyra") {
            storeFile = file("yarvis.keystore")
            storePassword = "velkyra2026"
            keyAlias = "yarvis"
            keyPassword = "velkyra2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("velkyra")
        }
        debug {
            signingConfig = signingConfigs.getByName("velkyra")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Reconocimiento de voz sin internet para la palabra "Yarvis"
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
