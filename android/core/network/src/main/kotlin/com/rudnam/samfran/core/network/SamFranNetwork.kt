package com.rudnam.samfran.core.network

import com.rudnam.samfran.core.network.model.NetworkActivity
import com.rudnam.samfran.core.network.model.NetworkAppCode
import com.rudnam.samfran.core.network.model.NetworkAuthOptions
import com.rudnam.samfran.core.network.model.NetworkBusStops
import com.rudnam.samfran.core.network.model.NetworkBusStopInput
import com.rudnam.samfran.core.network.model.NetworkBuzz
import com.rudnam.samfran.core.network.model.NetworkCredentials
import com.rudnam.samfran.core.network.model.NetworkDemo
import com.rudnam.samfran.core.network.model.NetworkDownloads
import com.rudnam.samfran.core.network.model.NetworkEmail
import com.rudnam.samfran.core.network.model.NetworkError
import com.rudnam.samfran.core.network.model.NetworkFoundStop
import com.rudnam.samfran.core.network.model.NetworkInviteLink
import com.rudnam.samfran.core.network.model.NetworkLookup
import com.rudnam.samfran.core.network.model.NetworkLookupResult
import com.rudnam.samfran.core.network.model.NetworkMcpToken
import com.rudnam.samfran.core.network.model.NetworkMe
import com.rudnam.samfran.core.network.model.NetworkName
import com.rudnam.samfran.core.network.model.NetworkReadProgress
import com.rudnam.samfran.core.network.model.NetworkReadStarted
import com.rudnam.samfran.core.network.model.NetworkNewVisit
import com.rudnam.samfran.core.network.model.NetworkRestaurant
import com.rudnam.samfran.core.network.model.NetworkRestaurantInput
import com.rudnam.samfran.core.network.model.NetworkSaveShifts
import com.rudnam.samfran.core.network.model.NetworkShiftMonth
import com.rudnam.samfran.core.network.model.NetworkShiftSettings
import com.rudnam.samfran.core.network.model.NetworkShiftSettingsInput
import com.rudnam.samfran.core.network.model.NetworkSpace
import com.rudnam.samfran.core.network.model.NetworkStars
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The session has ended (a 401 on a signed-in request): the app goes back to signing in. */
class SignedOutException : java.io.IOException("Not signed in")

/**
 * The server said no; [message] is its words for the user (internal/httpx).
 * [fromProxy]: the answer had no words of the server's, so it came from the
 * CDN or proxy in front of it (a server that's down or restarting).
 */
class ApiException(val status: Int, override val message: String, val fromProxy: Boolean = false) : Exception(message)

/** README.md's API table: what the app uses. Paths as the website calls them (web/src/api.ts). */
internal interface SamFranApi {
    @GET("api/me") suspend fun me(): NetworkMe
    @GET("api/auth/options") suspend fun authOptions(): NetworkAuthOptions
    @POST("api/login") suspend fun login(@Body body: NetworkCredentials): NetworkMe
    @POST("api/signup") suspend fun signup(@Body body: NetworkCredentials): NetworkMe
    @POST("api/demo") suspend fun demo(@Body body: NetworkDemo): NetworkMe
    @POST("api/auth/app-token") suspend fun appToken(@Body body: NetworkAppCode): NetworkMe
    @POST("api/logout") suspend fun logout(): Response<Unit>
    @POST("api/verify-email") suspend fun resendConfirmation(): Response<Unit>
    @POST("api/password/forgot") suspend fun forgotPassword(@Body body: NetworkEmail): Response<Unit>
    @GET("api/downloads") suspend fun downloads(): NetworkDownloads

    @GET("api/space") suspend fun space(): NetworkSpace
    @PUT("api/space/me") suspend fun setMyName(@Body body: NetworkName): NetworkSpace
    @POST("api/space/invite-link") suspend fun createInviteLink(): NetworkInviteLink
    @DELETE("api/space/invite-link") suspend fun cancelInviteLink(): NetworkSpace
    @DELETE("api/space/invites/{email}") suspend fun uninvite(@Path("email") email: String): NetworkSpace

