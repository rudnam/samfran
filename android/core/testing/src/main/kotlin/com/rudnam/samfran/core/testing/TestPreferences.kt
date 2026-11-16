package com.rudnam.samfran.core.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The user's preferences in memory: they answer at once, on the test's own
 * thread, so tests don't race a file being read in the background.
 */
object TestPreferences {
    fun repository(): UserPreferencesRepository = UserPreferencesRepository(MemoryDataStore())

    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
