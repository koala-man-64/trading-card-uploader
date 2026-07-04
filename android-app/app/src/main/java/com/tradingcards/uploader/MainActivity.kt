@file:Suppress("TooManyFunctions")

package com.tradingcards.uploader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tradingcards.uploader.auth.MsalAuthRepository
import com.tradingcards.uploader.data.GalleryRepository
import com.tradingcards.uploader.data.NetworkClients
import com.tradingcards.uploader.data.UploadRepository
import com.tradingcards.uploader.ui.CaptureScreen
import com.tradingcards.uploader.ui.CaptureScreenActions
import com.tradingcards.uploader.ui.GalleryScreen
import com.tradingcards.uploader.ui.theme.UploaderTheme
import com.tradingcards.uploader.viewmodel.CaptureViewModel
import com.tradingcards.uploader.viewmodel.GalleryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private object Routes {
    const val CAPTURE = "capture"
    const val GALLERY = "gallery"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = UploadRepository.database(this)
        val repository = UploadRepository(this, database.uploadQueueDao())
        val authRepository = MsalAuthRepository(this)
        val galleryRepository =
            GalleryRepository(BuildConfig.API_BASE_URL, client = NetworkClients.sasIssuerClient())

        setContent {
            UploaderApp(
                repository = repository,
                galleryRepository = galleryRepository,
                authRepository = authRepository,
            )
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun UploaderApp(
    repository: UploadRepository,
    galleryRepository: GalleryRepository,
    authRepository: MsalAuthRepository,
) {
    val navController = rememberNavController()

    UploaderTheme {
        Scaffold(
            bottomBar = { AppBottomBar(navController) },
        ) { contentPadding ->
            NavHost(navController = navController, startDestination = Routes.CAPTURE) {
                composable(Routes.CAPTURE) {
                    CaptureRoute(
                        modifier = Modifier.padding(contentPadding),
                        repository = repository,
                        authRepository = authRepository,
                    )
                }
                composable(Routes.GALLERY) {
                    GalleryRoute(
                        modifier = Modifier.padding(contentPadding),
                        galleryRepository = galleryRepository,
                        authRepository = authRepository,
                    )
                }
            }
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun AppBottomBar(navController: NavHostController) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.CAPTURE,
            onClick = { navController.navigateToTab(Routes.CAPTURE) },
            label = { Text(stringResource(R.string.nav_capture)) },
            icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.GALLERY,
            onClick = { navController.navigateToTab(Routes.GALLERY) },
            label = { Text(stringResource(R.string.nav_gallery)) },
            icon = { Icon(Icons.Default.Collections, contentDescription = null) },
        )
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")
@Composable
private fun CaptureRoute(
    modifier: Modifier,
    repository: UploadRepository,
    authRepository: MsalAuthRepository,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val viewModel: CaptureViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { CaptureViewModel(repository, authRepository) }
                },
        )
    val uploads by viewModel.uploads.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    var pendingCapture by remember { mutableStateOf<PendingCapture?>(null) }
    val takePictureLauncher =
        rememberTakePictureLauncher(
            pendingCapture = pendingCapture,
            clearPendingCapture = { pendingCapture = null },
            repository = repository,
            onQueued = viewModel::onCaptureQueued,
            onCancelled = viewModel::onCaptureCancelled,
        )
    val cameraPermissionLauncher =
        rememberCameraPermissionLauncher(
            context = context,
            takePictureLauncher = takePictureLauncher,
            setPendingCapture = { pendingCapture = it },
            onFailed = viewModel::onCaptureFailed,
            onPermissionDenied = viewModel::onCameraPermissionDenied,
        )
    val galleryPhotoLauncher =
        rememberGalleryPhotoLauncher(
            repository = repository,
            onQueued = viewModel::onGalleryPhotoQueued,
            onCancelled = viewModel::onGalleryPhotoCancelled,
            onFailed = viewModel::onGalleryPhotoFailed,
        )

    CaptureScreen(
        modifier = modifier,
        statusText = statusText,
        uploads = uploads,
        signedIn = signedIn,
        actions =
            CaptureScreenActions(
                onAuthenticate = { viewModel.authenticate(activity) },
                onCapture = {
                    if (hasCameraPermission(context)) {
                        launchCapture(
                            context = context,
                            takePictureLauncher = takePictureLauncher,
                            setPendingCapture = { pendingCapture = it },
                            onFailed = viewModel::onCaptureFailed,
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
                onRetry = { upload -> viewModel.retryUpload(upload.uploadId) },
                onRemove = { upload -> viewModel.removePendingUpload(upload.uploadId) },
            ),
    )
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun GalleryRoute(
    modifier: Modifier,
    galleryRepository: GalleryRepository,
    authRepository: MsalAuthRepository,
) {
    val activity = LocalContext.current as Activity
    val viewModel: GalleryViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { GalleryViewModel(galleryRepository, authRepository) }
                },
        )
    val state by viewModel.state.collectAsState()

    DisposableEffect(viewModel) {
        viewModel.onScreenEntered(activity)
        onDispose { viewModel.onScreenExited() }
    }

    GalleryScreen(
        modifier = modifier,
        state = state,
        repository = galleryRepository,
        onCategorySelected = { viewModel.onCategorySelected(activity, it) },
        onRefresh = { viewModel.onRefresh(activity) },
        onToggleSelected = viewModel::onToggleSelected,
        onClearSelection = viewModel::onClearSelection,
        onDeleteSelected = { viewModel.onDeleteSelected(activity) },
        onReprocessSelected = { viewModel.onReprocessSelected(activity) },
    )
}

@Composable
private fun rememberGalleryPhotoLauncher(
    repository: UploadRepository,
    onQueued: () -> Unit,
    onCancelled: () -> Unit,
    onFailed: (String) -> Unit,
): ActivityResultLauncher<PickVisualMediaRequest> {
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selectedUri ->
        if (selectedUri == null) {
            onCancelled()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching { repository.enqueueSelectedPhoto(selectedUri) }
                .onSuccess { onQueued() }
                .onFailure { error -> onFailed(error.message ?: "unable to queue photo") }
        }
    }
}

@Composable
private fun rememberTakePictureLauncher(
    pendingCapture: PendingCapture?,
    clearPendingCapture: () -> Unit,
    repository: UploadRepository,
    onQueued: () -> Unit,
    onCancelled: () -> Unit,
): ActivityResultLauncher<Uri> {
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val capture = pendingCapture
        clearPendingCapture()
        if (saved && capture != null) {
            enqueueCapture(capture, repository, scope, onQueued)
        } else {
            capture?.file?.delete()
            onCancelled()
        }
    }
}

private fun enqueueCapture(
    capture: PendingCapture,
    repository: UploadRepository,
    scope: CoroutineScope,
    onQueued: () -> Unit,
) {
    scope.launch {
        repository.enqueue(
            localUri = capture.uri.toString(),
            contentLengthBytes = capture.file.length(),
        )
        onQueued()
    }
}

@Composable
private fun rememberCameraPermissionLauncher(
    context: Context,
    takePictureLauncher: ActivityResultLauncher<Uri>,
    setPendingCapture: (PendingCapture) -> Unit,
    onFailed: (String) -> Unit,
    onPermissionDenied: () -> Unit,
): ActivityResultLauncher<String> =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCapture(
                context = context,
                takePictureLauncher = takePictureLauncher,
                setPendingCapture = setPendingCapture,
                onFailed = onFailed,
            )
        } else {
            onPermissionDenied()
        }
    }

private fun launchCapture(
    context: Context,
    takePictureLauncher: ActivityResultLauncher<Uri>,
    setPendingCapture: (PendingCapture) -> Unit,
    onFailed: (String) -> Unit,
) {
    runCatching { createPendingCapture(context) }
        .onSuccess { capture ->
            setPendingCapture(capture)
            takePictureLauncher.launch(capture.uri)
        }.onFailure { error ->
            onFailed(error.message ?: "unable to open camera")
        }
}

private fun createPendingCapture(context: Context): PendingCapture {
    val directory = File(context.filesDir, "captures").also { it.mkdirs() }
    val file = File.createTempFile("card-", ".jpg", directory)
    return runCatching {
        PendingCapture(
            file = file,
            uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    file,
                ),
        )
    }.getOrElse { error ->
        file.delete()
        throw error
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private data class PendingCapture(
    val file: File,
    val uri: Uri,
)
