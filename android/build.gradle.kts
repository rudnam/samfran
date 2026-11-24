// Plugins every module may use, declared once here with their versions
// (gradle/libs.versions.toml) and applied in the modules that need them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.roborazzi) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Robolectric's Android jars, one per SDK the tests run on, downloaded by Gradle into one folder
// that every module's tests read offline (build-logic's Steady.kt says why). A new Robolectric
// wants new ones: its tests then fail, naming the jar; put that version in the catalog.
// One configuration each: in one, Gradle would keep only the newer version of the same module.
val robolectricJarsFor = listOf(
    libs.androidAll.api36, // @Config(sdk = [36])
    libs.androidAll.api37, // the rest: compileSdk
).mapIndexed { i, jar ->
    configurations.create("robolectricSdk$i") {
        isCanBeConsumed = false
        isTransitive = false
    }.also { dependencies.add(it.name, jar) }
}
tasks.register<Sync>("robolectricJars") {
    from(robolectricJarsFor)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}

// Android Lint over every module: ./gradlew lintNative, as CI runs it.
tasks.register("lintNative") {
    group = "verification"
    description = "Runs Android Lint on app, core:* and feature:*."
    // The modules themselves, not the core and feature folders; core:model is plain Kotlin, with no Android to lint.
    val native = subprojects.filter { it.path == ":app" || it.path.startsWith(":core:") || it.path.startsWith(":feature:") }
    dependsOn(native.filter { it.path != ":core:model" }.map { "${it.path}:lintDebug" })
}
