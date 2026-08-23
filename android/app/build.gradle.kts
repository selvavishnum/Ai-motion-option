plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Resolved once, and treated as absent when blank as well as when unset: the workflow always
// passes RELEASE_KEYSTORE_PATH, and an unset step output arrives as "" rather than not at all.
// A null check alone let that empty string through to file(""), which throws.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.aimotion.handsfree"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aimotion.handsfree"
        minSdk = 31 // Android 12+, per requirements
        // Play refuses new uploads below API 35, and rejects an app that targets an older
        // platform than the one it is tested against.
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        // The signing key lives entirely outside this repository, supplied through environment
        // variables that CI fills from GitHub Actions secrets. A keystore used to be committed
        // here, with its password in this file — a defensible trade for a personally sideloaded
        // app, and an unacceptable one for anything published: whoever holds the key can sign an
        // update that every installed copy accepts as genuine, and a key in a public repo is held
        // by everyone.
        //
        // Set RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS and
        // RELEASE_KEY_PASSWORD as repository secrets; the workflow decodes the keystore to a file
        // and passes its path as RELEASE_KEYSTORE_PATH. Without them a release build is unsigned
        // rather than signed with a key that isn't really yours — see the null check below.
        create("release") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only attached when a key was actually supplied. Leaving the config attached with no
            // storeFile fails the build outright, which would mean nobody could produce a local
            // release build without the private key — including to check that it compiles.
            signingConfig = signingConfigs.getByName("release").takeIf { releaseKeystorePath != null }
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

    // Play requires an app bundle, not an APK, and the bundle is what Play Console accepts —
    // `./gradlew bundleRelease` produces app/build/outputs/bundle/release/app-release.aab.
    bundle {
        language { enableSplit = false } // per-language splits break in-app language switching
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

    // Play requires 16 KB page-size support for apps targeting Android 15, which means native
    // libraries aligned for it — 0.10.14's .so files are not. This is the one dependency whose
    // upgrade needs on-device verification rather than just a green build: hand and face
    // detection accuracy come from it.
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

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
