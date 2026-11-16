plugins {
    alias(libs.plugins.samfran.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.samfran.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.rudnam.samfran"

    defaultConfig {
        // Permanent: the same package as the Capacitor build, so this installs as an update.
        applicationId = "com.rudnam.samfran"
        // Every release needs a higher versionCode: the app offers a build with a higher one as
        // an update. build.sh sets it from version.sh (one above the published APK); the number
        // here is the floor. 1–9 were the Capacitor build; 10 is the first native one.
        versionCode = (System.getenv("SAMFRAN_VERSION_CODE") ?: "10").toInt()
        versionName = System.getenv("SAMFRAN_VERSION_NAME") ?: "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The Google Maps key for the map of the bus stop (README.md, "Google Maps key"):
        // samfran.mapsKey in ~/.gradle/gradle.properties, or SAMFRAN_MAPS_KEY. It isn't in the
        // repository. Without it the stop's map is Google's web map instead (feature/bus StopMap).
        manifestPlaceholders["mapsKey"] = providers.gradleProperty("samfran.mapsKey")
            .orElse(providers.environmentVariable("SAMFRAN_MAPS_KEY")).getOrElse("")
    }

    androidResources {
        // English and Japanese, nothing else: Android's per-app language lists only these.
        generateLocaleConfig = true
    }

    // Signed with keystore/samfran.jks (git-ignored, see README.md); build.sh passes its
    // passwords in from keystore/keystore.properties. Without them, release builds come out unsigned.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/samfran.jks")
            keyAlias = "samfran"
            storePassword = System.getenv("SAMFRAN_KEYSTORE_PASSWORD")
            keyPassword = System.getenv("SAMFRAN_KEY_PASSWORD")
        }
    }

    buildTypes {
        // A debug build installs beside the real app, as "SamFran Preview" (src/debug), so it
        // can be tried without uninstalling anything. It talks to the same server.
        debug {
            applicationIdSuffix = ".preview"
        }
        release {
            if (System.getenv("SAMFRAN_KEYSTORE_PASSWORD") != null) signingConfig = signingConfigs.getByName("release")
            // R8: shrinks, optimizes and obfuscates. The libraries bring their own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // What CI builds for a pull request: the release build (a debuggable Compose app is
        // several times slower), installed beside the real app as "SamFran Preview", signed
        // with the debug key.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The preview's name and the rest of src/debug's resources.
    sourceSets.getByName("preview").res.srcDir("src/debug/res")

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":feature:signin"))
    implementation(project(":feature:food"))
    implementation(project(":feature:shifts"))
    implementation(project(":feature:bus"))
    implementation(project(":feature:us"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
