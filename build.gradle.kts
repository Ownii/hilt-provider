plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "de.mafo.hilt"
    version = "0.1.0-SNAPSHOT"
}
