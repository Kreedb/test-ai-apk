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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
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
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
}
