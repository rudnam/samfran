package com.rudnam.samfran.core.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.rudnam.samfran.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files

/** The user's preferences, kept in a throwaway file. */
object TestPreferences {
    fun repository(): UserPreferencesRepository {
        val dir = Files.createTempDirectory("prefs").toFile()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(dir, "test.preferences_pb") },
        )
        return UserPreferencesRepository(store)
    }
}
