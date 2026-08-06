plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // `SingletonComponent` & friends are part of the public annotation API.
    api(libs.hilt.core)
}

