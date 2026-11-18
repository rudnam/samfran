package com.rudnam.samfran.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesTest {
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `a phone that had the old Nearby switch on opens on Nearby, until another order is chosen`() = runTest {
        val store = MemoryDataStore()
        val preferences = UserPreferencesRepository(store)
        assertEquals("", preferences.preferences.first().placesOrder)
        store.edit { it[booleanPreferencesKey("places_nearby")] = true }
        assertEquals("nearby", preferences.preferences.first().placesOrder)
        preferences.setPlacesOrder("name")
        assertEquals("name", preferences.preferences.first().placesOrder)
        preferences.setPlacesOrder("newest")
        assertEquals("newest", preferences.preferences.first().placesOrder)
    }
}
