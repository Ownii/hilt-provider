import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt.android) apply false
}

allprojects {
    // JitPack builds a tag and passes the coordinates it will serve under via the environment
    // (GROUP is derived from the GitHub account, VERSION is the tag). Reading them here keeps the
    // published artefacts findable there, while a local build stays at the values below.
    group = providers.environmentVariable("GROUP").getOrElse("de.mafo.hilt")
    version = providers.environmentVariable("VERSION").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    // Only the two library modules apply maven-publish; the samples must never be published.
    plugins.withId("maven-publish") {
        extensions.configure<JavaPluginExtension> {
            // Sources for navigation in the IDE, javadoc because Maven Central requires the artefact.
            withSourcesJar()
            withJavadocJar()
        }
        extensions.configure<PublishingExtension> {
            publications.register("maven", MavenPublication::class.java) {
                from(components["java"])
                // The module names alone ("annotations", "processor") would be useless coordinates.
                artifactId = "hilt-provider-${project.name}"
                pom {
                    name.set(artifactId)
                    description.set(provider { project.description })
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
    }

    // JVM 17 is deliberate and load-bearing: the artefacts have to stay usable for Android and Hilt
    // consumers, even though JDK 25 builds them. Enforced here rather than per module, so a new
    // module cannot silently compile against the JDK default.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
