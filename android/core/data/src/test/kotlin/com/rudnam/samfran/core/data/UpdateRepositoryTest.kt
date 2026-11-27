package com.rudnam.samfran.core.data

import android.content.Context
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.model.ChangelogEntry
import com.rudnam.samfran.core.network.AuthInterceptor
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.Server
import com.rudnam.samfran.core.testing.TestPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class UpdateRepositoryTest {
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val play = FakeAppUpdateManager(context)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }
    private lateinit var network: SamFranNetwork

    private val session = object : SessionStore {
        override val token: Flow<String?> = MutableStateFlow("tok")
        override fun currentToken() = "tok"
        override fun save(token: String) {}
        override fun clear() {}
        override fun keepGoogleVerifier(verifier: String) {}
        override fun takeGoogleVerifier(): String? = null
    }

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session) { LocaleListCompat.getEmptyLocaleList() }).build()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        network = SamFranNetwork(retrofit, json)
        @Suppress("DEPRECATION")
        Shadows.shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).apply {
            versionCode = 40
            longVersionCode = 40L
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun repo(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = ServerUpdateRepository(
        context = context,
        network = network,
        preferences = TestPreferences.repository(),
        server = Server(),
        play = PlayStoreUpdates(play),
        io = dispatcher,
    )

    @Test
    fun `direct APK check sends since=installed and attaches changelog`() = runTest(UnconfinedTestDispatcher()) {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"android":{"url":"/download/samfran.apk","size":123,"version":42,"version_name":"2.0.42","changelog":[
                    {"version":42,"version_name":"2.0.42","en":["Show what's new"],"ja":["変更内容を表示"]},
                    {"version":41,"version_name":"2.0.41","en":["Faster ride alarm"]},
                    {"version":40,"version_name":"2.0.40","en":["Should be filtered out"]}
                ]}}""",
            ).build(),
        )

        val update = repo(UnconfinedTestDispatcher(testScheduler)).check()
        assertNotNull(update)
        assertEquals(42, update!!.versionCode)
        assertEquals("2.0.42", update.name)
        assertEquals(2, update.changelog.size)
        assertEquals(listOf(42, 41), update.changelog.map { it.versionCode })
        assertEquals("/api/downloads?since=40", server.takeRequest().url.let { "${it.encodedPath}?${it.encodedQuery}" })
    }

    @Test
    fun `Play Store update enriches with server changelog up to availableVersionCode`() = runTest(UnconfinedTestDispatcher()) {
        Shadows.shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, "com.android.vending", "com.android.vending")
        play.setUpdateAvailable(42, AppUpdateType.IMMEDIATE)
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"android":{"url":"/download/samfran.apk","size":123,"version":43,"version_name":"2.0.43","changelog":[
                    {"version":43,"version_name":"2.0.43","en":["Not on Play yet"]},
                    {"version":42,"version_name":"2.0.42","en":["Play release note"]}
                ]}}""",
            ).build(),
        )

        val update = repo(UnconfinedTestDispatcher(testScheduler)).check()
        assertNotNull(update)
        assertEquals(42, update!!.versionCode)
        assertEquals(true, update.fromPlay)
        assertEquals(listOf("Play release note"), update.changelog.single().en)
    }

    @Test
    fun `Play Store update still succeeds when server is unreachable`() = runTest(UnconfinedTestDispatcher()) {
        Shadows.shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, "com.android.vending", "com.android.vending")
        play.setUpdateAvailable(42, AppUpdateType.IMMEDIATE)
        server.close()

        val update = repo(UnconfinedTestDispatcher(testScheduler)).check()
        assertNotNull(update)
        assertEquals(42, update!!.versionCode)
        assertEquals(emptyList<ChangelogEntry>(), update.changelog)
    }

    @Test
    fun `notesFor picks Japanese when available and falls back to English`() {
        val bilingual = ChangelogEntry(42, "2.0.42", en = listOf("English"), ja = listOf("日本語"))
        val englishOnly = ChangelogEntry(41, "2.0.41", en = listOf("Only English"))

        assertEquals(listOf("日本語"), bilingual.notesFor(japanese = true))
        assertEquals(listOf("English"), bilingual.notesFor(japanese = false))
        assertEquals(listOf("Only English"), englishOnly.notesFor(japanese = true))
    }
}
