plugins {
    alias(libs.plugins.samfran.android.feature)
}

android { namespace = "com.rudnam.samfran.feature.bus" }

dependencies {
    implementation(libs.maps.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
