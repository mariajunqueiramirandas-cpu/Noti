plugins {
    id("com.android.application")
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
            // Xiaomi Pad 7 only: one ABI = one native build.
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
            // No R8: the app has no dependencies, and the JNI bridge binds
            // app.noti.ink.NotiInk by name (RegisterNatives).
            isMinifyEnabled = false
            isShrinkResources = false
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
