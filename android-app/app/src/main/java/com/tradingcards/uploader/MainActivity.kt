package com.tradingcards.uploader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.UploadRepository
import com.tradingcards.uploader.ui.CaptureScreen
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = UploadRepository.database(this)
        val repository = UploadRepository(this, database.uploadQueueDao())
        val authRepository = MsalAuthRepository(this)

        setContent {
            val scope = rememberCoroutineScope()
            val latestUpload by database.uploadQueueDao().latest().collectAsState(initial = null)
            var statusText by remember { mutableStateOf("Ready") }
            var pendingUri by remember { mutableStateOf<Uri?>(null) }
            var pendingFile by remember { mutableStateOf<File?>(null) }
            val takePictureLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
                    val uri = pendingUri
                    val file = pendingFile
                    pendingUri = null
                    pendingFile = null
                    if (saved && uri != null && file != null) {
                        scope.launch {
                            val uploadId =
                                repository.enqueue(
                                    localUri = uri.toString(),
                                    contentLengthBytes = file.length(),
                                )
                            statusText = "Upload queued: $uploadId"
                        }
                    } else {
                        file?.delete()
                        statusText = "Capture cancelled"
                    }
                }
            fun launchCapture() {
                runCatching {
                    val directory = File(filesDir, "captures").also { it.mkdirs() }
                    val file = File.createTempFile("card-", ".jpg", directory)
                    val uri =
                        FileProvider.getUriForFile(
                            this@MainActivity,
                            "$packageName.files",
                            file,
                        )
                    pendingFile = file
                    pendingUri = uri
                    takePictureLauncher.launch(uri)
                }.onFailure { error ->
                    pendingUri = null
                    pendingFile?.delete()
                    pendingFile = null
                    statusText = "Capture failed: ${error.message ?: "unable to open camera"}"
                }
            }
            val cameraPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        launchCapture()
                    } else {
                        statusText = "Camera permission is required to capture a card photo"
                    }
                }

            CaptureScreen(
                statusText = statusText,
                latestUpload = latestUpload,
                onSignIn = {
                    scope.launch {
                        runCatching { authRepository.signIn(this@MainActivity) }
                            .onSuccess { statusText = "Signed in" }
                            .onFailure { statusText = "Sign-in failed: ${it.message}" }
                    }
                },
                onCapture = {
                    if (
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        launchCapture()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
            )
        }
    }
}
