// Repositories: what the screens ask for. They read the phone's copy and keep it in step with the server.
plugins {
    alias(libs.plugins.samfran.android.library)
    alias(libs.plugins.samfran.hilt)
}

android { namespace = "com.rudnam.samfran.core.data" }

dependencies {
    api(project(":core:model"))
    api(project(":core:datastore"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit.core)
    implementation(libs.kotlinx.serialization.json)
    // Updates of a copy installed from the Play Store, from Play (PlayStoreUpdates).
    implementation(libs.play.app.update)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit.core)
    testImplementation(libs.retrofit.kotlinx.serialization)
    testImplementation(libs.androidx.core.ktx)
}
