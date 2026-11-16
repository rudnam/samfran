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
    testImplementation(project(":core:testing"))
}
