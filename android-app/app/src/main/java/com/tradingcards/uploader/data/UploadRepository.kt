package com.tradingcards.uploader.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    suspend fun enqueueSelectedPhoto(sourceUri: Uri): String {
        val selectedPhoto = copySelectedPhoto(sourceUri)
        return runCatching {
            enqueue(
                localUri = selectedPhoto.uri.toString(),
                contentLengthBytes = selectedPhoto.file.length(),
                contentType = selectedPhoto.contentType,
            )
        }.onFailure {
            selectedPhoto.file.delete()
        }.getOrThrow()
    }

    private suspend fun copySelectedPhoto(sourceUri: Uri): PendingSelectedPhoto =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val contentType =
                supportedSelectedPhotoContentType(appContext.contentResolver.getType(sourceUri))
                    ?: error("Choose a JPEG, HEIC, or HEIF photo")
            val directory = File(appContext.filesDir, "captures").also { it.mkdirs() }
            val file =
                File.createTempFile(
                    "gallery-",
                    selectedPhotoFileExtension(contentType),
                    directory,
                )
            runCatching {
                appContext.contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { "Unable to open selected photo" }
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                check(file.length() > 0) { "Selected photo is empty" }
                PendingSelectedPhoto(
                    file = file,
                    uri =
                        FileProvider.getUriForFile(
                            appContext,
                            "${appContext.packageName}.files",
                            file,
                        ),
                    contentType = contentType,
                )
            }.getOrElse { error ->
                file.delete()
                throw error
            }
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

private data class PendingSelectedPhoto(
    val file: File,
    val uri: Uri,
    val contentType: String,
)
