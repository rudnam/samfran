package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.network.ApiException
import com.rudnam.samfran.core.network.SignedOutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/** How a call to the server went. */
sealed interface Outcome<out T> {
    data class Done<T>(val value: T) : Outcome<T>

    /** The server couldn't be reached. */
    data object Offline : Outcome<Nothing>

    /** The session had ended; the app is going back to signing in. */
    data object SignedOut : Outcome<Nothing>

    /** The server said no: [message] is its words for the user ("" if it gave none). */
    data class Refused(val message: String) : Outcome<Nothing>
}

val <T> Outcome<T>.valueOrNull: T? get() = (this as? Outcome.Done)?.value

/** Runs [block] (calls to the server) on [io], and says how it went. */
internal suspend fun <T> outcome(io: CoroutineDispatcher, block: suspend () -> T): Outcome<T> = withContext(io) {
    try {
        Outcome.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SignedOutException) {
        Outcome.SignedOut
    } catch (e: IOException) {
        Outcome.Offline
    } catch (e: ApiException) {
        // A server that's down or restarting answers from the proxy: to the user, it can't be reached.
        if (e.status in 502..504) Outcome.Offline else Outcome.Refused(e.message)
    } catch (e: kotlinx.serialization.SerializationException) {
        Outcome.Refused("")
    } catch (e: IllegalArgumentException) {
        Outcome.Refused("") // an answer written in a way it never has been
    } catch (e: java.time.format.DateTimeParseException) {
        Outcome.Refused("")
    }
}
