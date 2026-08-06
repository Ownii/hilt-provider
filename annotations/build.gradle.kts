plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

description = "The @Provide annotation for top-level Hilt providers."

dependencies {
    // `SingletonComponent` & friends are part of the public annotation API.
    api(libs.hilt.core)
}

