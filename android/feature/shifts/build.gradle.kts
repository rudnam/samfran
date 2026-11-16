plugins {
    alias(libs.plugins.samfran.android.feature)
}

android { namespace = "com.rudnam.samfran.feature.shifts" }

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
