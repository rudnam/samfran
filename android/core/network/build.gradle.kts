// The server's API (README.md "API"): requests, answers, and signing them in as the app.
plugins {
    alias(libs.plugins.samfran.android.library)
    alias(libs.plugins.samfran.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The server the app talks to: samfran.server in gradle.properties, or
 * -Psamfran.server=https://… for another (a local server, say).
 */
val server: String = providers.gradleProperty("samfran.server").orNull ?: error("samfran.server is missing from gradle.properties")

android {
    namespace = "com.rudnam.samfran.core.network"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("String", "SERVER", "\"${server.trimEnd('/')}\"")
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    api(libs.okhttp)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.okhttp.mockwebserver)
}
