package com.tradingcards.uploader.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tradingcards.uploader.BuildConfig
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.BlobUploader
import com.tradingcards.uploader.data.SasIssuerClient
import com.tradingcards.uploader.data.UploadRepository
import com.tradingcards.uploader.model.SasRequest
import com.tradingcards.uploader.model.UploadStateMachine
import com.tradingcards.uploader.model.UploadStatus

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploadId = inputData.getString(KEY_UPLOAD_ID) ?: return Result.failure()
        val dao = UploadRepository.database(applicationContext).uploadQueueDao()
        val entity = dao.get(uploadId) ?: return Result.failure()
        val attempt = entity.attemptCount + 1

        return try {
            dao.updateStatus(uploadId, UploadStatus.RequestingSas, attempt, null, System.currentTimeMillis())
            val token = MsalAuthRepository(applicationContext).acquireTokenSilent()
            val sasResponse = SasIssuerClient.create(BuildConfig.API_BASE_URL).issueUploadSas(
                authorization = "Bearer $token",
                request = SasRequest(
                    clientUploadId = uploadId,
                    contentType = entity.contentType,
                    contentLengthBytes = entity.contentLengthBytes,
                    sha256Hex = entity.sha256Hex,
                ),
            )
            if (!sasResponse.isSuccessful || sasResponse.body() == null) {
                val status = UploadStateMachine.nextForHttpFailure(sasResponse.code(), attempt, MAX_ATTEMPTS)
                dao.updateStatus(uploadId, status, attempt, "SAS request failed: ${sasResponse.code()}", System.currentTimeMillis())
                return if (status == UploadStatus.RetryWaiting) Result.retry() else Result.failure()
            }

            dao.updateStatus(uploadId, UploadStatus.Uploading, attempt, null, System.currentTimeMillis())
            val sas = requireNotNull(sasResponse.body())
            val uploadCode = BlobUploader(applicationContext).upload(
                uri = Uri.parse(entity.localUri),
                uploadUrl = sas.uploadUrl,
                requiredHeaders = sas.requiredHeaders,
            )
            if (uploadCode == 201 || uploadCode == 200) {
                dao.updateStatus(uploadId, UploadStatus.Uploaded, attempt, null, System.currentTimeMillis())
                dao.updateStatus(uploadId, UploadStatus.Complete, attempt, null, System.currentTimeMillis())
                Result.success()
            } else {
                val status = UploadStateMachine.nextForHttpFailure(uploadCode, attempt, MAX_ATTEMPTS)
                dao.updateStatus(uploadId, status, attempt, "Blob upload failed: $uploadCode", System.currentTimeMillis())
                if (status == UploadStatus.RetryWaiting) Result.retry() else Result.failure()
            }
        } catch (exception: IllegalStateException) {
            dao.updateStatus(
                uploadId,
                UploadStatus.FailedTerminal,
                attempt,
                exception.message,
                System.currentTimeMillis(),
            )
            Result.failure()
        } catch (exception: Exception) {
            val status = UploadStateMachine.nextForNetworkFailure(attempt, MAX_ATTEMPTS)
            dao.updateStatus(uploadId, status, attempt, exception.message, System.currentTimeMillis())
            if (status == UploadStatus.RetryWaiting) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_UPLOAD_ID = "uploadId"
        private const val MAX_ATTEMPTS = 5
    }
}
