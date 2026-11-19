plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Постоянный ключ подписи: APK с одним ключом ставятся поверх друг друга как обновления.
// Ключ лежит в keystore/avrora-klav.jks в корне репозитория.
val keystoreFile = rootProject.file("keystore/avrora-klav.jks")
val keyPass = "avrora-klav-key"

android {
    namespace = "com.avrora.klav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.avrora.klav"
        minSdk = 24
        targetSdk = 34
        // При каждом обновлении увеличивай versionCode, иначе Android не поставит APK поверх.
        versionCode = 13
        versionName = "0.12.1"
    }

    signingConfigs {
        create("avrora") {
            storeFile = keystoreFile
            storePassword = keyPass
            keyAlias = "avrora"
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("avrora")
        }
        debug {
            signingConfig = signingConfigs.getByName("avrora")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
