package com.yourapp.wine.phonecorpus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.n8rtz.winetracker.ui.screens.DevicePhotoLabScreen
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.n8rtz.winetracker.viewmodels.DevicePhotoLabViewModel

/** Separate lifecycle and storage from the retained-label benchmark. Rotation retains the active ViewModel. */
class DevicePhotoLabActivity:ComponentActivity() {
    private val vm:DevicePhotoLabViewModel by viewModels()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        vm.configure(intent.getStringArrayListExtra(EXTRA_MODULE_KEYS).orEmpty())
        setContent {
            val state by vm.state.collectAsState()
            var saveIndex by rememberSaveable { mutableStateOf(0) }
            val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                vm.runPhotos(uris)
            }
            val saveJson=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                uri?.let { vm.saveExport(it,saveIndex) }
            }
            val saveCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                uri?.let { vm.saveExport(it,saveIndex) }
            }
            WineTrackerTheme {
                Surface {
                    DevicePhotoLabScreen(state,onSelectPhotos={ picker.launch(arrayOf("image/*")) },onStop=vm::stop,
                        onSave={ index,file ->
                            saveIndex=index
                            if(file.extension=="json") saveJson.launch(file.name) else saveCsv.launch(file.name)
                        },onClose={ finish() })
                }
            }
        }
    }

    companion object { const val EXTRA_MODULE_KEYS="lab_module_keys" }
}
