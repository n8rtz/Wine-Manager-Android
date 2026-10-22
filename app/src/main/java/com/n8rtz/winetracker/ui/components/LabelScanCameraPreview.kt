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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import java.io.File

/** CameraX preview used only in the explicit, full-screen label scanner. */
@Composable
fun LabelScanCameraPreview(
    scannerViewModel: ScannerViewModel,
    onCameraError: (String) -> Unit,
    onCameraReadyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCameraError = rememberUpdatedState(onCameraError)
    val latestOnCameraReadyChanged = rememberUpdatedState(onCameraReadyChanged)
    val labelScanState by scannerViewModel.labelScanState.collectAsState()
    val bindingState = remember { mutableStateOf<CameraUseCaseBinding?>(null) }
    var cameraReady by remember { mutableStateOf(false) }

    LaunchedEffect(bindingState.value, cameraReady, labelScanState) {
        val captureRequest = labelScanState as? LabelScanState.CapturingPhoto
        if (cameraReady && captureRequest != null) {
            bindingState.value?.takePicture(captureRequest.requestId)
        }
    }

    DisposableEffect(lifecycleOwner, scannerViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) scannerViewModel.cancelActiveLabelScan()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scannerViewModel.cancelActiveLabelScan()
            bindingState.value?.close()
            cameraReady = false
            latestOnCameraReadyChanged.value(false)
        }
    }

    val capturedPhotoPath = (labelScanState as? LabelScanState.Processing)?.photoPath

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                val previewView = PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
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
                    onPhotoCaptured = scannerViewModel::onLabelPhotoCaptured,
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
                    if (capturedPhotoPath == null) View.VISIBLE else View.INVISIBLE
            },
            modifier = Modifier.fillMaxSize()
        )

        // Do not decode the JPEG again with Coil while OCR owns its two working bitmaps.
        // The live preview is a CameraX surface, not a working Bitmap.
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
