package com.tradingcards.uploader

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
            var statusText by remember { mutableStateOf("Ready") }
            var pendingUri by remember { mutableStateOf<Uri?>(null) }
            var pendingFile by remember { mutableStateOf<File?>(null) }
            val launcher =
                rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
                    val uri = pendingUri
                    val file = pendingFile
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
                        statusText = "Capture cancelled"
                    }
                }

            CaptureScreen(
                statusText = statusText,
                onSignIn = {
                    scope.launch {
                        runCatching { authRepository.signIn(this@MainActivity) }
                            .onSuccess { statusText = "Signed in" }
                            .onFailure { statusText = "Sign-in failed: ${it.message}" }
                    }
                },
                onCapture = {
                    val directory = File(filesDir, "captures").also { it.mkdirs() }
                    val file = File.createTempFile("card-", ".jpg", directory)
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    pendingFile = file
                    pendingUri = uri
                    launcher.launch(uri)
                },
            )
        }
    }
}
