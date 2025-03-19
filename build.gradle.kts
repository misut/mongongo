import com.diffplug.gradle.spotless.SpotlessExtension

allprojects {
    group = "mongongo"

    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.spotless)
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<SpotlessExtension> {
        kotlin {
            targetExclude("**/build/generated/**/*.kt")
            ktfmt().googleStyle().configure {
                it.setBlockIndent(4)
                it.setContinuationIndent(4)
                it.setRemoveUnusedImport(true)
            }
        }

        kotlinGradle {
            target("*.gradle.kts")
            ktfmt().googleStyle().configure {
                it.setBlockIndent(4)
                it.setContinuationIndent(4)
                it.setRemoveUnusedImport(true)
            }
        }
    }
}