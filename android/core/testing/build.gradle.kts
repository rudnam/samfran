// What tests share: fakes of the repositories, sample data, and a rule for coroutines.
plugins {
    alias(libs.plugins.samfran.android.library)
}

android { namespace = "com.rudnam.samfran.core.testing" }

dependencies {
    api(project(":core:model"))
    api(project(":core:data"))
    api(project(":core:datastore"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}
