plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Defaults match what the GitHub ubuntu-24.04 image has preinstalled, so CI downloads nothing.
val ndkVersionProp = providers.gradleProperty("noti.ndkVersion").orElse("27.3.13750724")
val cmakeVersionProp = providers.gradleProperty("noti.cmakeVersion").orElse("3.31.5")

android {
    namespace = "app.noti"
    compileSdk = 36
    ndkVersion = ndkVersionProp.get()

    defaultConfig {
        applicationId = "app.noti"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0-dev"

        ndk {
            // Modern 64-bit ARM only (engine/CMakeLists.txt rejects any other ABI).
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    // No -g: faster compile, nothing to strip.
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DNOTI_BUILD_TESTS=OFF",
                    "-DNOTI_BUILD_TOOLS=OFF",
                )
                targets += "noti_ink"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../engine/CMakeLists.txt")
            version = cmakeVersionProp.get()
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "../engine/android/kotlin"
            baselineProfiles.directories += "../engine/android/baseline-profile"
        }
    }

    buildTypes {
        release {
            // R8 on: Compose is much slower and far larger without it. The JNI bridge binds
            // app.noti.ink.NotiInk by name (RegisterNatives); proguard-rules.pro keeps it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned on purpose: CI signs with apksigner, so no key or password ever
            // enters Gradle's configuration cache (which CI caches).
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
        resValues = false
        aidl = false
        shaders = false
    }

    // lintVitalRelease is the slowest task of assembleRelease; lint is not a release gate here.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs.useLegacyPackaging = false  // stored uncompressed, page aligned: mmapped straight from the APK
    }
}

// Version from the environment, read at execution time: it changes every CI run, but it is not
// a configuration input, so the configuration cache still hits.
androidComponents {
    onVariants { variant ->
        val code = providers.environmentVariable("NOTI_VERSION_CODE").map(String::toInt)
        val name = providers.environmentVariable("NOTI_VERSION_NAME")
        variant.outputs.forEach { out ->
            out.versionCode.set(code.orElse(1))
            out.versionName.set(name.orElse("0.0.0-dev"))
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
}
