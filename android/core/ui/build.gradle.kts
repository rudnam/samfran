// Pieces of screen that know the app's models: dates in the app's language, stars, people's badges, the server's messages.
plugins {
    alias(libs.plugins.samfran.android.library.compose)
}

android { namespace = "com.rudnam.samfran.core.ui" }

dependencies {
    api(project(":core:model"))
    api(project(":core:designsystem"))
    api(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
    api(libs.androidx.appcompat)
    // Places' photos (AsyncImage); the app sets up the loader (SamFranApplication).
    api(libs.coil.compose)
    testImplementation(project(":core:testing"))
}
