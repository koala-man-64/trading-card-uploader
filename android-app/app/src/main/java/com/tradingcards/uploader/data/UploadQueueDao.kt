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
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadQueueDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: UploadEntity)

    @Query("SELECT * FROM upload_queue WHERE uploadId = :uploadId")
    suspend fun get(uploadId: String): UploadEntity?

    @Query("SELECT * FROM upload_queue ORDER BY createdAtEpochMillis DESC LIMIT 20")
    suspend fun recent(): List<UploadEntity>

    @Query("SELECT * FROM upload_queue ORDER BY createdAtEpochMillis DESC LIMIT 1")
    fun latest(): Flow<UploadEntity?>

    @Query(
        """
        UPDATE upload_queue
        SET contentLengthBytes = :contentLengthBytes,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE uploadId = :uploadId
        """,
    )
    suspend fun updateContentLength(
        uploadId: String,
        contentLengthBytes: Long,
        updatedAtEpochMillis: Long,
    )

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

    @Query(
        """
        UPDATE upload_queue
        SET serverUploadId = :serverUploadId,
            blobName = :blobName,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE uploadId = :uploadId
        """,
    )
    suspend fun updateServerUpload(
        uploadId: String,
        serverUploadId: String,
        blobName: String,
        updatedAtEpochMillis: Long,
    )
}

@Database(entities = [UploadEntity::class], version = 2, exportSchema = false)
@TypeConverters(UploadStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadQueueDao(): UploadQueueDao
}
