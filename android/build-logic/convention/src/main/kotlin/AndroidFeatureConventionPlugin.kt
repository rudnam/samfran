import com.rudnam.samfran.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A feature: its screens, their ViewModels and navigation, on the shared design system and data. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("samfran.android.library.compose")
        pluginManager.apply("samfran.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:model"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", libs.findLibrary("androidx-compose-material3").get())
            add("implementation", libs.findLibrary("androidx-hilt-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
            add("testImplementation", project(":core:testing"))
        }
    }
}
