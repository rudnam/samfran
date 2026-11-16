package com.rudnam.samfran.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * One answer from the server, as it came (JSON), under the key the
 * website's page cache uses for it: "restaurants", "restaurant:12",
 * "shifts:2026-11", "space", "bus-stop", "activity"… Screens show it at once,
 * offline too, while the repository asks the server again.
 *
 * Whole answers rather than tables of rows: every screen shows a whole
 * answer, the data is small (two people's places and shifts), and nothing is
 * queried or changed on the phone without the server, so rows would add
 * schema and migrations for nothing. Changes go to the server first; its
 * answer replaces the document.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val key: String,
    val json: String,
    /** When it came, in milliseconds since 1970. */
    val savedAt: Long,
)

@Dao
interface DocumentDao {
    @Query("SELECT json FROM documents WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT json FROM documents WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE `key` = :key")
    suspend fun delete(key: String)

    /** Signing out: the next person starts from nothing. */
    @Query("DELETE FROM documents")
    suspend fun clear()
}

@Database(entities = [DocumentEntity::class], version = 1)
abstract class SamFranDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SamFranDatabase =
        Room.databaseBuilder(context, SamFranDatabase::class.java, "samfran.db")
            // A copy of the server's answers: a new version can always start empty and fetch them again.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun documents(db: SamFranDatabase): DocumentDao = db.documents()
}
