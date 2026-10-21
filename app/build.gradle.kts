plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.korenkrita.simulsub"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.korenkrita.simulsub"
        minSdk = 29 // AudioPlaybackCapture
        targetSdk = 35
        versionCode = 29
        versionName = "1.0.9"
        ndk { abiFilters += "arm64-v8a" }
    }

    // the speech models in assets/models (setup.sh): stored, so sherpa-onnx reads them from the APK without inflating
    androidResources { noCompress += "onnx" }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // The sherpa-onnx AAR also ships its C and C++ API libraries; the Kotlin API only loads libsherpa-onnx-jni.so, which links none of their symbols.
    // useLegacyPackaging: .so files are stored compressed in the APK (release APK 13.6 MB at d83a627) and extracted
    // on install, so the installed footprint is larger (APK + extracted libraries) and the first install is slower.
    packaging {
        jniLibs {
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Jev and online MT: a Call can be cancelled before it runs, so stop never lets a request go out afterwards
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android but stubbed out in local unit tests; the reference implementation stands in
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
