package com.tradingcards.uploader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
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
import com.tradingcards.uploader.ui.GalleryScreen
import com.tradingcards.uploader.ui.GalleryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

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
        var pendingCapture by remember { mutableStateOf<PendingCapture?>(null) }

        fun loadGallery(category: GalleryCategory = galleryState.category) {
            galleryState =
                galleryState.copy(
                    category = category,
                    loading = true,
                    selectedNames = emptySet(),
                    statusText = "Loading ${category.wireValue} images",
                )
            scope.launch {
                runCatching {
                    val token = authRepository.acquireGalleryManageToken(this@MainActivity)
                    val loaded = loadGalleryWithRawFallback(token, category, galleryRepository)
                    token to loaded
                }.onSuccess { (token, loaded) ->
                    galleryState =
                        galleryState.copy(
                            category = loaded.selectedCategory,
                            items = loaded.response.items,
                            loading = false,
                            statusText = galleryStatusText(loaded),
                            accessToken = token,
                        )
                }.onFailure { error ->
                    galleryState =
                        galleryState.copy(
                            loading = false,
                            statusText = "Gallery load failed: ${error.message}",
                        )
                }
            }
        }

        fun selectedSourceNames(): List<String> = selectedGallerySourceNames(galleryState.items, galleryState.selectedNames)

        fun selectedIndividualDeleteImages(): List<GalleryImage> =
            selectedGalleryIndividualDeleteImages(galleryState.items, galleryState.selectedNames)

        fun runGalleryAction(
            sourceAction: suspend (String, String) -> Unit,
            imageAction: (suspend (String, GalleryImage) -> Unit)? = null,
        ) {
            val sourceNames = selectedSourceNames()
            val individualImages = imageAction?.let { selectedIndividualDeleteImages() }.orEmpty()
            if (sourceNames.isEmpty() && individualImages.isEmpty()) {
                galleryState = galleryState.copy(statusText = "Select raw or lineage-backed images")
                return
            }
            galleryState = galleryState.copy(loading = true, statusText = "Applying action")
            scope.launch {
                runCatching {
                    val token = authRepository.acquireGalleryManageToken(this@MainActivity)
                    for (sourceName in sourceNames) {
                        sourceAction(token, sourceName)
                    }
                    for (image in individualImages) {
                        imageAction?.invoke(token, image)
                    }
                    val loaded = loadGalleryWithRawFallback(token, galleryState.category, galleryRepository)
                    token to loaded
                }.onSuccess { (token, loaded) ->
                    galleryState =
                        galleryState.copy(
                            category = loaded.selectedCategory,
                            items = loaded.response.items,
                            selectedNames = emptySet(),
                            loading = false,
                            statusText = "Action complete",
                            accessToken = token,
                        )
                }.onFailure { error ->
                    galleryState =
                        galleryState.copy(
                            loading = false,
                            statusText = "Action failed: ${error.message}",
                        )
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

        LaunchedEffect(currentPage) {
            if (currentPage == AppPage.Gallery && galleryState.accessToken == null) {
                loadGallery()
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
                        )
                    AppPage.Gallery ->
                        GalleryScreen(
                            modifier = Modifier.padding(contentPadding),
                            state = galleryState,
                            repository = galleryRepository,
                            onCategorySelected = { loadGallery(it) },
                            onRefresh = { loadGallery() },
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