    @GET("api/restaurants") suspend fun restaurants(): List<NetworkRestaurant>
    @GET("api/restaurants/{id}") suspend fun restaurant(@Path("id") id: Long): NetworkRestaurant
    @POST("api/restaurants") suspend fun createRestaurant(@Body body: NetworkRestaurantInput): NetworkRestaurant
    @PATCH("api/restaurants/{id}") suspend fun updateRestaurant(@Path("id") id: Long, @Body body: NetworkRestaurantInput): NetworkRestaurant
    @DELETE("api/restaurants/{id}") suspend fun deleteRestaurant(@Path("id") id: Long): Response<Unit>
    @POST("api/restaurants/{id}/visits") suspend fun addVisit(@Path("id") id: Long, @Body body: NetworkNewVisit): NetworkRestaurant
    @DELETE("api/restaurants/{id}/visits/{visit}") suspend fun deleteVisit(@Path("id") id: Long, @Path("visit") visit: Long): NetworkRestaurant
    @PUT("api/restaurants/{id}/rating") suspend fun rate(@Path("id") id: Long, @Body body: NetworkStars): NetworkRestaurant
    @POST("api/restaurants/lookup") suspend fun lookup(@Body body: NetworkLookup): NetworkLookupResult
    @GET("api/restaurants/search") suspend fun searchPlaces(@Query("q") q: String, @Query("lat") lat: Double?, @Query("lng") lng: Double?): List<com.rudnam.samfran.core.network.model.NetworkFoundPlace>
    @GET("api/restaurants/suggest") suspend fun suggestPlaces(@Query("q") q: String, @Query("lat") lat: Double?, @Query("lng") lng: Double?, @Query("session") session: String): List<com.rudnam.samfran.core.network.model.NetworkFoundPlace>
    @GET("api/restaurants/place/{id}") suspend fun placeDetails(@Path("id") id: String, @Query("session") session: String): com.rudnam.samfran.core.network.model.NetworkFoundPlace

    @GET("api/shifts") suspend fun shifts(@Query("month") month: String? = null): NetworkShiftMonth
    @PUT("api/shifts/month") suspend fun saveShifts(@Body body: NetworkSaveShifts): NetworkShiftMonth
    @GET("api/shifts/settings") suspend fun shiftSettings(): NetworkShiftSettings
    @PUT("api/shifts/settings") suspend fun saveShiftSettings(@Body body: NetworkShiftSettingsInput): NetworkShiftSettings
    @POST("api/shifts/feed") suspend fun feedOn(): NetworkShiftSettings
    @DELETE("api/shifts/feed") suspend fun feedOff(): NetworkShiftSettings

    @Multipart
    @POST("api/shifts/reads")
    suspend fun startRead(@Part photo: MultipartBody.Part): NetworkReadStarted
    @GET("api/shifts/reads/{id}") suspend fun readStatus(@Path("id") id: String): Response<ResponseBody>

    @GET("api/bus/stops") suspend fun busStops(): NetworkBusStops
    @POST("api/bus/stops") suspend fun addBusStop(@Body body: NetworkBusStopInput): NetworkBusStops
    @PUT("api/bus/stops/{id}") suspend fun changeBusStop(@Path("id") id: Long, @Body body: NetworkBusStopInput): NetworkBusStops
    @DELETE("api/bus/stops/{id}") suspend fun deleteBusStop(@Path("id") id: Long): NetworkBusStops
    @POST("api/bus/stops/{id}/use") suspend fun useBusStop(@Path("id") id: Long): NetworkBusStops
    @PUT("api/bus/buzz") suspend fun setBuzz(@Body body: NetworkBuzz): NetworkBusStops
    @GET("api/bus/search") suspend fun searchStops(@Query("q") q: String, @Query("lat") lat: Double?, @Query("lng") lng: Double?): List<NetworkFoundStop>

    @GET("api/activity") suspend fun activity(@Query("bus") bus: Int? = null): List<NetworkActivity>

    @GET("api/mcp/tokens") suspend fun mcpTokens(): List<NetworkMcpToken>
    @DELETE("api/mcp/tokens/{id}") suspend fun deleteMcpToken(@Path("id") id: Long): Response<Unit>
}

/**
 * The API for the repositories. Every call fails with [ApiException] when
 * the server refuses (its message is for the user), an IOException when it
 * can't be reached, and [SignedOutException] when the session has ended.
 */
@Singleton
class SamFranNetwork @Inject constructor(retrofit: Retrofit, private val json: Json) {
    private val api = retrofit.create(SamFranApi::class.java)

    suspend fun me() = call { api.me() }
    suspend fun authOptions() = call { api.authOptions() }
    suspend fun login(email: String, password: String) = call { api.login(NetworkCredentials(email, password)) }
    suspend fun signup(email: String, password: String) = call { api.signup(NetworkCredentials(email, password)) }
    suspend fun startDemo(zone: String) = call { api.demo(NetworkDemo(zone)) }
    suspend fun appToken(code: String, verifier: String) = call { api.appToken(NetworkAppCode(code, verifier)) }
    suspend fun logout() = call { api.logout().orThrow() }
    suspend fun resendConfirmation() = call { api.resendConfirmation().orThrow() }
    suspend fun forgotPassword(email: String) = call { api.forgotPassword(NetworkEmail(email)).orThrow() }
    suspend fun downloads() = call { api.downloads() }

    suspend fun space() = call { api.space() }
    suspend fun setMyName(name: String) = call { api.setMyName(NetworkName(name)) }
    suspend fun createInviteLink() = call { api.createInviteLink() }
    suspend fun cancelInviteLink() = call { api.cancelInviteLink() }
    suspend fun uninvite(email: String) = call { api.uninvite(email) }

