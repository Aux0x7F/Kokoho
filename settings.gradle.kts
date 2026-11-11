pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// Kokoho enforces a strict FOSS-only, zero-GMS build: no full flavor, no Mapbox SDK, no Google ARCore.
val fossOnly = true
(gradle as ExtensionAware).extra["fossOnly"] = fossOnly

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kokoho"
include(":app")
