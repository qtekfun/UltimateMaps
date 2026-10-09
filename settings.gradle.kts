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
include(":core-bikeshare") // bike-share stations (GBFS static) and optional live availability
include(":core-zbe") // low-emission zones (OSM polygons, route check)
include(":core-weather") // AEMET weather warnings (opt-in, user-supplied API key, national bundle filtered on the device)
include(":mapcore") // shared MapLibre + PMTiles map viewer (git submodule, AGPL-3.0; see docs/decisions.md)
project(":mapcore").projectDir = file("ultimate-mapcore/mapcore")

include(":gms") // Google Play Services (location, activity recognition): the optional `play` flavor only; see docs/decisions.md
