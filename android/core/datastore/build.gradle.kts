// What the app keeps on the phone outside the database: the session, and the user's preferences.
plugins {
    alias(libs.plugins.samfran.android.library)
    alias(libs.plugins.samfran.hilt)
}

android { namespace = "com.rudnam.samfran.core.datastore" }

dependencies {
    implementation(project(":core:common"))
    api(project(":core:model"))
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
