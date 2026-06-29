package com.tradingcards.uploader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.data.ScannerNotConfiguredException
import com.tradingcards.uploader.data.UploadQueueDao
import com.tradingcards.uploader.data.UploadRepository
import com.tradingcards.uploader.model.GalleryCategory
import com.tradingcards.uploader.model.GalleryImage
import com.tradingcards.uploader.model.GalleryImagesResponse
import com.tradingcards.uploader.model.selectedGalleryIndividualDeleteImages
import com.tradingcards.uploader.model.selectedGallerySourceNames
import com.tradingcards.uploader.ui.CaptureScreen
import com.tradingcards.uploader.ui.CaptureScreenActions
import com.tradingcards.uploader.ui.GalleryScreen
import com.tradingcards.uploader.ui.GalleryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val GALLERY_POLL_INTERVAL_MS = 5_000L
private const val GALLERY_POLL_FAILURE_BACKOFF_MS = 30_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = UploadRepository.database(this)
        val repository = UploadRepository(this, database.uploadQueueDao())
        val authRepository = MsalAuthRepository(this)
        val galleryRepository = GalleryRepository(BuildConfig.API_BASE_URL)

        setContent {
            uploaderApp(
                uploadQueueDao = database.uploadQueueDao(),
                repository = repository,
                galleryRepository = galleryRepository,
                authRepository = authRepository,
            )
        }
    }

    @Composable
    @Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength")
    private fun uploaderApp(
        uploadQueueDao: UploadQueueDao,
        repository: UploadRepository,
        galleryRepository: GalleryRepository,
        authRepository: MsalAuthRepository,
    ) {
        val scope = rememberCoroutineScope()
        val latestUpload by uploadQueueDao.latest().collectAsState(initial = null)
        var statusText by remember { mutableStateOf("Ready") }
        var currentPage by remember { mutableStateOf(AppPage.Capture) }
        var galleryState by remember { mutableStateOf(GalleryUiState()) }
        var galleryRefreshInFlight by remember { mutableStateOf(false) }
        var pendingCapture by remember { mutableStateOf<PendingCapture?>(null) }

        suspend fun refreshGallery(
            category: GalleryCategory = galleryState.category,
            reason: GalleryRefreshReason,
        ): Boolean {
            if (galleryRefreshInFlight) {
                return true
            }
            galleryRefreshInFlight = true
            galleryState = galleryStateForRefreshStart(galleryState, category, reason)
            return try {
                runCatching {
                    val token =
                        if (reason == GalleryRefreshReason.Poll) {
                            authRepository.acquireGalleryManageTokenSilent()
                        } else {
                            authRepository.acquireGalleryManageToken(this@MainActivity)
                        }
                    val loaded = loadGalleryWithRawFallback(token, category, galleryRepository)
                    galleryState = galleryStateForRefreshSuccess(galleryState, token, loaded, reason)
                }.fold(
                    onSuccess = { true },
                    onFailure = { error ->
                        throwIfCancellation(error)
                        galleryState = galleryStateForRefreshFailure(galleryState, reason, error.message)
                        false
                    },
                )
            } finally {
                galleryRefreshInFlight = false
            }
        }

        fun launchGalleryRefresh(
            category: GalleryCategory = galleryState.category,
            reason: GalleryRefreshReason,
        ) {
            scope.launch {
                refreshGallery(category, reason)
            }
        }

        fun selectedSourceNames(): List<String> = selectedGallerySourceNames(galleryState.items, galleryState.selectedNames)

        fun selectedIndividualDeleteImages(): List<GalleryImage> =
            selectedGalleryIndividualDeleteImages(galleryState.items, galleryState.selectedNames)

        fun runGalleryAction(
            sourceAction: suspend (String, String) -> Unit,
            imageAction: (suspend (String, GalleryImage) -> Unit)? = null,
        ) {
            if (galleryRefreshInFlight) {
                return
            }
            val sourceNames = selectedSourceNames()
            val individualImages = imageAction?.let { selectedIndividualDeleteImages() }.orEmpty()
            if (sourceNames.isEmpty() && individualImages.isEmpty()) {
                galleryState = galleryState.copy(statusText = "Select raw or lineage-backed images")
                return
            }
            scope.launch {
                galleryRefreshInFlight = true
                try {
                    galleryState =
                        galleryStateForRefreshStart(
                            galleryState,
                            galleryState.category,
                            GalleryRefreshReason.Action,
                        )
                    runCatching {
                        val token = authRepository.acquireGalleryManageToken(this@MainActivity)
                        for (sourceName in sourceNames) {
                            sourceAction(token, sourceName)
                        }
                        for (image in individualImages) {
                            imageAction?.invoke(token, image)
                        }
                        val loaded = loadGalleryWithRawFallback(token, galleryState.category, galleryRepository)
                        galleryState =
                            galleryStateForRefreshSuccess(
                                galleryState,
                                token,
                                loaded,
                                GalleryRefreshReason.Action,
                            )
                    }.onFailure { error ->
                        throwIfCancellation(error)
                        galleryState =
                            galleryStateForRefreshFailure(
                                galleryState,
                                GalleryRefreshReason.Action,
                                error.message,
                            )
                    }
                } finally {
                    galleryRefreshInFlight = false
                }
            }
        }
        val takePictureLauncher =
            rememberTakePictureLauncher(
                pendingCapture = pendingCapture,
                clearPendingCapture = { pendingCapture = null },
                repository = repository,
                scope = scope,
                onStatusTextChanged = { statusText = it },
            )
        val cameraPermissionLauncher =
            rememberCameraPermissionLauncher(
                takePictureLauncher = takePictureLauncher,
                setPendingCapture = { pendingCapture = it },
                onStatusTextChanged = { statusText = it },
            )
        val galleryPhotoLauncher =
            rememberGalleryPhotoLauncher(
                repository = repository,
                scope = scope,
                onStatusTextChanged = { statusText = it },
            )

        LaunchedEffect(currentPage) {
            if (currentPage == AppPage.Gallery && galleryState.accessToken == null) {
                refreshGallery(reason = GalleryRefreshReason.Initial)
            }
        }

        LaunchedEffect(currentPage, galleryState.category, galleryState.accessToken) {
            if (currentPage != AppPage.Gallery || galleryState.accessToken == null) {
                return@LaunchedEffect
            }
            var nextDelayMs = GALLERY_POLL_INTERVAL_MS
            while (true) {
                delay(nextDelayMs)
                if (galleryState.loading || galleryRefreshInFlight) {
                    nextDelayMs = GALLERY_POLL_INTERVAL_MS
                    continue
                }
                val refreshed =
                    refreshGallery(
                        category = galleryState.category,
                        reason = GalleryRefreshReason.Poll,
                    )
                nextDelayMs =
                    if (refreshed) {
                        GALLERY_POLL_INTERVAL_MS
                    } else {
                        GALLERY_POLL_FAILURE_BACKOFF_MS
                    }
            }
        }

        MaterialTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentPage == AppPage.Capture,
                            onClick = { currentPage = AppPage.Capture },
                            label = { Text("Capture") },
                            icon = {},
                        )
                        NavigationBarItem(
                            selected = currentPage == AppPage.Gallery,
                            onClick = { currentPage = AppPage.Gallery },
                            label = { Text("Gallery") },
                            icon = {},
                        )
                    }
                },
            ) { contentPadding ->
                when (currentPage) {
                    AppPage.Capture ->
                        CaptureScreen(
                            modifier = Modifier.padding(contentPadding),
                            statusText = statusText,
                            latestUpload = latestUpload,
                            actions =
                                CaptureScreenActions(
                                    onAuthenticate = {
                                        authenticateForUpload(
                                            scope = scope,
                                            authRepository = authRepository,
                                            onStatusTextChanged = { statusText = it },
                                        )
                                    },
                                    onCapture = {
                                        if (hasCameraPermission()) {
                                            launchCapture(
                                                takePictureLauncher = takePictureLauncher,
                                                setPendingCapture = { pendingCapture = it },
                                                onStatusTextChanged = { statusText = it },
                                            )
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    onSelectPhoto = {
                                        galleryPhotoLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                ),
                        )
                    AppPage.Gallery ->
                        GalleryScreen(
                            modifier = Modifier.padding(contentPadding),
                            state = galleryState,
                            repository = galleryRepository,
                            onCategorySelected = { launchGalleryRefresh(it, GalleryRefreshReason.Category) },
                            onRefresh = { launchGalleryRefresh(reason = GalleryRefreshReason.Manual) },
                            onToggleSelected = { image -> galleryState = toggleSelection(galleryState, image) },
                            onDeleteSelected = {
                                runGalleryAction(
                                    sourceAction = { token, sourceName ->
                                        galleryRepository.deleteSourceGroup(token, sourceName)
                                    },
                                    imageAction = { token, image ->
                                        galleryRepository.deleteImage(token, image)
                                    },
                                )
                            },
                            onReprocessSelected = {
                                runGalleryAction(
                                    sourceAction = { token, sourceName ->
                                        galleryRepository.reprocessSource(token, sourceName)
                                    },
                                )
                            },
                        )
                }
            }
        }
    }

    @Composable
    private fun rememberGalleryPhotoLauncher(
        repository: UploadRepository,
        scope: CoroutineScope,
        onStatusTextChanged: (String) -> Unit,
    ): ActivityResultLauncher<PickVisualMediaRequest> =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selectedUri ->
            if (selectedUri == null) {
                onStatusTextChanged("Gallery selection cancelled")
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                runCatching { repository.enqueueSelectedPhoto(selectedUri) }
                    .onSuccess { uploadId -> onStatusTextChanged("Gallery photo queued: $uploadId") }
                    .onFailure { error ->
                        onStatusTextChanged("Gallery photo failed: ${error.message ?: "unable to queue photo"}")
                    }
            }
        }

    @Composable
    private fun rememberTakePictureLauncher(
        pendingCapture: PendingCapture?,
        clearPendingCapture: () -> Unit,
        repository: UploadRepository,
        scope: CoroutineScope,
        onStatusTextChanged: (String) -> Unit,
    ): ActivityResultLauncher<Uri> =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val capture = pendingCapture
            clearPendingCapture()
            if (saved && capture != null) {
                scope.launch {
                    val uploadId =
                        repository.enqueue(
                            localUri = capture.uri.toString(),
                            contentLengthBytes = capture.file.length(),
                        )
                    onStatusTextChanged("Upload queued: $uploadId")
                }
            } else {
                capture?.file?.delete()
                onStatusTextChanged("Capture cancelled")
            }
        }

    @Composable
    private fun rememberCameraPermissionLauncher(
        takePictureLauncher: ActivityResultLauncher<Uri>,
        setPendingCapture: (PendingCapture) -> Unit,
        onStatusTextChanged: (String) -> Unit,
    ): ActivityResultLauncher<String> =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCapture(
                    takePictureLauncher = takePictureLauncher,
                    setPendingCapture = setPendingCapture,
                    onStatusTextChanged = onStatusTextChanged,
                )
            } else {
                onStatusTextChanged("Camera permission is required to capture a card photo")
            }
        }

    private fun authenticateForUpload(
        scope: CoroutineScope,
        authRepository: MsalAuthRepository,
        onStatusTextChanged: (String) -> Unit,
    ) {
        scope.launch {
            runCatching { authRepository.acquireUploadToken(this@MainActivity) }
                .onSuccess { onStatusTextChanged("Authenticated") }
                .onFailure { onStatusTextChanged("Authentication failed: ${it.message}") }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchCapture(
        takePictureLauncher: ActivityResultLauncher<Uri>,
        setPendingCapture: (PendingCapture) -> Unit,
        onStatusTextChanged: (String) -> Unit,
    ) {
        runCatching { createPendingCapture() }
            .onSuccess { capture ->
                setPendingCapture(capture)
                takePictureLauncher.launch(capture.uri)
            }.onFailure { error ->
                onStatusTextChanged("Capture failed: ${error.message ?: "unable to open camera"}")
            }
    }

    private fun createPendingCapture(): PendingCapture {
        val directory = File(filesDir, "captures").also { it.mkdirs() }
        val file = File.createTempFile("card-", ".jpg", directory)
        return runCatching {
            PendingCapture(
                file = file,
                uri =
                    FileProvider.getUriForFile(
                        this,
                        "$packageName.files",
                        file,
                    ),
            )
        }.getOrElse { error ->
            file.delete()
            throw error
        }
    }

    private data class PendingCapture(
        val file: File,
        val uri: Uri,
    )

    private enum class AppPage {
        Capture,
        Gallery,
    }

    private fun toggleSelection(
        state: GalleryUiState,
        image: GalleryImage,
    ): GalleryUiState {
        val selected =
            if (image.name in state.selectedNames) {
                state.selectedNames - image.name
            } else {
                state.selectedNames + image.name
            }
        return state.copy(selectedNames = selected)
    }
}

internal enum class GalleryRefreshReason {
    Initial,
    Category,
    Manual,
    Action,
    Poll,
}

internal data class LoadedGallery(
    val selectedCategory: GalleryCategory,
    val response: GalleryImagesResponse,
    val scannerFallback: Boolean,
)

internal suspend fun loadGalleryWithRawFallback(
    token: String,
    category: GalleryCategory,
    repository: GalleryRepository,
): LoadedGallery =
    try {
        LoadedGallery(
            selectedCategory = category,
            response = repository.list(token, category),
            scannerFallback = false,
        )
    } catch (error: ScannerNotConfiguredException) {
        if (category == GalleryCategory.Raw) {
            throw error
        }
        LoadedGallery(
            selectedCategory = category,
            response = repository.list(token, GalleryCategory.Raw),
            scannerFallback = true,
        )
    }

internal fun galleryStateForRefreshStart(
    state: GalleryUiState,
    category: GalleryCategory,
    reason: GalleryRefreshReason,
): GalleryUiState =
    when (reason) {
        GalleryRefreshReason.Poll -> state
        GalleryRefreshReason.Action ->
            state.copy(
                loading = true,
                statusText = "Applying action",
            )
        GalleryRefreshReason.Initial,
        GalleryRefreshReason.Category,
        GalleryRefreshReason.Manual,
        ->
            state.copy(
                category = category,
                loading = true,
                selectedNames = emptySet(),
                statusText = "Loading ${category.wireValue} images",
            )
    }

internal fun galleryStateForRefreshSuccess(
    state: GalleryUiState,
    token: String,
    loaded: LoadedGallery,
    reason: GalleryRefreshReason,
): GalleryUiState {
    val loadedNames = loaded.response.items.map { it.name }.toSet()
    val selectedNames =
        if (reason == GalleryRefreshReason.Poll) {
            state.selectedNames.intersect(loadedNames)
        } else {
            emptySet()
        }
    val statusText =
        if (reason == GalleryRefreshReason.Action) {
            "Action complete"
        } else {
            galleryStatusText(loaded)
        }
    return state.copy(
        category = loaded.selectedCategory,
        items = loaded.response.items,
        selectedNames = selectedNames,
        loading = false,
        statusText = statusText,
        accessToken = token,
    )
}

internal fun galleryStateForRefreshFailure(
    state: GalleryUiState,
    reason: GalleryRefreshReason,
    message: String?,
): GalleryUiState =
    when (reason) {
        GalleryRefreshReason.Poll -> state
        GalleryRefreshReason.Action ->
            state.copy(
                loading = false,
                statusText = "Action failed: ${message ?: "unknown error"}",
            )
        GalleryRefreshReason.Initial,
        GalleryRefreshReason.Category,
        GalleryRefreshReason.Manual,
        ->
            state.copy(
                loading = false,
                statusText = "Gallery load failed: ${message ?: "unknown error"}",
            )
    }

private fun throwIfCancellation(error: Throwable) {
    if (error is CancellationException) {
        throw error
    }
}

private fun galleryStatusText(loaded: LoadedGallery): String =
    galleryStatusText(
        itemCount = loaded.response.items.size,
        scannerFallback = loaded.scannerFallback,
    )

internal fun galleryStatusText(
    itemCount: Int,
    scannerFallback: Boolean,
): String =
    if (scannerFallback) {
        "Showing $itemCount raw image(s)"
    } else {
        "$itemCount image(s)"
    }
