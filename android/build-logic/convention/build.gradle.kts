plugins {
    `kotlin-dsl`
}

group = "com.rudnam.samfran.buildlogic"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "samfran.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "samfran.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "samfran.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "samfran.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidRoom") {
            id = "samfran.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("hilt") {
            id = "samfran.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "samfran.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
