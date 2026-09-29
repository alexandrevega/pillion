plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kover)
}

// Gradle-generates the app version into composeApp (KMP library modules have no BuildConfig) from
// the same catalog entry androidApp's versionName reads — see AppInfo.android.kt.
val generatedVersionDir = layout.buildDirectory.dir("generated/version/kotlin")
val generateVersionInfo = tasks.register("generateVersionInfo") {
    val outputDir = generatedVersionDir
    val versionName = libs.versions.app.versionName.get()
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("app/pillion/core/VersionInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package app.pillion.core

            internal const val BUILD_VERSION_NAME = "$versionName"

            """.trimIndent(),
        )
    }
}

kotlin {
    android {
        namespace = "app.pillion.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        androidResources { enable = true }

        // Backs the JVM unit tests in commonTest (protocol codec, head-unit profiles/registry,
        // SemVer, controllers) that stub android.util.Log via the shared Logger.
        withHostTest { isReturnDefaultValues = true }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain {
            kotlin.srcDir(generateVersionInfo)
            dependencies {
                // BackHandler.android.kt delegates to androidx.activity.compose.BackHandler.
                implementation(libs.androidx.activity.compose)
            }
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
                    "app.pillion.resources.*",   // generated resource accessors
                    "*ComposableSingletons*",
                    "*ComposeApp*",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
    }
}
