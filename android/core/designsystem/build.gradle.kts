// The app's look on Material 3: the website's colours and font, shapes, icons and shared components.
plugins {
    alias(libs.plugins.samfran.android.library.compose)
}

android { namespace = "com.rudnam.samfran.core.designsystem" }

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
}
