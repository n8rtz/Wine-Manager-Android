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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme

/**
 * Settings ➔ Scanner testing ➔ `Dual OCR + Granite 4.2 4B Workflow`.
 *
 * Completely isolated Activity and ViewModel hosting the Dual OCR (ML Kit v2 + PP-OCRv6 ONNX)
 * + IBM Granite 4.2 3B GGUF reconciliation test bench.
 */
class GraniteWorkflowActivity : ComponentActivity() {
    private val viewModel: GraniteWorkflowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsState()
            var pendingImportTarget by remember {
                mutableStateOf(GraniteModelImportTarget.AUTO_DETECT)
            }

            // One-tap Android file picker (ActivityResultContracts.OpenDocument) for .gguf / .onnx model files
            val modelDocumentPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    viewModel.importModelFiles(listOf(uri), pendingImportTarget)
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
                        onImportModelSlot = { target ->
                            pendingImportTarget = target
                            modelDocumentPicker.launch(arrayOf("*/*"))
                        },
                        onSeedBundledPpOcr = viewModel::seedBundledPpOcrModels,
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
