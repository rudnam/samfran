plugins {
    alias(libs.plugins.samfran.android.feature)
}

android { namespace = "com.rudnam.samfran.feature.bus" }

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
