package com.tradingcards.uploader.ui

import com.tradingcards.uploader.model.ScannerStatusResponse
import com.tradingcards.uploader.model.SourceActivity

data class MonitorUiState(
    val status: ScannerStatusResponse? = null,
    val activities: List<SourceActivity> = emptyList(),
    val loading: Boolean = false,
    val statusErrorText: String? = null,
    val activityErrorText: String? = null,
)
