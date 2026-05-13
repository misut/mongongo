import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId)
    id(libs.plugins.kotlin.plugin.serialization.get().pluginId)
}

kotlin {
    jvm()
    macosArm64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.cryptography.random)
            implementation(libs.ktor.network)
            implementation(libs.ktor.network.tls)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
        }

        commonTest.dependencies {
            implementation(libs.ktor.network)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {}

        jvmTest.dependencies {
            implementation(libs.embed.mongo)
            implementation(libs.kotlin.reflect)
            implementation(libs.kotlin.test)
            implementation(libs.mongodb.driver.kotlin.sync)
        }
    }
}
