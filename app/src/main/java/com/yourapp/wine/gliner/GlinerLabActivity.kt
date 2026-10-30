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

/**
 * Settings → Scanner testing → GLiNER lane lab.
 *
 * Hosted like the other lab screens: its own activity and ViewModel so a rotation keeps the model
 * session and the run loop alive, and so nothing here can leak into the production scanner.
 */
class GlinerLabActivity:ComponentActivity() {
    private val vm:GlinerLabViewModel by viewModels()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsState()
            val importLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if(uris.isNotEmpty()) vm.importBundle(uris)
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
                        onImport={ importLauncher.launch(arrayOf("*/*")) },
                        onClearBundle=vm::clearBundle,
                        onRefresh=vm::refresh,
                        onRunAll=vm::runAll,
                        onRunImage={ image -> vm.runSingle(image) },
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
