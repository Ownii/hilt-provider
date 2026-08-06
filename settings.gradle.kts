pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "hilt-provider"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        google()
        mavenCentral()
    }
}

include(":annotations")
include(":processor")
include(":sample")
include(":sample-android:feature")
include(":sample-android:app")
