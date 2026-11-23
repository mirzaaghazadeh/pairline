plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.pairline.android"
    // AndroidX now needs compileSdk 37. This only sets the APIs we compile against;
    // targetSdk (runtime behavior) and minSdk (supported phones) are separate.
    compileSdk = 37

    defaultConfig {
        // Provisional (D-007). Changing it after the first release breaks updates.
        applicationId = "app.pairline.android"
        minSdk = 30
        targetSdk = 36
        // A release tag must be android-v<versionName>; the release workflow checks this.
        // Raise versionCode for every release.
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Lets a debug build sit next to the release build on the same test phone.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Signed only by .github/workflows/android-release.yml after the build, never by Gradle,
            // so anyone can build the same unsigned APK without our keys (docs/RELEASING.md).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Don't embed Google's encrypted dependency report in the APK: users can't read it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
