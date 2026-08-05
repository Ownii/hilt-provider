plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // `SingletonComponent` & friends are part of the public annotation API.
    api(libs.hilt.core)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
