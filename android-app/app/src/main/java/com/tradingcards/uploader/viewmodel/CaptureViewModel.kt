package com.tradingcards.uploader.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.UploadRepository
import com.tradingcards.uploader.model.UploadEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val UPLOADS_STOP_TIMEOUT_MS = 5_000L

class CaptureViewModel(
    private val repository: UploadRepository,
    private val authRepository: MsalAuthRepository,
) : ViewModel() {
    val uploads: StateFlow<List<UploadEntity>> =
        repository.recentUploads().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(UPLOADS_STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    init {
        viewModelScope.launch {
            runCatching { authRepository.acquireUploadTokenSilent() }.onSuccess { _signedIn.value = true }
        }
    }

    fun authenticate(activity: Activity) {
        viewModelScope.launch {
            runCatching { authRepository.acquireUploadToken(activity) }
                .onSuccess {
                    _signedIn.value = true
                    _statusText.value = ""
                }.onFailure { _statusText.value = "Sign-in failed: ${it.message ?: "unknown error"}" }
        }
    }

    fun retryUpload(uploadId: String) {
        viewModelScope.launch {
            _statusText.value =
                if (repository.retry(uploadId)) {
                    "Retrying upload"
                } else {
                    "Nothing to retry: upload already in progress or complete"
                }
        }
    }

    fun onCaptureQueued() {
        _statusText.value = ""
    }

    fun onCaptureCancelled() {
        _statusText.value = ""
    }

    fun onCaptureFailed(message: String) {
        _statusText.value = "Capture failed: $message"
    }

    fun onCameraPermissionDenied() {
        _statusText.value = "Camera permission is required to capture a card photo"
    }

    fun onGalleryPhotoQueued() {
        _statusText.value = ""
    }

    fun onGalleryPhotoCancelled() {
        _statusText.value = ""
    }

    fun onGalleryPhotoFailed(message: String) {
        _statusText.value = "Couldn't add photo: $message"
    }
}
