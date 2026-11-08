package com.n8rtz.winetracker.debug.graniteworkflow

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme

/**
 * Settings ➔ Scanner testing ➔ `Dual OCR + Granite 4.2 4B Workflow`.
 *
 * Hosted like the other lab screens: its own activity and ViewModel so a rotation keeps the model
 * session and the run loop alive, and so nothing here can leak into the production scanner.
 */
class GraniteWorkflowActivity : ComponentActivity() {
    private val viewModel: GraniteWorkflowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        onResetCache = viewModel::resetModelCache,
                        onImportGraniteGguf = {
                            graniteGgufPicker.launch(arrayOf("*/*"))
                        },
                        onPickGalleryImage = {
                            galleryImagePicker.launch(arrayOf("image/*"))
                        },
                        onCameraPhotoCaptured = viewModel::onCameraPhotoCaptured,
                        onLoadSampleLabel = viewModel::onLoadSyntheticSampleLabel,
                        onClearImage = viewModel::clearSelectedImage,
                        onSelectOcrMode = viewModel::setOcrMode,
                        onToggleThinkMode = viewModel::setThinkModeEnabled,
                        onToggleClahe = viewModel::setClaheEnabled,
                        onToggleShowMlKitBoxes = viewModel::setShowMlKitBoxes,
                        onToggleShowPpOcrBoxes = viewModel::setShowPpOcrBoxes,
                        onRunWorkflow = { viewModel.runWorkflow(allowPreviewWithoutAllModels = true) },
                        onStopWorkflow = viewModel::stopWorkflow
                    )
                }
            }
        }
    }
}
