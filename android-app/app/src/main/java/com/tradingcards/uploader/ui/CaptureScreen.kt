package com.tradingcards.uploader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.model.UploadEntity
import com.tradingcards.uploader.model.UploadStatus
import com.tradingcards.uploader.ui.theme.LocalStatusPalette

private const val FULL_WEIGHT = 1f

data class CaptureScreenActions(
    val onAuthenticate: () -> Unit,
    val onCapture: () -> Unit,
    val onSelectPhoto: () -> Unit,
    val onRetry: (UploadEntity) -> Unit,
)

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
fun CaptureScreen(
    statusText: String,
    uploads: List<UploadEntity>,
    signedIn: Boolean,
    actions: CaptureScreenActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        Header(signedIn = signedIn, onAuthenticate = actions.onAuthenticate)
        StatusCaption(statusText)
        if (uploads.isNotEmpty()) {
            SummaryRow(uploads)
        }
        if (uploads.isEmpty()) {
            EmptyState(modifier = Modifier.weight(FULL_WEIGHT))
        } else {
            UploadList(
                uploads = uploads,
                onRetry = actions.onRetry,
                modifier = Modifier.weight(FULL_WEIGHT),
            )
        }
        ActionBar(
            signedIn = signedIn,
            onAuthenticate = actions.onAuthenticate,
            onCapture = actions.onCapture,
            onSelectPhoto = actions.onSelectPhoto,
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun Header(
    signedIn: Boolean,
    onAuthenticate: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
    ) {
        Text(
            "Your cards",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(FULL_WEIGHT),
        )
        if (signedIn) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(LocalStatusPalette.current.success),
                    )
                    Text(
                        "Signed in",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            TextButton(onClick = onAuthenticate) { Text("Sign in") }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun StatusCaption(statusText: String) {
    if (statusText.isBlank() || statusText == "Ready") return
    Text(
        statusText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun SummaryRow(uploads: List<UploadEntity>) {
    val uploaded = uploads.count { it.status == UploadStatus.Complete }
    val uploading = uploads.count { it.status.toUi().inProgress }
    val failed = uploads.count { it.status == UploadStatus.FailedTerminal }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SummaryStat("Uploaded", uploaded, MaterialTheme.colorScheme.onSurface, Modifier.weight(FULL_WEIGHT))
        SummaryStat("Uploading", uploading, MaterialTheme.colorScheme.primary, Modifier.weight(FULL_WEIGHT))
        SummaryStat("Failed", failed, MaterialTheme.colorScheme.error, Modifier.weight(FULL_WEIGHT))
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun SummaryStat(
    label: String,
    value: Int,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = valueColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun UploadList(
    uploads: List<UploadEntity>,
    onRetry: (UploadEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            "Recent",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uploads, key = { it.uploadId }) { upload ->
                UploadRow(upload, onRetry)
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun UploadRow(
    upload: UploadEntity,
    onRetry: (UploadEntity) -> Unit,
) {
    val ui = upload.status.toUi()
    val border =
        if (ui.tone == StatusTone.Error) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = border,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            CardThumbnail(upload.localUri)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(FULL_WEIGHT)) {
                Text(
                    "Card photo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                if (ui.inProgress) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                    )
                } else {
                    Text(
                        rowSubtitle(upload, ui),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (ui.tone == StatusTone.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (ui.canRetry) {
                TextButton(onClick = { onRetry(upload) }) { Text("Retry") }
            } else {
                StatusPill(ui)
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun StatusPill(ui: UploadStatusUi) {
    val status = LocalStatusPalette.current
    val container =
        when (ui.tone) {
            StatusTone.Accent -> MaterialTheme.colorScheme.primaryContainer
            StatusTone.Success -> status.successContainer
            StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
            StatusTone.Error -> MaterialTheme.colorScheme.errorContainer
        }
    val content =
        when (ui.tone) {
            StatusTone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer
            StatusTone.Success -> status.onSuccessContainer
            StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
            StatusTone.Error -> MaterialTheme.colorScheme.onErrorContainer
        }
    Surface(color = container, shape = CircleShape) {
        Text(
            ui.label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Capture your first card",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Cards you capture or pick from Photos upload automatically and show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ActionBar(
    signedIn: Boolean,
    onAuthenticate: () -> Unit,
    onCapture: () -> Unit,
    onSelectPhoto: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { if (signedIn) onCapture() else onAuthenticate() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(54.dp),
        ) {
            Text(
                if (signedIn) "Capture card" else "Sign in to upload",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        OutlinedButton(
            onClick = onSelectPhoto,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Upload from Photos")
        }
    }
}
