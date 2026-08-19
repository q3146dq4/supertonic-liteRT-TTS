import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.supertonic.tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.supertonic.tts"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
    implementation(project(":sdk"))

    // Java Interpreter runtime used only by the isolated GPU/NNAPI delegate
    // runner. Native CPU/XNNPACK remains on libLiteRt.so 2.1.5. No delegate
    // handles cross from this Java runtime into the native CPU runtime.
    implementation(files("libs/litert-api.aar"))
    implementation(files("libs/litert.aar"))
    implementation(files("libs/litert-gpu-api.aar"))
    implementation(files("libs/litert-gpu.aar"))

    // setup.sh downloads these directly through repo.maven.apache.org and keeps
    // them in the SupertonicLiteRT cache, avoiding Gradle's slow large-AAR fetch.
    implementation(files("libs/qnn-runtime.aar"))
    implementation(files("libs/qnn-litert-delegate.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
