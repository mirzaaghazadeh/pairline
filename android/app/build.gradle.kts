plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Shared protocol test vectors (docs/PROTOCOL.md). Unit tests must reproduce every value.
val protocolVectors: File = file("../../protocol/test-vectors/v0.json")
val remoteProtocolVectors: File = file("../../protocol/test-vectors/v1-remote.json")

android {
    namespace = "app.pairline.android"
    // AndroidX needs compileSdk 37.1 (Compose BOM 2026.11). This only sets the APIs we compile against;
    // targetSdk (runtime behavior) and minSdk (supported phones) are separate.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        // Final since 1.0.0 (D-037). Changing it would break updates.
        applicationId = "app.pairline.android"
        // Android 9 and later (D-038; was Android 11).
        minSdk = 28
        targetSdk = 36
        // A release tag must be android-v<versionName>; the release workflow checks this.
        // Raise versionCode for every release.
        versionCode = 26
        versionName = "1.9.9"

        externalNativeBuild {
            cmake {
                // Static C++ runtime: the library is self-contained.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    androidResources {
        // The app ships in English, Persian, Arabic and Turkish (D-019). Dropping the other languages that
        // libraries bring along keeps every screen in one language.
        localeFilters += listOf("en", "fa", "ar", "tr")
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
        buildConfig = true
        // The privileged service's interface (privileged/IPrivilegedService.aidl, D-046).
        aidl = true
        // BoringSSL comes as an NDK prefab package (D-054).
        prefab = true
    }

    // The SPAKE2 step of pairing with Wireless debugging (privileged/adb, D-054), with BoringSSL's implementation.
    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests.all {
            it.systemProperty("pairline.vectors", protocolVectors.absolutePath)
            it.inputs.file(protocolVectors).withPropertyName("protocolVectors")
            it.systemProperty("pairline.vectors.remote", remoteProtocolVectors.absolutePath)
            it.inputs.file(remoteProtocolVectors).withPropertyName("remoteProtocolVectors")
        }
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.boringssl)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
