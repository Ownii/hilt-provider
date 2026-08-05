plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.mafo.hilt.provider.sample.android.feature"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation(project(":annotations"))
    ksp(project(":processor"))

    implementation(libs.hilt.android)
    // Generates the @InstallIn aggregation metadata for the modules produced in this library.
    ksp(libs.hilt.android.compiler)
}
