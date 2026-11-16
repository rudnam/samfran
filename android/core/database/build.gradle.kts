// The phone's copy of what the server last said (Room), so every screen opens at once, offline too.
plugins {
    alias(libs.plugins.samfran.android.library)
    alias(libs.plugins.samfran.android.room)
    alias(libs.plugins.samfran.hilt)
}

android { namespace = "com.rudnam.samfran.core.database" }

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
