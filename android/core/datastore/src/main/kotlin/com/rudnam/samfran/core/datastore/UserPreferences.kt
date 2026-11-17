package com.rudnam.samfran.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rudnam.samfran.core.model.Me
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Light, dark, or whatever the phone uses (Us › This device › Appearance). */
enum class Appearance(val key: String) {
    System("auto"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromKey(key: String?): Appearance = entries.firstOrNull { it.key == key } ?: System
    }
}

data class UserPreferences(
    val appearance: Appearance = Appearance.System,
    /** The newest update the user said "Later" to: not offered again until a newer one. */
    val updateLaterVersion: Int = 0,
    /** The newest change seen on this phone (Recent changes, the Us tab's dot); null before the first look. */
    val activitySeen: Long? = null,
    /** The Shifts tab shows the month as a list rather than a calendar. */
    val shiftsAsList: Boolean = false,
    /** The Shifts tab shows everyone there during a shift, not only those on the same one. */
    val shiftsEveryone: Boolean = false,
)

/** The user's choices about this phone's app, kept on the phone. */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> = dataStore.data.map { p ->
        UserPreferences(
            appearance = Appearance.fromKey(p[APPEARANCE]),
            updateLaterVersion = p[UPDATE_LATER] ?: 0,
            activitySeen = p[ACTIVITY_SEEN],
            shiftsAsList = p[SHIFTS_AS_LIST] ?: false,
            shiftsEveryone = p[SHIFTS_EVERYONE] ?: false,
        )
    }

    suspend fun setAppearance(appearance: Appearance) {
        dataStore.edit { it[APPEARANCE] = appearance.key }
    }

    suspend fun updateLater(versionCode: Int) {
        dataStore.edit { it[UPDATE_LATER] = versionCode }
    }

    suspend fun setActivitySeen(id: Long) {
        dataStore.edit { it[ACTIVITY_SEEN] = id }
    }

    suspend fun setShiftsAsList(list: Boolean) {
        dataStore.edit { it[SHIFTS_AS_LIST] = list }
    }

    suspend fun setShiftsEveryone(on: Boolean) {
        dataStore.edit { it[SHIFTS_EVERYONE] = on }
    }

    /** Who was signed in when the server last said, so the app can show it offline; null once they sign out. */
    val me: Flow<Me?> = dataStore.data.map { p ->
        p[ME_EMAIL]?.let { Me(it, hasPassword = p[ME_PASSWORD] ?: true, confirmEmail = p[ME_CONFIRM] ?: false, demo = p[ME_DEMO] ?: false) }
    }

    suspend fun setMe(me: Me?) {
        dataStore.edit {
            if (me == null) {
                it.remove(ME_EMAIL)
                it.remove(ME_PASSWORD)
                it.remove(ME_DEMO)
                it.remove(ME_CONFIRM)
                it.remove(ACTIVITY_SEEN) // the next person's changes start from nothing
            } else {
                it[ME_EMAIL] = me.email
                it[ME_PASSWORD] = me.hasPassword
                it[ME_DEMO] = me.demo
                it[ME_CONFIRM] = me.confirmEmail
            }
        }
    }

    private companion object {
        val APPEARANCE = stringPreferencesKey("appearance")
        val UPDATE_LATER = intPreferencesKey("update_later")
        val ACTIVITY_SEEN = longPreferencesKey("activity_seen")
        val SHIFTS_AS_LIST = booleanPreferencesKey("shifts_as_list")
        val SHIFTS_EVERYONE = booleanPreferencesKey("shifts_everyone")
        val ME_EMAIL = stringPreferencesKey("me_email")
        val ME_PASSWORD = booleanPreferencesKey("me_has_password")
        val ME_DEMO = booleanPreferencesKey("me_demo")
        val ME_CONFIRM = booleanPreferencesKey("me_confirm_email")
    }
}
