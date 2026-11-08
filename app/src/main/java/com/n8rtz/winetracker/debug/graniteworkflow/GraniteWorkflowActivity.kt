package com.n8rtz.winetracker.debug.graniteworkflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import java.io.File

/**
 * Isolated debug/testing Activity for the **Dual OCR + Granite 4.2 4B Workflow**.
 *
 * Mirrors `GlinerLabActivity` setup (`enableEdgeToEdge()`, `WineTrackerTheme { Surface { ... } }`)
 * while keeping all state, storage, and native execution isolated in
 * `com.n8rtz.winetracker.debug.graniteworkflow`.
 */
class GraniteWorkflowActivity : ComponentActivity() {
    private val viewModel: GraniteWorkflowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WineTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsState()
                    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

                    val importGraniteModelLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            viewModel.importGraniteModelFile(uri)
                        }
                    }

                    val galleryImageLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            viewModel.onGalleryImageSelected(uri)
                        }
                    }

                    val takePictureLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.TakePicture()
                    ) { success ->
                        val file = pendingCameraFile
                        if (success && file != null && file.exists() && file.length() > 0L) {
                            viewModel.onCameraPhotoCaptured(file)
                        }
                    }

                    fun launchCameraCapture() {
                        runCatching {
                            val dir = File(cacheDir, "granite_workflow_captures").apply { mkdirs() }
                            val photoFile = File(dir, "granite_label_${System.currentTimeMillis()}.jpg")
                            pendingCameraFile = photoFile
                            val authority = "${applicationContext.packageName}.fileprovider"
                            val photoUri = FileProvider.getUriForFile(this, authority, photoFile)
                            takePictureLauncher.launch(photoUri)
                        }.onFailure { err ->
                            Toast.makeText(
                                this,
                                "Camera launch failed: ${err.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    val cameraPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) {
                            launchCameraCapture()
                        } else {
                            Toast.makeText(
                                this,
                                "Camera permission is required to snap a wine label photo.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    GraniteWorkflowScreen(
                        state = state,
                        onNavigateBack = { finish() },
                        onRefreshModels = { viewModel.refreshModelStatus() },
                        onResetCache = { viewModel.resetModelCache() },
                        onImportGraniteGguf = {
                            importGraniteModelLauncher.launch(arrayOf("*/*"))
                        },
                        onPickGalleryImage = {
                            galleryImageLauncher.launch("image/*")
                        },
                        onSnapCameraPhoto = {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                launchCameraCapture()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onLoadSampleLabel = { viewModel.onLoadSyntheticSampleLabel() },
                        onUseGlinerDataset = { viewModel.onUseGlinerDataset() },
                        onSelectDatasetSample = { imageKey -> viewModel.onSelectDatasetSample(imageKey) },
                        onRunDatasetSample = { imageKey -> viewModel.runDatasetSample(imageKey) },
                        onRunAllGlinerDataset = { viewModel.runAllGlinerDatasetSamples() },
                        onClearImage = { viewModel.clearSelectedImage() },
                        onRunWorkflow = { viewModel.runWorkflow(allowPreviewWithoutAllModels = true) },
                        onStopWorkflow = { viewModel.stopWorkflow() },
                        onOcrModeChange = { viewModel.setOcrMode(it) },
                        onThinkModeChange = { viewModel.setThinkModeEnabled(it) },
                        onClaheChange = { viewModel.setClaheEnabled(it) },
                        onDbRagChange = { viewModel.setDbRagEnabled(it) },
                        onToggleMlKitBoxes = { viewModel.setShowMlKitBoxes(it) },
                        onTogglePpOcrBoxes = { viewModel.setShowPpOcrBoxes(it) },
                        onShareJson = { jsonText ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "Granite 4.2 Wine Label JSON")
                                putExtra(Intent.EXTRA_TEXT, jsonText)
                            }
                            startActivity(Intent.createChooser(shareIntent, "Share Wine Label JSON"))
                        }
                    )
                }
            }
        }
    }
}
