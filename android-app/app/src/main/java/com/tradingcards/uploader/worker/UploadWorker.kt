package com.tradingcards.uploader.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.microsoft.identity.client.exception.MsalException
import com.tradingcards.uploader.BuildConfig
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.BlobUploader
import com.tradingcards.uploader.data.SasIssuerClient
import com.tradingcards.uploader.data.UploadQueueDao
import com.tradingcards.uploader.data.UploadRepository
import com.tradingcards.uploader.data.currentUploadContentLength
import com.tradingcards.uploader.data.resolveCurrentContentLengthBytes
import com.tradingcards.uploader.model.SasRequest
import com.tradingcards.uploader.model.SasResponse
import com.tradingcards.uploader.model.UploadEntity
import com.tradingcards.uploader.model.UploadStateMachine
import com.tradingcards.uploader.model.UploadStatus
import java.io.IOException

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = UploadRepository.database(applicationContext).uploadQueueDao()
        val uploadId = inputData.getString(KEY_UPLOAD_ID)
        val entity = uploadId?.let { dao.get(it) }
        return if (uploadId == null || entity == null) {
            Result.failure()
        } else {
            upload(dao, uploadId, entity)
        }
    }

    private suspend fun upload(
        dao: UploadQueueDao,
        uploadId: String,
        entity: UploadEntity,
    ): Result {
        val attempt = entity.attemptCount + 1

        return try {
            val blobUpload = prepareBlobUpload(dao, uploadId, entity)
            dao.updateStatus(
                uploadId,
                UploadStatus.RequestingSas,
                attempt,
                null,
                System.currentTimeMillis(),
            )
            val token = MsalAuthRepository(applicationContext).acquireUploadTokenSilent()
            val sasResponse =
                SasIssuerClient.create(BuildConfig.API_BASE_URL).issueUploadSas(
                    authorization = "Bearer $token",
                    request =
                        SasRequest(
                            clientUploadId = uploadId,
                            contentType = entity.contentType,
                            contentLengthBytes = blobUpload.contentLengthBytes,
                            sha256Hex = entity.sha256Hex,
                        ),
                )
            val sas = sasResponse.body()
            if (!sasResponse.isSuccessful || sas == null) {
                handleHttpFailure(
                    dao = dao,
                    uploadId = uploadId,
                    attempt = attempt,
                    statusCode = sasResponse.code(),
                    message = "SAS request failed: ${sasResponse.code()}",
                )
            } else {
                dao.updateServerUpload(
                    uploadId = uploadId,
                    serverUploadId = sas.uploadId,
                    blobName = sas.blobName,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                uploadBlob(dao, uploadId, blobUpload, sas, attempt)
            }
        } catch (exception: IOException) {
            handleNetworkFailure(dao, uploadId, attempt, exception.message)
        } catch (exception: IllegalStateException) {
            handleTerminalFailure(dao, uploadId, attempt, exception.message)
        } catch (exception: MsalException) {
            handleTerminalFailure(dao, uploadId, attempt, exception.message)
        }
    }

    private suspend fun prepareBlobUpload(
        dao: UploadQueueDao,
        uploadId: String,
        entity: UploadEntity,
    ): PendingBlobUpload {
        val uploadUri = Uri.parse(entity.localUri)
        val contentLengthBytes =
            currentUploadContentLength(
                storedContentLengthBytes = entity.contentLengthBytes,
                resolvedContentLengthBytes = resolveCurrentContentLengthBytes(applicationContext, uploadUri),
            )
        check(contentLengthBytes > 0) { "Upload content length is invalid: $contentLengthBytes" }
        if (contentLengthBytes != entity.contentLengthBytes) {
            dao.updateContentLength(uploadId, contentLengthBytes, System.currentTimeMillis())
        }
        return PendingBlobUpload(uploadUri, contentLengthBytes)
    }

    private suspend fun uploadBlob(
        dao: UploadQueueDao,
        uploadId: String,
        blobUpload: PendingBlobUpload,
        sas: SasResponse,
        attempt: Int,
    ): Result {
        dao.updateStatus(uploadId, UploadStatus.Uploading, attempt, null, System.currentTimeMillis())
        val uploadResult =
            BlobUploader(applicationContext).upload(
                uri = blobUpload.uploadUri,
                contentLengthBytes = blobUpload.contentLengthBytes,
                uploadUrl = sas.uploadUrl,
                requiredHeaders = sas.requiredHeaders,
            )
        return if (uploadResult.statusCode in SUCCESSFUL_UPLOAD_CODES) {
            dao.updateStatus(uploadId, UploadStatus.Uploaded, attempt, null, System.currentTimeMillis())
            dao.updateStatus(uploadId, UploadStatus.Complete, attempt, null, System.currentTimeMillis())
            Result.success()
        } else {
            handleHttpFailure(
                dao = dao,
                uploadId = uploadId,
                attempt = attempt,
                statusCode = uploadResult.statusCode,
                message = uploadResult.failureMessage ?: "Blob upload failed: ${uploadResult.statusCode}",
            )
        }
    }

    private suspend fun handleHttpFailure(
        dao: UploadQueueDao,
        uploadId: String,
        attempt: Int,
        statusCode: Int,
        message: String,
    ): Result {
        val status = UploadStateMachine.nextForHttpFailure(statusCode, attempt, MAX_ATTEMPTS)
        dao.updateStatus(uploadId, status, attempt, message, System.currentTimeMillis())
        return if (status == UploadStatus.RetryWaiting) Result.retry() else Result.failure()
    }

    private suspend fun handleNetworkFailure(
        dao: UploadQueueDao,
        uploadId: String,
        attempt: Int,
        message: String?,
    ): Result {
        val status = UploadStateMachine.nextForNetworkFailure(attempt, MAX_ATTEMPTS)
        dao.updateStatus(uploadId, status, attempt, message, System.currentTimeMillis())
        return if (status == UploadStatus.RetryWaiting) Result.retry() else Result.failure()
    }

    private suspend fun handleTerminalFailure(
        dao: UploadQueueDao,
        uploadId: String,
        attempt: Int,
        message: String?,
    ): Result {
        dao.updateStatus(
            uploadId,
            UploadStatus.FailedTerminal,
            attempt,
            message,
            System.currentTimeMillis(),
        )
        return Result.failure()
    }

    companion object {
        const val KEY_UPLOAD_ID = "uploadId"
        private const val MAX_ATTEMPTS = 5
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private val SUCCESSFUL_UPLOAD_CODES = setOf(HTTP_OK, HTTP_CREATED)
    }
}

private data class PendingBlobUpload(
    val uploadUri: Uri,
    val contentLengthBytes: Long,
)
