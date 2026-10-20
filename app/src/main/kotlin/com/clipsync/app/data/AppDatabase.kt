package com.clipsync.app.data

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "entries")
data class ClipEntryRow(
    @PrimaryKey val id: String,
    val entryType: String, // "text" | "image"
    val encryptedContent: ByteArray,
    val createdAt: Long,
    val pinned: Boolean,
    val direction: String, // "incoming" (from PC) | "outgoing" (sent to PC)
)

@Dao
interface ClipDao {
    @Query("SELECT * FROM entries ORDER BY pinned DESC, createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<ClipEntryRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipEntryRow)

    @Query("UPDATE entries SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM entries WHERE pinned = 0")
    suspend fun clearUnpinned()
}

/** v0.2 pairs with exactly one PC - this table only ever holds one row. */
@Entity(tableName = "paired_pc")
data class PairedPc(
    @PrimaryKey val deviceId: String,
    val name: String,
    val publicKey: ByteArray,
    val address: String, // "ip:port"
    val certHash: ByteArray, // raw SHA-256 of the PC's self-signed TLS cert, pinned on every connection
)

@Dao
interface PairedPcDao {
    @Query("SELECT * FROM paired_pc LIMIT 1")
    fun observePc(): Flow<PairedPc?>

    @Query("SELECT * FROM paired_pc LIMIT 1")
    suspend fun getPc(): PairedPc?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pc: PairedPc)

    @Query("DELETE FROM paired_pc")
    suspend fun clear()
}

@Database(entities = [ClipEntryRow::class, PairedPc::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun pairedPcDao(): PairedPcDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "clipsync.db",
                ).build().also { instance = it }
            }
    }
}
