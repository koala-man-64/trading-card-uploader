package com.tradingcards.uploader.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingcards.uploader.GalleryRefreshReason
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.galleryStateForRefreshFailure
import com.tradingcards.uploader.galleryStateForRefreshStart
import com.tradingcards.uploader.galleryStateForRefreshSuccess
import com.tradingcards.uploader.loadGallery
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.selectedGalleryIndividualDeleteImages
import com.tradingcards.uploader.model.selectedGallerySourceNames
import com.tradingcards.uploader.throwIfCancellation
import com.tradingcards.uploader.ui.GalleryUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val GALLERY_POLL_INTERVAL_MS = 5_000L
private const val GALLERY_POLL_FAILURE_BACKOFF_MS = 30_000L

@Suppress("TooManyFunctions")
class GalleryViewModel(
    private val repository: GalleryRepository,
    private val authRepository: MsalAuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state

    private var refreshInFlight = false
    private var pollingJob: Job? = null

    /** Call when the gallery screen becomes visible; loads data (interactively, if needed) and (re)starts polling. */
    fun onScreenEntered(activity: Activity) {
        if (_state.value.accessToken == null) {
            launchRefresh(activity = activity, reason = GalleryRefreshReason.Initial)
        }
        startPolling()
    }

    /** Call when the gallery screen is no longer visible, to stop the background poll loop. */
    fun onScreenExited() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun onCategorySelected(
        activity: Activity,
        category: GalleryCategory,
    ) {
        launchRefresh(activity, category, GalleryRefreshReason.Category)
    }

    fun onRefresh(activity: Activity) {
        launchRefresh(activity = activity, reason = GalleryRefreshReason.Manual)
    }

    fun onToggleSelected(image: GalleryImage) {
        val current = _state.value
        val selected =
            if (image.name in current.selectedNames) {
                current.selectedNames - image.name
            } else {
                current.selectedNames + image.name
            }
        _state.value = current.copy(selectedNames = selected)
    }

    fun onClearSelection() {
        _state.value = _state.value.copy(selectedNames = emptySet())
    }

    fun onDeleteSelected(activity: Activity) {
        runAction(
            activity = activity,
            sourceAction = { token, sourceName -> repository.deleteSourceGroup(token, sourceName) },
            imageAction = { token, image -> repository.deleteImage(token, image) },
        )
    }

    fun onReprocessSelected(activity: Activity) {
        runAction(
            activity = activity,
            sourceAction = { token, sourceName -> repository.reprocessSource(token, sourceName) },
        )
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob =
            viewModelScope.launch {
                var nextDelayMs = GALLERY_POLL_INTERVAL_MS
                while (true) {
                    delay(nextDelayMs)
                    if (_state.value.loading || refreshInFlight) {
                        nextDelayMs = GALLERY_POLL_INTERVAL_MS
                        continue
                    }
                    val refreshed = refreshGallery(_state.value.category, GalleryRefreshReason.Poll, activity = null)
                    nextDelayMs = if (refreshed) GALLERY_POLL_INTERVAL_MS else GALLERY_POLL_FAILURE_BACKOFF_MS
                }
            }
    }

    private fun launchRefresh(
        activity: Activity,
        category: GalleryCategory = _state.value.category,
        reason: GalleryRefreshReason,
    ) {
        viewModelScope.launch { refreshGallery(category, reason, activity) }
    }

    private suspend fun refreshGallery(
        category: GalleryCategory,
        reason: GalleryRefreshReason,
        activity: Activity?,
    ): Boolean {
        if (refreshInFlight) return true
        refreshInFlight = true
        _state.value = galleryStateForRefreshStart(_state.value, category, reason)
        return try {
            runCatching {
                val token =
                    if (reason == GalleryRefreshReason.Poll) {
                        authRepository.acquireGalleryManageTokenSilent()
                    } else {
                        authRepository.acquireGalleryManageToken(requireNotNull(activity))
                    }
                val loaded = loadGallery(token, category, repository)
                _state.value = galleryStateForRefreshSuccess(_state.value, token, loaded, reason)
            }.fold(
                onSuccess = { true },
                onFailure = { error ->
                    throwIfCancellation(error)
                    _state.value = galleryStateForRefreshFailure(_state.value, reason, error.message)
                    false
                },
            )
        } finally {
            refreshInFlight = false
        }
    }

    private fun runAction(
        activity: Activity,
        sourceAction: suspend (String, String) -> Unit,
        imageAction: (suspend (String, GalleryImage) -> Unit)? = null,
    ) {
        if (refreshInFlight) return
        val current = _state.value
        val sourceNames = selectedGallerySourceNames(current.items, current.selectedNames)
        val individualImages =
            imageAction?.let { selectedGalleryIndividualDeleteImages(current.items, current.selectedNames) }.orEmpty()
        if (sourceNames.isEmpty() && individualImages.isEmpty()) {
            _state.value = current.copy(errorText = "The selected images don't support this action")
            return
        }
        viewModelScope.launch {
            refreshInFlight = true
            try {
                _state.value =
                    galleryStateForRefreshStart(_state.value, _state.value.category, GalleryRefreshReason.Action)
                runCatching {
                    val token = authRepository.acquireGalleryManageToken(activity)
                    for (sourceName in sourceNames) {
                        sourceAction(token, sourceName)
                    }
                    for (image in individualImages) {
                        imageAction?.invoke(token, image)
                    }
                    val loaded = loadGallery(token, _state.value.category, repository)
                    _state.value =
                        galleryStateForRefreshSuccess(_state.value, token, loaded, GalleryRefreshReason.Action)
                }.onFailure { error ->
                    throwIfCancellation(error)
                    _state.value =
                        galleryStateForRefreshFailure(_state.value, GalleryRefreshReason.Action, error.message)
                }
            } finally {
                refreshInFlight = false
            }
        }
    }
}
