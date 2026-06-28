package com.tradingcards.uploader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.UploadQueueDao
import com.tradingcards.uploader.data.UploadRepository
import com.tradingcards.uploader.ui.CaptureScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = UploadRepository.database(this)
        val repository = UploadRepository(this, database.uploadQueueDao())
        val authRepository = MsalAuthRepository(this)

        setContent {
            UploaderApp(
                uploadQueueDao = database.uploadQueueDao(),
                repository = repository,
                authRepository = authRepository,
            )
        }
    }

    @Composable
    private fun UploaderApp(
        uploadQueueDao: UploadQueueDao,
        repository: UploadRepository,
        authRepository: MsalAuthRepository,
    ) {
        val scope = rememberCoroutineScope()
        val latestUpload by uploadQueueDao.latest().collectAsState(initial = null)
        var statusText by remember { mutableStateOf("Ready") }
        var pendingCapture by remember { mutableStateOf<PendingCapture?>(null) }
        val takePictureLauncher =
            rememberTakePictureLauncher(
                pendingCapture = pendingCapture,
                clearPendingCapture = { pendingCapture = null },
                repository = repository,
                scope = scope,
                onStatusTextChanged = { statusText = it },
            )
        val cameraPermissionLauncher =
            rememberCameraPermissionLauncher(
                takePictureLauncher = takePictureLauncher,
                setPendingCapture = { pendingCapture = it },
                onStatusTextChanged = { statusText = it },
            )

        CaptureScreen(
            statusText = statusText,
            latestUpload = latestUpload,
            onSignIn = {
                signIn(
                    scope = scope,
                    authRepository = authRepository,
                    onStatusTextChanged = { statusText = it },
                )
            },
            onCapture = {
                if (hasCameraPermission()) {
                    launchCapture(
                        takePictureLauncher = takePictureLauncher,
                        setPendingCapture = { pendingCapture = it },
                        onStatusTextChanged = { statusText = it },
                    )
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        )
    }

    @Composable
    private fun rememberTakePictureLauncher(
        pendingCapture: PendingCapture?,
        clearPendingCapture: () -> Unit,
        repository: UploadRepository,
        scope: CoroutineScope,
        onStatusTextChanged: (String) -> Unit,
    ): ActivityResultLauncher<Uri> =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val capture = pendingCapture
            clearPendingCapture()
            if (saved && capture != null) {
                scope.launch {
                    val uploadId =
                        repository.enqueue(
                            localUri = capture.uri.toString(),
                            contentLengthBytes = capture.file.length(),
                        )
                    onStatusTextChanged("Upload queued: $uploadId")
                }
            } else {
                capture?.file?.delete()
                onStatusTextChanged("Capture cancelled")
            }
        }

    @Composable
    private fun rememberCameraPermissionLauncher(
        takePictureLauncher: ActivityResultLauncher<Uri>,
        setPendingCapture: (PendingCapture) -> Unit,
        onStatusTextChanged: (String) -> Unit,
    ): ActivityResultLauncher<String> =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCapture(
                    takePictureLauncher = takePictureLauncher,
                    setPendingCapture = setPendingCapture,
                    onStatusTextChanged = onStatusTextChanged,
                )
            } else {
                onStatusTextChanged("Camera permission is required to capture a card photo")
            }
        }

    private fun signIn(
        scope: CoroutineScope,
        authRepository: MsalAuthRepository,
        onStatusTextChanged: (String) -> Unit,
    ) {
        scope.launch {
            runCatching { authRepository.signIn(this@MainActivity) }
                .onSuccess { onStatusTextChanged("Signed in") }
                .onFailure { onStatusTextChanged("Sign-in failed: ${it.message}") }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchCapture(
        takePictureLauncher: ActivityResultLauncher<Uri>,
        setPendingCapture: (PendingCapture) -> Unit,
        onStatusTextChanged: (String) -> Unit,
    ) {
        runCatching { createPendingCapture() }
            .onSuccess { capture ->
                setPendingCapture(capture)
                takePictureLauncher.launch(capture.uri)
            }.onFailure { error ->
                onStatusTextChanged("Capture failed: ${error.message ?: "unable to open camera"}")
            }
    }

    private fun createPendingCapture(): PendingCapture {
        val directory = File(filesDir, "captures").also { it.mkdirs() }
        val file = File.createTempFile("card-", ".jpg", directory)
        return runCatching {
            PendingCapture(
                file = file,
                uri =
                    FileProvider.getUriForFile(
                        this,
                        "$packageName.files",
                        file,
                    ),
            )
        }.getOrElse { error ->
            file.delete()
            throw error
        }
    }

    private data class PendingCapture(
        val file: File,
        val uri: Uri,
    )
}
