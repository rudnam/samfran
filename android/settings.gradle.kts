pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "SamFran"

include(":app")

// The native app (README.md, "Native app"). core:* is shared; each feature:* is a tab, or signing in.
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:datastore")
include(":core:database")
include(":core:data")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")
include(":feature:signin")
include(":feature:food")
include(":feature:shifts")
include(":feature:bus")
include(":feature:us")
