package com.rudnam.samfran

import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

// Two things that made CI's Android job fail now and then, for reasons outside the code.

/** Held by each KSP task while it runs; only one can hold it. */
abstract class OneKspAtATime : BuildService<BuildServiceParameters.None>

/**
 * KSP runs one task at a time. KSP 2 runs every module's processing inside the Gradle daemon,
 * with no isolation between tasks. When many ran at once (a change to core:designsystem
 * rebuilds ~900 tasks), one came out wrong: Hilt saw none of core:data's bindings, or a
 * library class "could not be resolved". Every other task still runs in parallel.
 */
internal fun Project.oneKspAtATime() {
    val lock = gradle.sharedServices.registerIfAbsent("oneKspAtATime", OneKspAtATime::class.java) {
        maxParallelUsages.set(1)
    }
    plugins.withId("com.google.devtools.ksp") {
        tasks.withType<KspAATask>().configureEach { usesService(lock) }
    }
}

/**
 * Robolectric's Android jars (android-all-instrumented, ~150 MB each) come from Gradle, not
 * from Robolectric itself. On its own Robolectric fetches them from Maven Central into ~/.m2
 * on every CI run, which CI doesn't keep, and Maven Central sometimes answers 429 Too Many
 * Requests, which failed whole test classes. Gradle retries, and CI caches what it downloads.
 * The root project's robolectricJars task gathers them (build.gradle.kts).
 */
internal fun Project.robolectricOffline() {
    val jars = rootProject.files(rootProject.tasks.named("robolectricJars"))
    tasks.withType<Test>().configureEach {
        jvmArgumentProviders.add(RobolectricJars(jars))
    }
}

/** Robolectric offline, reading its jars from one folder; the jars are the test's input. */
class RobolectricJars(@get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) val jars: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments() = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${jars.singleFile.absolutePath}",
    )
}
