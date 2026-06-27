package com.tradingcards.uploader.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.tradingcards.uploader.model.UploadEntity
import com.tradingcards.uploader.model.UploadStatus
import com.tradingcards.uploader.model.UploadStatusConverter

@Dao
interface UploadQueueDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: UploadEntity)

    @Query("SELECT * FROM upload_queue WHERE uploadId = :uploadId")
    suspend fun get(uploadId: String): UploadEntity?

    @Query("SELECT * FROM upload_queue ORDER BY createdAtEpochMillis DESC LIMIT 20")
    suspend fun recent(): List<UploadEntity>

    @Query(
        """
        UPDATE upload_queue
        SET status = :status,
            attemptCount = :attemptCount,
            lastError = :lastError,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE uploadId = :uploadId
        """,
    )
    suspend fun updateStatus(
        uploadId: String,
        status: UploadStatus,
        attemptCount: Int,
        lastError: String?,
        updatedAtEpochMillis: Long,
    )
}

@Database(entities = [UploadEntity::class], version = 1, exportSchema = false)
@TypeConverters(UploadStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadQueueDao(): UploadQueueDao
}
