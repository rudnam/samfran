package com.rudnam.samfran.core.data

import androidx.core.os.LocaleListCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rudnam.samfran.core.database.SamFranDatabase
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.model.RestaurantFields
import com.rudnam.samfran.core.network.AuthInterceptor
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.Server
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.LocalDate

/** The repositories against a mock server and an in-memory database: what's asked, what's kept, offline and signed out. */
@RunWith(RobolectricTestRunner::class)
class OfflineFirstRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: SamFranDatabase
    private lateinit var network: SamFranNetwork
    private lateinit var documents: Documents
    private val session = object : SessionStore {
        val current = MutableStateFlow<String?>("tok")
        override val token: Flow<String?> = current
        override fun currentToken() = current.value
        override fun save(token: String) { current.value = token }
        override fun clear() { current.value = null }
        override fun keepGoogleVerifier(verifier: String) {}
        override fun takeGoogleVerifier(): String? = null
    }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SamFranDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session) { LocaleListCompat.forLanguageTags("ja") }).build()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        network = SamFranNetwork(retrofit, json)
        documents = Documents(db.documents(), json, Clock.systemUTC())
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun answer(code: Int = 200, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val list = """[
        {"id":12,"name":"Hanabi Grill","area":"Station East","cuisine":"Yakiniku","map_url":"","notes":"","added_by":"Sam","created_at":"2026-06-01T00:00:00Z",
         "visit_count":2,"last_visit":"2026-11-14","ratings":[{"name":"Fran","me":true,"stars":5}],"visit_notes":[{"visited_on":"2026-11-14","note":"harami"}],"new_field":1},
        {"id":15,"name":"Sushi Kiyo","area":"","cuisine":"","map_url":"","notes":"","added_by":"","created_at":"2026-06-01T00:00:00Z","visit_count":0,"last_visit":null,"ratings":[]}
    ]"""

    @Test
    fun `the list is asked for as the app, kept, and still there offline`() = runTest(UnconfinedTestDispatcher()) {
        val food = OfflineFirstFoodRepository(network, documents, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        answer(body = list)
        assertEquals(Outcome.Done(Unit), food.refresh())
        val request = server.takeRequest()
        assertEquals("/api/restaurants", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("android", request.headers["X-App-Client"])
        assertEquals("ja", request.headers["Accept-Language"])

        val kept = food.restaurants.first()!!
        assertEquals(listOf("Hanabi Grill", "Sushi Kiyo"), kept.map { it.name })
        assertEquals(LocalDate.of(2026, 11, 14), kept[0].lastVisit)
        assertEquals("harami", kept[0].visitNotes.single().note)

        server.close() // offline
        assertEquals(Outcome.Offline, food.refresh())
        assertEquals(2, food.restaurants.first()!!.size)
    }

    @Test
    fun `a change keeps the server's answer, in the list too`() = runTest(UnconfinedTestDispatcher()) {
        val food = OfflineFirstFoodRepository(network, documents, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        answer(body = list)
        food.refresh()
        server.takeRequest()
        answer(body = """{"id":15,"name":"Sushi Kiyo","visit_count":1,"last_visit":"2026-11-16","ratings":[],"visits":[{"id":3,"visited_on":"2026-11-16","note":"omakase","added_by":"Fran"}]}""")
        answer(body = list) // the list asked for again afterwards
        val r = food.setBeen(15, LocalDate.of(2026, 11, 16))
        assertTrue(r is Outcome.Done)
        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        assertEquals("/api/restaurants/15/been", sent.url.encodedPath)
        assertEquals("""{"visited_on":"2026-11-16"}""", sent.body?.utf8())
        assertEquals(LocalDate.of(2026, 11, 16), food.restaurant(15).first()?.lastVisit)
        assertEquals("omakase", food.restaurant(15).first()?.visits?.single()?.note)
    }

    @Test
    fun `a comment is sent trimmed and the place comes back with it`() = runTest(UnconfinedTestDispatcher()) {
        val food = OfflineFirstFoodRepository(network, documents, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        answer(body = list)
        food.refresh()
        server.takeRequest()
        answer(body = """{"id":15,"name":"Sushi Kiyo","visit_count":1,"last_visit":"2026-11-16","ratings":[],"comments":[{"id":4,"body":"So fresh","by":"Fran","me":true,"created_at":"2026-11-16T12:00:00Z"}]}""")
        val r = food.addComment(15, "  So fresh ")
        assertTrue(r is Outcome.Done)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/restaurants/15/comments", sent.url.encodedPath)
        assertEquals("""{"body":"So fresh"}""", sent.body?.utf8())
        val c = food.restaurant(15).first()!!.comments.single()
        assertEquals(listOf("So fresh", "Fran"), listOf(c.body, c.by))
        assertTrue(c.me)
        // The list keeps its summary: no comments in it.
        assertTrue(food.restaurants.first()!!.first { it.id == 15L }.comments.isEmpty())
    }

    @Test
    fun `a refusal says why, in the server's words`() = runTest(UnconfinedTestDispatcher()) {
        val food = OfflineFirstFoodRepository(network, documents, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        answer(400, """{"error":"a name must be 1 to 200 characters"}""")
        assertEquals(Outcome.Refused("a name must be 1 to 200 characters"), food.create(RestaurantFields(name = ""), null, 0))
    }

    @Test
    fun `a 401 ends the session`() = runTest(UnconfinedTestDispatcher()) {
        val bus = OfflineFirstBusRepository(network, documents, UnconfinedTestDispatcher(testScheduler))
        answer(401, """{"error":"session expired; sign in again"}""")
        assertEquals(Outcome.SignedOut, bus.refresh())
        assertNull(session.currentToken())
    }

    @Test
    fun `shifts keep the current month under its own name too, and the ends to ask at`() = runTest(UnconfinedTestDispatcher()) {
        val shifts = OfflineFirstShiftsRepository(network, documents, Server(), UnconfinedTestDispatcher(testScheduler))
        fun month(m: String, days: String) = """{"month":"$m","settings":{"person_name":"Sam","lengths":{"F":9},"time_zone":"Asia/Tokyo","reader":true},"days":[$days]}"""
        answer(body = month("2026-11", """{"date":"2026-11-30","code":"13F","kind":"work","label":"Late","start":"2026-11-30T13:00:00+09:00","end":"2026-11-30T22:00:00+09:00"}"""))
        answer(body = month("2026-10", ""))
        answer(body = month("2026-12", """{"date":"2026-12-01","code":"普","kind":"off","label":"Day off"}"""))
        val ends = shifts.upcomingShifts(java.time.Instant.parse("2026-11-16T00:00:00Z"))
        assertEquals(listOf(java.time.Instant.parse("2026-11-30T13:00:00Z")), (ends as Outcome.Done).value.map { it.end!!.toInstant() })
        assertEquals("Late", ends.value.single().label)
        assertEquals("Sam", shifts.month(java.time.YearMonth.of(2026, 11)).first()?.settings?.personName)
        assertEquals("22:00", shifts.month(null).first()?.days?.single()?.end?.let { "${it.hour}:%02d".format(it.minute) })
    }
}
