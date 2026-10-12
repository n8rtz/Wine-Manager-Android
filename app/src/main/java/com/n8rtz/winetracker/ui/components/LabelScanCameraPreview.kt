package com.n8rtz.winetracker.ui.components

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CameraX preview plus a YUV ImageAnalysis stream; OCR itself is requested one frame at a time. */
@Composable
fun LabelScanCameraPreview(
    scannerViewModel: ScannerViewModel,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCameraError = rememberUpdatedState(onCameraError)
    val labelScanState by scannerViewModel.labelScanState.collectAsState()
    val analyzerEnabled = labelScanState is LabelScanState.WaitingForFrame
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val bindingState = remember { mutableStateOf<CameraUseCaseBinding?>(null) }

    LaunchedEffect(bindingState.value, analyzerEnabled) {
        bindingState.value?.setAnalyzerEnabled(analyzerEnabled)
    }

    DisposableEffect(analyzerExecutor, lifecycleOwner, scannerViewModel) {
        onDispose {
            scannerViewModel.cancelActiveLabelScan()
            bindingState.value?.close()
            analyzerExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // 720p keeps the OCR input modest; verify label legibility on physical devices.
                .setTargetResolution(Size(1280, 720))
                .build()
            CameraUseCaseBinding(
                preview = preview,
                analysis = analysis,
                analyzerExecutor = analyzerExecutor,
                analyzer = scannerViewModel::onAnalysisFrame,
                onError = { latestOnCameraError.value(it) }
            )
                .also { binding ->
                    bindingState.value = binding
                    binding.bind(viewContext, lifecycleOwner)
                }
            previewView
        },
        modifier = modifier
    )
}

private class CameraUseCaseBinding(
    private val preview: Preview,
    private val analysis: ImageAnalysis,
    private val analyzerExecutor: ExecutorService,
    private val analyzer: ImageAnalysis.Analyzer,
    private val onError: (String) -> Unit
) {
    @Volatile
    private var closed = false
    private var cameraProvider: ProcessCameraProvider? = null

    fun setAnalyzerEnabled(enabled: Boolean) {
        if (closed) return
        if (enabled) {
            analysis.setAnalyzer(analyzerExecutor, analyzer)
        } else {
            analysis.clearAnalyzer()
        }
    }

    fun bind(context: Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (closed) return@addListener
            try {
                val provider = providerFuture.get()
                if (closed) return@addListener
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                cameraProvider = provider
            } catch (_: Exception) {
                if (!closed) onError("Camera could not be started. You can still enter the wine manually.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun close() {
        closed = true
        analysis.clearAnalyzer()
        cameraProvider?.let { provider ->
            runCatching { provider.unbind(preview, analysis) }
        }
        cameraProvider = null
    }
}
