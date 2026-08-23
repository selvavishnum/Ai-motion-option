plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Deliberately public — see the signingConfigs comment below. Named so that nobody has to
// wonder whether a bare string literal in a build file was an accident.
val COMMITTED_KEYSTORE_PASSWORD = "airsensor"

android {
    namespace = "com.aimotion.handsfree"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aimotion.handsfree"
        minSdk = 31 // Android 12+, per requirements
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // Release builds need a real (non-debug) signature — some OEM skins (ColorOS/Realme UI,
        // MIUI, ...) reject sideloaded apps signed with the auto-generated debug key.
        //
        // The keystore is committed, and its password is in this file on purpose. CI used to
        // generate a throwaway key per build, which meant every APK was signed differently and
        // Android refused to install one over another: updating required uninstalling first,
        // which wipes every saved gesture mapping. A stable key is what makes an update an
        // update.
        //
        // This is NOT a secret and must not be treated as one. Anyone with the repo can sign an
        // APK that Android will accept as an update to this app, so the trade is only reasonable
        // because this is a personally sideloaded app that is not distributed through any store.
        // Publishing it anywhere real means generating a private key, keeping it out of the repo,
        // and supplying it through the RELEASE_KEYSTORE_* environment variables below.
        create("release") {
            // Environment variables win when set, so a private key can be supplied by CI later
            // without touching this file.
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            } else {
                storeFile = file("airsensor-release.keystore")
                storePassword = COMMITTED_KEYSTORE_PASSWORD
                keyAlias = "airsensor"
                keyPassword = COMMITTED_KEYSTORE_PASSWORD
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // matches Kotlin 1.9.24 (see plugin version above)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Jetpack Compose, for the production-grade gesture-mapping component system
    // (ui/mapping/). BOM pins all Compose artifact versions together.
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
