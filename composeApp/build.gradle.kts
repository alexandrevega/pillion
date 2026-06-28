import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kover)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            // In-app ADB (wireless-debugging pairing + shell) for the dedicated-dash bootstrap.
            implementation(libs.libadb.android)
            implementation(libs.bouncycastle.pkix)
            implementation(libs.conscrypt.android)
            // SDL "Path B": USB/AOA full-motion H.264 to USB head units (Tracer etc.).
            implementation(libs.smartdevicelink.android)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.pillion.resources"
}

// Code coverage focuses on the unit-testable shared domain (protocol codec, head-unit profiles/registry,
// SemVer, controllers). Compose UI, Android-framework services and generated code are excluded because
// they require an instrumented device, not JVM unit tests — leaving them in would mask real coverage.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "app.pillion.ui.*",          // Compose screens
                    "app.pillion.android.*",     // Android services / framework glue
                    "app.pillion.server.*",      // Ktor dash server (device-bound)
                    "app.pillion.resources.*",   // generated resource accessors
                    "*ComposableSingletons*",
                    "*ComposeApp*",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
    }
}

// Release signing is read from a gitignored keystore.properties (local only). When it's absent
// (e.g. a fresh clone or CI), release builds are simply left unsigned so the project still builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    namespace = "app.pillion"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.pillion"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0-alpha"
    }
    // Exposes VERSION_NAME so AppInfo.VERSION reads the build's own version (not a hardcoded copy).
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // Stub android.util.Log etc. in JVM unit tests (the shared Logger maps to android.util.Log
            // on this target) so commonTest can exercise logging code paths without an emulator.
            isReturnDefaultValues = true
        }
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
