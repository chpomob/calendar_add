plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()

if (hasReleaseKeystore) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

// -PemulatorX86_64=true restricts the DEBUG build to x86_64 (emulator). It never affects release.
val emulatorX86_64Only = providers.gradleProperty("emulatorX86_64")
    .map { it.toBoolean() }
    .getOrElse(false)

android {
    namespace = "com.calendaradd"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.calendaradd"
        minSdk = 31
        targetSdk = 36
        versionCode = 14
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(requireNotNull(keystoreProperties.getProperty("storeFile")) {
                    "Missing storeFile in keystore.properties"
                })
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword")) {
                    "Missing storePassword in keystore.properties"
                }
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias")) {
                    "Missing keyAlias in keystore.properties"
                }
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword")) {
                    "Missing keyPassword in keystore.properties"
                }
            }
        }
    }

    // ABI filters are declared per build type only: a defaultConfig filter would be unioned
    // with these and defeat the x86_64-only debug build.
    buildTypes {
        debug {
            ndk {
                if (emulatorX86_64Only) {
                    abiFilters += "x86_64"
                } else {
                    abiFilters += listOf("arm64-v8a", "x86_64")
                }
            }
        }
        release {
            // The release artifact must stay arm64-only: LiteRT-LM ships no inference engine
            // (liblitertlm_jni.so, libLiteRt.so) for 32-bit ABIs, so a 32-bit device could install
            // the app but never run inference.
            ndk {
                abiFilters += "arm64-v8a"
            }
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)

    // LiteRT-LM (2026 - Multimodal Gemma 4)
    implementation(libs.litertlm.android)

    // ML Kit (OCR)
    implementation(libs.mlkit.text.recognition)

    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Testing
    testImplementation(libs.androidx.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.io.mockk)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}
