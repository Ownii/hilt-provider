plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "de.mafo.hilt.provider.sample.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "de.mafo.hilt.provider.sample.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    // The @Provide declarations live here; only the Hilt root lives in this module.
    implementation(project(":sample-android:feature"))

    implementation(libs.androidx.activity)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
}
