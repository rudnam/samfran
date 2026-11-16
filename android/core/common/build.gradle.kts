// Small things every layer shares: coroutine dispatchers, the app-wide scope and the clock.
plugins {
    alias(libs.plugins.samfran.android.library)
    alias(libs.plugins.samfran.hilt)
}

android { namespace = "com.rudnam.samfran.core.common" }

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
