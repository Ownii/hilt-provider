plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

description = "KSP processor that wraps @Provide declarations in generated Hilt modules."

dependencies {
    implementation(project(":annotations"))
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        optIn.add("com.google.devtools.ksp.KspExperimental")
    }
}

// kotlin-compile-testing brings its own, older KSP implementation: the API resolves to our version
// but symbol-processing-aa-embeddable and friends would stay behind, so the tests would exercise a
// different KSP runtime than the one consumers get.
val kspVersion = libs.versions.ksp.get()

configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.devtools.ksp") {
            useVersion(kspVersion)
            because("tests must run against the KSP version the processor is built against")
        }
    }
}

// kotlin-compile-testing exposes the compiler plugin API in its DSL – test sources only.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}

tasks.test {
    useJUnitPlatform()
}
