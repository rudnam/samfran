plugins {
    alias(libs.plugins.samfran.android.feature)
}

android { namespace = "com.rudnam.samfran.feature.food" }

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