    suspend fun restaurants() = call { api.restaurants() }
    suspend fun restaurant(id: Long) = call { api.restaurant(id) }
    suspend fun createRestaurant(input: NetworkRestaurantInput) = call { api.createRestaurant(input) }
    suspend fun updateRestaurant(id: Long, input: NetworkRestaurantInput) = call { api.updateRestaurant(id, input) }
    suspend fun deleteRestaurant(id: Long) = call { api.deleteRestaurant(id).orThrow() }
    suspend fun addVisit(id: Long, visitedOn: String, note: String) = call { api.addVisit(id, NetworkNewVisit(visitedOn, note)) }
    suspend fun deleteVisit(id: Long, visit: Long) = call { api.deleteVisit(id, visit) }
    suspend fun rate(id: Long, stars: Int) = call { api.rate(id, NetworkStars(stars)) }
    suspend fun lookup(url: String, position: Boolean) = call { api.lookup(NetworkLookup(url, position)) }
    suspend fun searchPlaces(q: String, lat: Double?, lng: Double?) = call { api.searchPlaces(q, lat, lng) }
    suspend fun suggestPlaces(q: String, lat: Double?, lng: Double?, session: String) = call { api.suggestPlaces(q, lat, lng, session) }
    suspend fun placeDetails(id: String, session: String) = call { api.placeDetails(id, session) }

    suspend fun shifts(month: String?) = call { api.shifts(month) }
    suspend fun saveShifts(body: NetworkSaveShifts) = call { api.saveShifts(body) }
    suspend fun shiftSettings() = call { api.shiftSettings() }
    suspend fun saveShiftSettings(body: NetworkShiftSettingsInput) = call { api.saveShiftSettings(body) }
    suspend fun feed(on: Boolean) = call { if (on) api.feedOn() else api.feedOff() }

    /** How often a photo being read is asked after (tests make it short). */
    internal var readPollMs = 2_000L

    /**
     * Reads a photo of the sheet (a JPEG); nothing is saved until [saveShifts].
     * Reading takes about a minute, longer than one request may last behind
     * the CDN, so this starts the read and asks after it until it's done,
     * telling [onProgress] how far it's got.
     */
    suspend fun readPhoto(jpeg: ByteArray, onProgress: (NetworkReadProgress) -> Unit = {}): NetworkShiftMonth = call {
        val id = api.startRead(MultipartBody.Part.createFormData("photo", "sheet.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))).id
        // The server gives up after 3 minutes; this waits a little longer.
        val until = System.currentTimeMillis() + 5 * 60_000
        var failures = 0
        while (System.currentTimeMillis() < until) {
            delay(readPollMs)
            val res = try {
                api.readStatus(id)
            } catch (e: IOException) {
                // A phone moving between networks: try again a few times.
                if (++failures > 3) throw e
                continue
            }
            if (res.code() == 202) {
                val body = res.body()?.use { it.string() }
                body?.let { runCatching { json.decodeFromString<NetworkReadProgress>(it) }.getOrNull() }?.let(onProgress)
                continue
            }
            if (!res.isSuccessful) throw HttpException(res)
            return@call json.decodeFromString<NetworkShiftMonth>(res.body()!!.string())
        }
        throw ApiException(504, "the photo couldn't be read just now; try again, or enter the shifts by hand")
    }

    suspend fun busStops() = call { api.busStops() }
    suspend fun addBusStop(body: NetworkBusStopInput) = call { api.addBusStop(body) }
    suspend fun changeBusStop(id: Long, body: NetworkBusStopInput) = call { api.changeBusStop(id, body) }
    suspend fun deleteBusStop(id: Long) = call { api.deleteBusStop(id) }
    suspend fun useBusStop(id: Long) = call { api.useBusStop(id) }
    suspend fun setBuzz(buzz: String) = call { api.setBuzz(NetworkBuzz(buzz)) }
    suspend fun searchStops(q: String, lat: Double?, lng: Double?) = call { api.searchStops(q, lat, lng) }

    suspend fun activity() = call { api.activity() }

    /** The latest changes and bus alarms started, for the notification check. */
    suspend fun activityWithBus() = call { api.activity(bus = 1) }

    suspend fun mcpTokens() = call { api.mcpTokens() }
    suspend fun deleteMcpToken(id: Long) = call { api.deleteMcpToken(id).orThrow() }

    private fun Response<Unit>.orThrow() {
        if (!isSuccessful) throw HttpException(this)
    }

    /** A refusal becomes an [ApiException] with the server's message for the user. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val body = e.response()?.errorBody()?.string().orEmpty()
        val message = runCatching { json.decodeFromString<NetworkError>(body).error }.getOrNull().orEmpty()
        throw ApiException(e.code(), message.ifEmpty { e.message() }, fromProxy = message.isEmpty())
    }
}
