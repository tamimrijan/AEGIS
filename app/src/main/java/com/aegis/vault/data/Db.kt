package com.aegis.vault.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Only ciphertext reaches SQLite. [meta] and [secret] are AES-256-GCM blobs whose AAD is
 * bound to this row's id and type; the timestamps are the only plaintext columns.
 */
@Entity(tableName = "items")
class ItemEntity(
    @PrimaryKey val id: String,
    val type: String,
    val meta: ByteArray,
    val secret: ByteArray?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "logs")
class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val event: String,
    /** Encrypted entry title, or null. Never contains credential contents. */
    val detail: ByteArray?,
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM items")
    suspend fun all(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM items")
    suspend fun deleteAllItems()

    @Insert
    suspend fun addLog(e: LogEntity)

    @Query("SELECT * FROM logs ORDER BY time DESC, id DESC LIMIT 500")
    suspend fun logs(): List<LogEntity>

    @Query("DELETE FROM logs")
    suspend fun clearLogs()

    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY time DESC, id DESC LIMIT 1000)")
    suspend fun trimLogs()
}

@Database(entities = [ItemEntity::class, LogEntity::class], version = 1, exportSchema = false)
abstract class AegisDb : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        @Volatile
        private var instance: AegisDb? = null

        fun get(context: Context): AegisDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AegisDb::class.java, "aegis.db")
                .build().also { instance = it }
        }
    }
}
