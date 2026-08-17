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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.8.2")
}
