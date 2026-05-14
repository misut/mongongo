import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId)
    id(libs.plugins.kotlin.plugin.serialization.get().pluginId)
    `maven-publish`
    signing
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

val emptyJavadocJar by tasks.registering(Jar::class) { archiveClassifier.set("javadoc") }

publishing {
    repositories {
        val publishingUrl = providers.gradleProperty("mavenPublishingRepositoryUrl").orNull
        if (!publishingUrl.isNullOrBlank()) {
            maven {
                name = "MavenCentral"
                url = uri(publishingUrl)
                credentials {
                    username = providers.gradleProperty("mavenPublishingUsername").orNull
                    password = providers.gradleProperty("mavenPublishingPassword").orNull
                }
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        artifact(emptyJavadocJar)

        pom {
            name.set("mongongo-core")
            description.set(
                "A BSON-first MongoDB client for Kotlin Multiplatform and Kotlin/Native."
            )
            url.set("https://github.com/misut/mongongo")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                }
            }

            developers {
                developer {
                    id.set("misut")
                    name.set("misut")
                    url.set("https://github.com/misut")
                }
            }

            scm {
                connection.set("scm:git:https://github.com/misut/mongongo.git")
                developerConnection.set("scm:git:ssh://git@github.com/misut/mongongo.git")
                url.set("https://github.com/misut/mongongo")
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    val hasSigningKeys = !signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()

    if (hasSigningKeys) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }

    isRequired = gradle.taskGraph.allTasks.any { it is PublishToMavenRepository }
    sign(publishing.publications)
}
