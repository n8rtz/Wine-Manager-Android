package com.yourapp.wine.gliner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.n8rtz.winetracker.ui.screens.GlinerLabScreen
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.n8rtz.winetracker.viewmodels.GlinerLabViewModel
import com.yourapp.wine.phonecorpus.WineAppLabelsViewModel

/**
 * Settings → Scanner testing → GLiNER lane lab.
 *
 * Hosted like the other lab screens: its own activity and ViewModel so a rotation keeps the model
 * session and the run loop alive, and so nothing here can leak into the production scanner.
 */
class GlinerLabActivity:ComponentActivity() {
    private val vm:GlinerLabViewModel by viewModels()
    // The Lab view model owns the saved, corpus-validated Lab and frozen Prod scores.
    private val labVm:WineAppLabelsViewModel by viewModels()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsState()
            val labState by labVm.state.collectAsState()
            val importLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if(uris.isNotEmpty()) vm.importBundle(uris)
            }
            val imageLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if(uris.isNotEmpty()) vm.importImages(uris)
            }
            val auditImportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                uris.firstOrNull()?.let(vm::importAudit)
            }
            val auditExportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                uri?.let(vm::saveAuditHistory)
            }
            val csvLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                uri?.let(vm::saveCsv)
            }
            val jsonLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                uri?.let(vm::saveJson)
            }
            WineTrackerTheme {
                Surface {
                    GlinerLabScreen(
                        state=state,
                        labState=labState,
                        onImport={ importLauncher.launch(arrayOf("*/*")) },
                        onClearBundle=vm::clearBundle,
                        onRefresh=vm::refresh,
                        onRunAll=vm::runAll,
                        onRunImage={ image -> vm.runSingle(image) },
                        onChooseImages={ imageLauncher.launch(arrayOf("image/*")) },
                        onRunCustom=vm::runCustomAll,
                        onRunCustomImage=vm::runCustomSingle,
                        onImportAudit={ auditImportLauncher.launch(arrayOf("*/*")) },
                        onRunAudit=vm::runAudit,
                        onSaveReviewedAudit=vm::saveReviewedAsAudit,
                        onSaveAudit={ auditExportLauncher.launch("gliner-audit-${System.currentTimeMillis()}.json") },
                        onSaveAuditField=vm::saveAuditField,
                        onRemoveCustom={ id -> vm.removeCustomImages(setOf(id)) },
                        onClearCustom={ vm.removeCustomImages(state.customSamples.map { it.sample.image }.toSet()) },
                        onConfirmField=vm::confirmField,
                        onCorrectField=vm::correctField,
                        onExpectedBlank=vm::expectBlank,
                        onConfirmAll=vm::confirmAllFields,
                        onDismissReviewPrompt=vm::dismissReviewPrompt,
                        onStop=vm::stop,
                        onSaveCsv={ csvLauncher.launch("gliner-lab-${System.currentTimeMillis()}.csv") },
                        onSaveJson={ jsonLauncher.launch("gliner-lab-${System.currentTimeMillis()}.json") },
                        onReport=vm::reportText,
                        onThreshold=vm::setThreshold,
                        onMaxWidth=vm::setMaxWidth,
                        onToggleRow=vm::toggleExpanded,
                        onClose={ finish() },
                    )
                }
            }
        }
    }
}
