package com.rudnam.samfran.core.network

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.rudnam.samfran.core.datastore.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The server the app talks to (samfran.server in android/gradle.properties). */
@Singleton
class Server @Inject constructor() {
    val base: String = BuildConfig.SERVER

    /** A path the server gave, e.g. /download/samfran.apk or /join/…, as a full address. */
    fun resolve(path: String): String = if (path.startsWith("http")) path else base + path
}

/**
 * Signs every request in as the Android app (internal/account/app.go): the
 * session's bearer token and X-App-Client, as the Capacitor build did, so
 * the server needs nothing new. Accept-Language is the app's language, which
 * the server writes emails in. A 401 on a request made with a session means
 * it has ended: it's forgotten, and the call fails with [SignedOutException].
 */
class AuthInterceptor(
    private val session: SessionStore,
    private val language: () -> LocaleListCompat,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-App-Client", "android")
            .header("Accept", "application/json")
            .header("Accept-Language", language().toLanguageTags().ifEmpty { "en" })
        val token = session.currentToken()
        token?.let { request.header("Authorization", "Bearer $it") }
        val response = chain.proceed(request.build())
        // Only a request made with a session can find it ended: a wrong password is a 401 too.
        if (response.code == 401 && token != null) {
            response.close()
            session.clear()
            throw SignedOutException()
        }
        return response
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun okHttp(session: SessionStore): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Reading a photo of the sheet takes a few minutes, but in the
        // background (SamFranNetwork.readPhoto): no request waits for it.
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            AuthInterceptor(session) {
                // The app's own language (Android's per-app language), else the phone's.
                AppCompatDelegate.getApplicationLocales().takeUnless { it.isEmpty } ?: LocaleListCompat.getAdjustedDefault()
            },
        )
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.SERVER + "/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
