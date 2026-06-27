package com.tradingcards.uploader.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tradingcards.uploader.model.UploadEntity
import com.tradingcards.uploader.model.UploadStatus
import com.tradingcards.uploader.worker.UploadWorker
import java.util.UUID

class UploadRepository(
    private val context: Context,
    private val dao: UploadQueueDao,
) {
    suspend fun enqueue(
        localUri: String,
        contentLengthBytes: Long,
        contentType: String = "image/jpeg",
    ): String {
        val now = System.currentTimeMillis()
        val uploadId = UUID.randomUUID().toString()
        dao.insert(
            UploadEntity(
                uploadId = uploadId,
                localUri = localUri,
                contentType = contentType,
                contentLengthBytes = contentLengthBytes,
                sha256Hex = null,
                serverUploadId = null,
                blobName = null,
                status = UploadStatus.Queued,
                attemptCount = 0,
                lastError = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        val request =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(Data.Builder().putString(UploadWorker.KEY_UPLOAD_ID, uploadId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
        WorkManager.getInstance(context).enqueue(request)
        return uploadId
    }

    companion object {
        @Volatile
        private var database: AppDatabase? = null

        fun database(context: Context): AppDatabase =
            database ?: synchronized(this) {
                database ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trading-card-uploader.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { database = it }
            }

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE upload_queue ADD COLUMN serverUploadId TEXT")
                    db.execSQL("ALTER TABLE upload_queue ADD COLUMN blobName TEXT")
                }
            }
    }
}
