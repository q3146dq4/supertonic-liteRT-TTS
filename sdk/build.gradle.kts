import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}


val speechCoreDir = providers.gradleProperty("SPEECH_CORE_DIR")
    .orElse("${project.rootDir}/speech-core")
    .get()

android {
    namespace = "audio.soniqo.speech"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSPEECH_CORE_DIR=$speechCoreDir",
                    "-DLITERT_DIR=${project.rootDir}/litert",
                )
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Keep native libraries extracted as real files. Qualcomm QnnDelegate uses
    // applicationInfo.nativeLibraryDir to locate its HTP runtime/skel libraries.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.8.2")

    // Compile against the same Java Interpreter/GPU APIs that the app packages.
    // Runtime AARs live in :app so the Android library module does not try to
    // embed local AARs inside another AAR.
    compileOnly(files("../app/libs/litert-api.aar"))
    compileOnly(files("../app/libs/litert.aar"))
    compileOnly(files("../app/libs/litert-gpu-api.aar"))
    compileOnly(files("../app/libs/litert-gpu.aar"))
    compileOnly(files("../app/libs/qnn-litert-delegate.aar"))
}
