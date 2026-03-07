package com.cybersensei.tvplayer.data.db

import android.content.Context
import androidx.room.*

@Entity(tableName = "media_files")
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_item_id") val mediaItemId: Int,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long = System.currentTimeMillis()
)

@Dao
interface MediaFileDao {
    @Query("SELECT * FROM media_files")
    suspend fun getAll(): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE media_item_id = :mediaItemId LIMIT 1")
    suspend fun getByMediaItemId(mediaItemId: Int): MediaFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaFileEntity): Long

    @Delete
    suspend fun delete(entity: MediaFileEntity)

    @Query("DELETE FROM media_files WHERE media_item_id = :mediaItemId")
    suspend fun deleteByMediaItemId(mediaItemId: Int)

    @Query("SELECT COUNT(*) FROM media_files")
    suspend fun getCount(): Int

    @Query("SELECT SUM(file_size) FROM media_files")
    suspend fun getTotalSize(): Long?
}

@Database(entities = [MediaFileEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaFileDao(): MediaFileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cyber_sensei_tv.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
