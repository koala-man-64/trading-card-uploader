package com.tradingcards.uploader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.R
import com.tradingcards.uploader.model.SourceActivity

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
fun MonitorScreen(
    state: MonitorUiState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonitorHeader(state, onRefresh)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "status") {
                ScannerStatusCard(status = state.status, errorText = state.statusErrorText)
            }
            item(key = "activity-heading") {
                Text(
                    stringResource(R.string.monitor_activity_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            when {
                state.activityErrorText != null ->
                    item(key = "activity-error") { MonitorNotice(state.activityErrorText, isError = true) }
                state.activities.isEmpty() ->
                    item(key = "activity-empty") {
                        MonitorNotice(stringResource(R.string.monitor_activity_empty), isError = false)
                    }
                else ->
                    items(state.activities, key = { it.sourceBlobName }) { activity ->
                        SourceActivityRow(activity)
                    }
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun MonitorHeader(
    state: MonitorUiState,
    onRefresh: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.monitor_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (state.loading) {
                    stringResource(R.string.gallery_loading)
                } else {
                    pluralStringResource(
                        R.plurals.monitor_source_count,
                        state.activities.size,
                        state.activities.size,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh, enabled = !state.loading) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.gallery_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun MonitorNotice(
    text: String,
    isError: Boolean,
) {
    Surface(
        color =
            if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun SourceActivityRow(activity: SourceActivity) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            ActivityDot(processed = activity.cropCount > 0)
            Column {
                Text(
                    activityTitle(activity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    activitySubtitle(activity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ActivityDot(processed: Boolean) {
    val color =
        if (processed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
    )
}

@Composable
private fun activityTitle(activity: SourceActivity): String =
    if (activity.cropCount > 0) {
        pluralStringResource(R.plurals.monitor_cards_extracted, activity.cropCount, activity.cropCount)
    } else {
        stringResource(R.string.monitor_awaiting_processing)
    }

@Composable
private fun activitySubtitle(activity: SourceActivity): String =
    listOfNotNull(
        formattedGalleryTimestamp(activity.uploadedAtUtc)?.let {
            stringResource(R.string.monitor_uploaded_at, it)
        },
        formattedGalleryTimestamp(activity.lastProcessedUtc)?.let {
            stringResource(R.string.monitor_processed_at, it)
        },
        stringResource(R.string.monitor_source_removed).takeIf { !activity.rawPresent },
    ).joinToString(" · ")
