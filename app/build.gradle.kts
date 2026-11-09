plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseStorePath = System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
val releaseSigningCount = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).count { !it.isNullOrBlank() }
check(releaseSigningCount == 0 || releaseSigningCount == 4) {
    "Release signing is partially configured: set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
        "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD together, or none of them."
}
val releaseSigningReady = releaseSigningCount == 4

android {
    namespace = "com.example.minimalapk"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.example.minimalapk"
        minSdk { version = release(24) }
        targetSdk { version = release(37) }
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                // MNN's prebuilt libraries (and LiteRT's) use the shared C++ runtime.
                arguments.add("-DANDROID_STL=c++_shared")
                arguments.add("-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF")
            }
        }
    }

    ndkVersion = "30.0.16248370"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = if (releaseSigningReady) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // The Qualcomm dispatch library dlopens libQnnHtp/... by name, so the NPU runtime
            // libraries must be extracted to nativeLibraryDir instead of loaded from the APK.
            useLegacyPackaging = true
            // qnn-runtime ships every HTP/DSP generation. Keep only the HTP v81 (SM8850) pair
            // plus the JIT prepare library. Adding another SoC means keeping its HTP
            // Skel/Stub pair here and relaxing the SM8850 gate in PoseAnalyzer.
            excludes += listOf(
                "lib/arm64-v8a/libQnnDsp.so",
                "lib/arm64-v8a/libQnnDspV66Skel.so",
                "lib/arm64-v8a/libQnnDspV66Stub.so",
                "lib/arm64-v8a/libQnnGpu.so",
                "lib/arm64-v8a/libQnnHtpV68Skel.so",
                "lib/arm64-v8a/libQnnHtpV68Stub.so",
                "lib/arm64-v8a/libQnnHtpV69Skel.so",
                "lib/arm64-v8a/libQnnHtpV69Stub.so",
                "lib/arm64-v8a/libQnnHtpV73Skel.so",
                "lib/arm64-v8a/libQnnHtpV73Stub.so",
                "lib/arm64-v8a/libQnnHtpV75Skel.so",
                "lib/arm64-v8a/libQnnHtpV75Stub.so",
                "lib/arm64-v8a/libQnnHtpV79Skel.so",
                "lib/arm64-v8a/libQnnHtpV79Stub.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// LiteRT's Play model-delivery module pulls in Play asset delivery, Play Services and
// WorkManager. WorkManager 2.9.1 relies on Room 2.5.0, whose keep rule (`-keep class * extends
// androidx.room.RoomDatabase`, no members) lets R8 strip WorkDatabase_Impl's constructor; its
// androidx.startup initializer then crashes the app before the activity starts. This app only
// loads bundled assets, so drop that unused subtree.
configurations.configureEach {
    exclude(group = "com.google.android.play", module = "ai-delivery")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)

    implementation(libs.material.kolor)

    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.runtime.qualcomm)
    implementation(libs.qnn.runtime)

    testImplementation(libs.junit)
}
