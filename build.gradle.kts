import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

group   = "dev.fakery"
version = "0.1.0"

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate()

    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    macosX64()
    macosArm64()

    linuxX64()
    mingwX64()

    sourceSets {

        // ── Common ───────────────────────────────────────────────────────────
        commonMain.dependencies {
            implementation(libs.ktor.server.core)       // Application, intercept, respond
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)     // JSON parsing
            implementation(libs.atomicfu)               // Thread-safe counters + atomic refs
            implementation(libs.kotlinx.io)             // Multiplatform file I/O
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        // ── JVM + Android shared ─────────────────────────────────────────────
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.server.cio)
            }
        }

        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // ── Native ───────────────────────────────────────────────────────────
        nativeMain.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.kotlinx.io)             // File I/O on native
        }
    }
}

android {
    namespace  = "dev.fakery"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
