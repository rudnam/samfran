// Signing in: email and password, a new account, a forgotten password, Google (in the phone's browser), and the demo.
plugins {
    alias(libs.plugins.samfran.android.feature)
}

android { namespace = "com.rudnam.samfran.feature.signin" }

dependencies {
    implementation(libs.androidx.browser)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
