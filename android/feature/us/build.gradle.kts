plugins {
    alias(libs.plugins.samfran.android.feature)
}

android { namespace = "com.rudnam.samfran.feature.us" }

dependencies {
    // Drawing an app's icon (LocksScreen's AppIcon).
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
