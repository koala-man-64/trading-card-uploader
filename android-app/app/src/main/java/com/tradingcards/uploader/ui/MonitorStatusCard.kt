package com.tradingcards.uploader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tradingcards.uploader.R
import com.tradingcards.uploader.model.ScannerHealth
import com.tradingcards.uploader.model.ScannerStatusResponse
import com.tradingcards.uploader.model.componentSummaries
import com.tradingcards.uploader.model.health
import java.util.Locale

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun ScannerStatusCard(
    status: ScannerStatusResponse?,
    errorText: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.monitor_status_heading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when {
                errorText != null -> MonitorStatusMessage(errorText, isError = true)
                status == null -> MonitorStatusMessage(stringResource(R.string.gallery_loading), isError = false)
                else -> ScannerStatusDetails(status)
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun MonitorStatusMessage(
    text: String,
    isError: Boolean,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color =
            if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ScannerStatusDetails(status: ScannerStatusResponse) {
    val health = status.health()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HealthDot(health)
        Text(
            healthLabel(health),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        status.statusCode?.let { code ->
            Text(
                stringResource(R.string.monitor_http_status, code),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    status.componentSummaries().forEach { component ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                componentDisplayName(component.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                component.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun HealthDot(health: ScannerHealth) {
    val color =
        when (health) {
            ScannerHealth.Ready -> MaterialTheme.colorScheme.primary
            ScannerHealth.WarmingUp -> MaterialTheme.colorScheme.tertiary
            ScannerHealth.NotConfigured -> MaterialTheme.colorScheme.outline
            ScannerHealth.Unreachable, ScannerHealth.NotReady -> MaterialTheme.colorScheme.error
        }
    Box(
        modifier =
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
    )
}

@Composable
private fun healthLabel(health: ScannerHealth): String =
    when (health) {
        ScannerHealth.Ready -> stringResource(R.string.monitor_health_ready)
        ScannerHealth.WarmingUp -> stringResource(R.string.monitor_health_warming)
        ScannerHealth.NotReady -> stringResource(R.string.monitor_health_not_ready)
        ScannerHealth.Unreachable -> stringResource(R.string.monitor_health_unreachable)
        ScannerHealth.NotConfigured -> stringResource(R.string.monitor_health_not_configured)
    }

private fun componentDisplayName(name: String): String =
    when (name) {
        "ocr" -> "OCR"
        else -> name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
