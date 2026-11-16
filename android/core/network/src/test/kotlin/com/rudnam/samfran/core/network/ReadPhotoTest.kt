package com.rudnam.samfran.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Reading a photo starts a read, then asks after it until it's done (internal/shift/reads.go). */
class ReadPhotoTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var network: SamFranNetwork

    @Before
    fun setUp() {
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        network = SamFranNetwork(retrofit, json).apply { readPollMs = 1 }
    }

    @After
    fun tearDown() = server.close()

    private fun answer(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun waitsForTheRead() = runTest {
        answer(202, """{"id":"r1"}""")
        answer(202, """{"status":"reading"}""")
        answer(202, """{"status":"reading"}""")
        answer(200, """{"month":"2026-11","name_as_read":"オトル サマンタ","days":[]}""")

        val month = network.readPhoto(byteArrayOf(1, 2, 3))

        assertEquals("2026-11", month.month)
        assertEquals("オトル サマンタ", month.nameAsRead)
        assertEquals("/api/shifts/reads", server.takeRequest().url.encodedPath)
        repeat(3) { assertEquals("/api/shifts/reads/r1", server.takeRequest().url.encodedPath) }
    }

    @Test
    fun aFailedReadSaysWhy() = runTest {
        answer(202, """{"id":"r1"}""")
        answer(422, """{"error":"couldn't find X on the sheet; check the name in settings"}""")

        val e = assertThrows(ApiException::class.java) { kotlinx.coroutines.runBlocking { network.readPhoto(byteArrayOf(1)) } }

        assertEquals(422, e.status)
        assertEquals("couldn't find X on the sheet; check the name in settings", e.message)
    }

    @Test
    fun theReadersOwn502KeepsItsWords() = runTest {
        answer(202, """{"id":"r1"}""")
        answer(502, """{"error":"the photo couldn't be read just now; try again, or enter the shifts by hand"}""")

        val e = assertThrows(ApiException::class.java) { kotlinx.coroutines.runBlocking { network.readPhoto(byteArrayOf(1)) } }

        assertEquals(502, e.status)
        assertEquals(false, e.fromProxy) // the server's words, not "couldn't reach the server"
    }

    @Test
    fun aProxys502IsTheServerNotAnswering() = runTest {
        answer(202, """{"id":"r1"}""")
        answer(502, "<html>502 Bad Gateway</html>")

        val e = assertThrows(ApiException::class.java) { kotlinx.coroutines.runBlocking { network.readPhoto(byteArrayOf(1)) } }

        assertEquals(true, e.fromProxy)
    }
}
