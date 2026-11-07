package com.n8rtz.winetracker.ui.components

import android.content.Context
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

/** CameraX preview used only in the explicit, full-screen label scanner. */
@Composable
fun LabelScanCameraPreview(
    scannerViewModel: ScannerViewModel,
    onCameraError: (String) -> Unit,
    onCameraReadyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** When set, captured photos go here (e.g. confirm/retake hold) instead of starting a scan. */
    onPhotoCaptured: ((Long, File) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCameraError = rememberUpdatedState(onCameraError)
    val latestOnCameraReadyChanged = rememberUpdatedState(onCameraReadyChanged)
    val latestOnPhotoCaptured = rememberUpdatedState(onPhotoCaptured)
    val labelScanState by scannerViewModel.labelScanState.collectAsState()
    val bindingState = remember { mutableStateOf<CameraUseCaseBinding?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    // A delivered capture transfers pending-request ownership downstream (confirm hold or scan),
    // so unmounting for the still must not cancel it. Reset with each mount; retakes remount.
    // Read directly in onDispose: this subtree is removed (not recomposed) the same frame the
    // flag is set, so a rememberUpdatedState wrapper would still hold null at disposal time.
    var deliveredRequestId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(bindingState.value, cameraReady, labelScanState) {
        val captureRequest = labelScanState as? LabelScanState.CapturingPhoto
        if (cameraReady && captureRequest != null) {
            bindingState.value?.takePicture(captureRequest.requestId)
        }
    }

    DisposableEffect(lifecycleOwner, scannerViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) scannerViewModel.cancelPendingLabelCapture()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (deliveredRequestId == null) scannerViewModel.cancelPendingLabelCapture()
            bindingState.value?.close()
            cameraReady = false
            latestOnCameraReadyChanged.value(false)
        }
    }

    val capturedPhotoPath = (labelScanState as? LabelScanState.Processing)?.photoPath
    // Keep the camera visible until the still actually loads; otherwise a slow decode flashes black.
    var frameLoaded by remember(capturedPhotoPath) { mutableStateOf(false) }
    val frozenPhoto = remember(context, capturedPhotoPath) {
        capturedPhotoPath?.let { capturedLabelImageRequest(context, it) }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                val previewView = PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView permits the captured still to be composited above the camera.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(95)
                    .build()
                CameraUseCaseBinding(
                    context = viewContext.applicationContext,
                    preview = preview,
                    imageCapture = imageCapture,
                    onPhotoCaptured = { requestId, photoFile ->
                        deliveredRequestId = requestId
                        latestOnPhotoCaptured.value?.invoke(requestId, photoFile)
                            ?: scannerViewModel.onLabelPhotoCaptured(requestId, photoFile)
                    },
                    onCaptureFailed = scannerViewModel::onLabelCaptureFailed,
                    onCameraError = { latestOnCameraError.value(it) },
                    onCameraReadyChanged = { ready ->
                        cameraReady = ready
                        latestOnCameraReadyChanged.value(ready)
                    }
                ).also { binding ->
                    bindingState.value = binding
                    binding.bind(viewContext, lifecycleOwner)
                }
                previewView
            },
            update = { previewView ->
                previewView.visibility =
                    if (capturedPhotoPath != null && frameLoaded) View.INVISIBLE else View.VISIBLE
            },
            modifier = Modifier.fillMaxSize()
        )

        if (frozenPhoto != null) {
            AsyncImage(
                model = frozenPhoto,
                contentDescription = "Captured wine label photo",
                contentScale = ContentScale.Crop,
                onSuccess = { frameLoaded = true },
                onError = { frameLoaded = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private class CameraUseCaseBinding(
    private val context: Context,
    private val preview: Preview,
    private val imageCapture: ImageCapture,
    private val onPhotoCaptured: (Long, File) -> Unit,
    private val onCaptureFailed: (Long, Throwable) -> Unit,
    private val onCameraError: (String) -> Unit,
    private val onCameraReadyChanged: (Boolean) -> Unit
) {
    @Volatile
    private var closed = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraReady = false
    private var captureInProgress = false
    private var lastCaptureRequestId: Long? = null

    fun bind(viewContext: Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(viewContext)
        providerFuture.addListener({
            if (closed) return@addListener
            try {
                val provider = providerFuture.get()
                if (closed) return@addListener
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                cameraProvider = provider
                cameraReady = true
                onCameraReadyChanged(true)
            } catch (_: Exception) {
                if (!closed) {
                    onCameraReadyChanged(false)
                    onCameraError("Camera could not be started. You can still enter the wine manually.")
                }
            }
        }, ContextCompat.getMainExecutor(viewContext))
    }

    fun takePicture(requestId: Long) {
        if (closed || lastCaptureRequestId == requestId) return
        if (!cameraReady || captureInProgress) {
            onCaptureFailed(requestId, IllegalStateException("Camera is not ready to take a photo"))
            return
        }

        lastCaptureRequestId = requestId
        captureInProgress = true
        val photoFile = try {
            File.createTempFile("wine-label-", ".jpg", context.cacheDir)
        } catch (error: Exception) {
            captureInProgress = false
            onCaptureFailed(requestId, error)
            return
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        try {
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        captureInProgress = false
                        if (closed) photoFile.delete() else onPhotoCaptured(requestId, photoFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        captureInProgress = false
                        photoFile.delete()
                        onCaptureFailed(requestId, exception)
                    }
                }
            )
        } catch (error: Exception) {
            captureInProgress = false
            photoFile.delete()
            onCaptureFailed(requestId, error)
        }
    }

    fun close() {
        closed = true
        cameraReady = false
        onCameraReadyChanged(false)
        cameraProvider?.let { provider ->
            runCatching { provider.unbind(preview, imageCapture) }
        }
        cameraProvider = null
    }
}

/** A display-only, bounded thumbnail; neither image cache retains a private label photo. */
internal fun capturedLabelImageRequest(context: Context, photoPath: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(File(photoPath))
        .size(1024)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
