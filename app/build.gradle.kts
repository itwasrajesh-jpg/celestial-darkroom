plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val buildNumber: Int = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.celestial.latent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.celestial.latent"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file(requireNotNull(System.getenv("KEYSTORE_PATH")))
            storePassword = requireNotNull(System.getenv("KEYSTORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("KEY_PASSWORD"))
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
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":engine:spektra-core"))
    implementation(project(":lib:libraw"))
    implementation("com.github.wendykierp:JTransforms:3.1")
    // Runs the depth model on the phone (step 50a). 1.24.x: the same generation the model was verified on.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
}
