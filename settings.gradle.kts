pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UltimateMaps"
include(":core-voice")
include(":app",":core-geo", ":core-data", ":core-net", ":core-regions", ":core-map", ":core-search", ":core-routing", ":core-nav", ":core-fuel", ":core-cameras", ":core-chargers", ":core-routes",":core-transit", ":native-comaps")
include(":core-zbe") // low-emission zones (OSM polygons, route check)
