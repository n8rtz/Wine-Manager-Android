package com.n8rtz.winetracker.debug.graniteworkflow

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme

/**
 * Settings ➔ Scanner testing ➔ `Dual OCR + Granite 4.2 4B Workflow`.
 *
 * Isolated Activity and ViewModel hosting the Dual OCR (reusing existing ML Kit v2 + PP-OCRv6 ONNX)
 * + IBM Granite 4.2 3B GGUF reconciliation test bench.
 */
class GraniteWorkflowActivity : ComponentActivity() {
    private val viewModel: GraniteWorkflowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsState()

            // One-tap Android file picker (ActivityResultContracts.OpenDocument) for granite-4.2-3b-Q4_K_M.gguf
            val graniteGgufPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    viewModel.importGraniteModelFile(uri)
                }
            }

            // Gallery photo picker via ActivityResultContracts.OpenDocument
            val galleryImagePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    viewModel.onGalleryImageSelected(uri)
                }
            }

            WineTrackerTheme {
                Surface {
                    GraniteWorkflowScreen(
                        state = state,
                        onBack = { finish() },
                        onRefreshModels = viewModel::refreshModelStatus,
                        onImportGraniteGguf = {
                            graniteGgufPicker.launch(arrayOf("*/*"))
                        },
                        onPickGalleryImage = {
                            galleryImagePicker.launch(arrayOf("image/*"))
                        },
                        onCameraPhotoCaptured = viewModel::onCameraPhotoCaptured,
                        onLoadSampleLabel = viewModel::onLoadSyntheticSampleLabel,
                        onSelectOcrMode = viewModel::setOcrMode,
                        onToggleThinkMode = viewModel::setThinkModeEnabled,
                        onToggleClahe = viewModel::setClaheEnabled,
                        onToggleShowMlKitBoxes = viewModel::setShowMlKitBoxes,
                        onToggleShowPpOcrBoxes = viewModel::setShowPpOcrBoxes,
                        onRunWorkflow = { viewModel.runWorkflow(allowPreviewWithoutAllModels = true) }
                    )
                }
            }
        }
    }
}
