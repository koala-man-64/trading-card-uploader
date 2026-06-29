package com.tradingcards.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.model.UploadEntity

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    statusText: String,
    latestUpload: UploadEntity?,
    onAuthenticate: () -> Unit,
    onCapture: () -> Unit,
) {
    MaterialTheme {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Trading Card Uploader",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(statusText)
            Button(onClick = onAuthenticate) {
                Text("Sign in")
            }
            Button(onClick = onCapture) {
                Text("Capture card photo")
            }
            latestUpload?.let { upload ->
                HorizontalDivider()
                Text(
                    "Latest upload",
                    style = MaterialTheme.typography.titleMedium,
                )
                UploadLine("Client upload ID", upload.uploadId)
                upload.serverUploadId?.let { UploadLine("Server upload ID", it) }
                UploadLine("Status", upload.status.name)
                UploadLine("Attempts", upload.attemptCount.toString())
                upload.blobName?.let { UploadLine("Blob", it) }
                upload.lastError?.let { UploadLine("Last error", it) }
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun UploadLine(
    label: String,
    value: String,
) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}
