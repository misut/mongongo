import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        jvm()

        commonMain.dependencies {
        }

        commonTest.dependencies {
        }

        jvmMain.dependencies {
        }

        jvmTest.dependencies {}
    }
}
