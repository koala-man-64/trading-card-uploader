package com.tradingcards.uploader.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.loadGallery
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.SourceActivity
import com.tradingcards.uploader.model.buildSourceActivities
import com.tradingcards.uploader.throwIfCancellation
import com.tradingcards.uploader.ui.MonitorUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val MONITOR_POLL_INTERVAL_MS = 10_000L
private const val MONITOR_POLL_FAILURE_BACKOFF_MS = 30_000L

// How many blobs per category to pull into the activity join. Bounds the
// number of paged listing calls while still covering recent history.
private const val ACTIVITY_SCAN_DEPTH = 200

class MonitorViewModel(
    private val repository: GalleryRepository,
    private val authRepository: MsalAuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state

    private var refreshInFlight = false
    private var pollingJob: Job? = null

    /** Call when the monitor screen becomes visible; loads data and (re)starts polling. */
    fun onScreenEntered(activity: Activity) {
        if (_state.value.status == null && !refreshInFlight) {
            launchRefresh(activity)
        }
        startPolling()
    }

    /** Call when the monitor screen is no longer visible, to stop the background poll loop. */
    fun onScreenExited() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun onRefresh(activity: Activity) {
        launchRefresh(activity)
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob =
            viewModelScope.launch {
                var nextDelayMs = MONITOR_POLL_INTERVAL_MS
                while (true) {
                    delay(nextDelayMs)
                    if (refreshInFlight) {
                        nextDelayMs = MONITOR_POLL_INTERVAL_MS
                        continue
                    }
                    val refreshed = refresh(activity = null)
                    nextDelayMs = if (refreshed) MONITOR_POLL_INTERVAL_MS else MONITOR_POLL_FAILURE_BACKOFF_MS
                }
            }
    }

    private fun launchRefresh(activity: Activity) {
        viewModelScope.launch { refresh(activity) }
    }

    /**
     * Refreshes both monitor sections independently so a scanner outage still
     * shows pipeline activity and vice versa — a monitor that goes fully
     * blank on partial failure would hide exactly what it exists to show.
     */
    private suspend fun refresh(activity: Activity?): Boolean {
        if (refreshInFlight) return true
        refreshInFlight = true
        _state.value = _state.value.copy(loading = true)
        return try {
            val token = acquireToken(activity)
            val refreshed = token != null && refreshStatus(token) and refreshActivities(token)
            _state.value = _state.value.copy(loading = false)
            refreshed
        } finally {
            refreshInFlight = false
        }
    }

    private suspend fun acquireToken(activity: Activity?): String? =
        runCatching {
            if (activity == null) {
                authRepository.acquireGalleryManageTokenSilent()
            } else {
                authRepository.acquireGalleryManageToken(activity)
            }
        }.getOrElse { error ->
            throwIfCancellation(error)
            _state.value =
                _state.value.copy(
                    statusErrorText = "Couldn't sign in: ${error.message ?: "unknown error"}",
                )
            null
        }

    private suspend fun refreshStatus(token: String): Boolean =
        runCatching { repository.scannerStatus(token) }
            .fold(
                onSuccess = { status ->
                    _state.value = _state.value.copy(status = status, statusErrorText = null)
                    true
                },
                onFailure = { error ->
                    throwIfCancellation(error)
                    _state.value =
                        _state.value.copy(
                            statusErrorText = "Couldn't load scanner status: ${error.message ?: "unknown error"}",
                        )
                    false
                },
            )

    private suspend fun refreshActivities(token: String): Boolean =
        runCatching { loadActivities(token) }
            .fold(
                onSuccess = { activities ->
                    _state.value = _state.value.copy(activities = activities, activityErrorText = null)
                    true
                },
                onFailure = { error ->
                    throwIfCancellation(error)
                    _state.value =
                        _state.value.copy(
                            activityErrorText = "Couldn't load activity: ${error.message ?: "unknown error"}",
                        )
                    false
                },
            )

    private suspend fun loadActivities(token: String): List<SourceActivity> {
        val raw = loadGallery(token, GalleryCategory.Raw, repository, minItems = ACTIVITY_SCAN_DEPTH)
        // Without a configured scanner the processed listing can only fail;
        // raw uploads alone still make pending work visible.
        val processed =
            if (_state.value.status?.configured == false) {
                emptyList()
            } else {
                loadGallery(token, GalleryCategory.Processed, repository, minItems = ACTIVITY_SCAN_DEPTH).items
            }
        return buildSourceActivities(raw.items, processed)
    }
}
