package com.yourapp.wine.phonecorpus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme

class PhoneCorpusActivity:ComponentActivity() {
    private val vm:PhoneCorpusViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WineTrackerTheme { Surface { Screen() } } }
    }
    override fun onStop() { if(!isChangingConfigurations) vm.stop();super.onStop() }
    @Composable private fun Screen() {
        val state by vm.state.collectAsState()
        val clipboard=LocalClipboardManager.current
        var newRun by remember { mutableStateOf(false) }
        var showDetails by remember { mutableStateOf(false) }
        var exportIndex by rememberSaveable { mutableStateOf(0) }
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.saveExportPart(it,exportIndex) } }
        val importCsv=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::splitExistingCsv) }
        DisposableEffect(state.busy) {
            if(state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text("Bundled 168-photo benchmark",style=MaterialTheme.typography.headlineSmall)
                Text("Build ${BuildConfig.BUILD_COMMIT} · all 168 photos are inside this debug APK. No photo downloads, picker or desktop required.")
                Text("Private test corpus; image redistribution rights are unresolved. Do not publish this APK. Frozen references are provisional development judgments, not held-out accuracy.",style=MaterialTheme.typography.bodySmall)
                Text("Fresh ML Kit + Medium OCR. Plug in your phone and keep this screen open; long runs can take hours and generate heat. Going to the background requests a stop after the current image. Abrupt process death preserves completed checkpoints.")
            }
            item {
                PhoneCorpusRunner.Mode.entries.forEach { mode ->
                    Row {
                        RadioButton(selected=state.mode==mode,onClick={vm.load(mode)},enabled=!state.busy)
                        Text(mode.title,Modifier.padding(top=10.dp))
                    }
                }
                Text("Each option runs only its selected implementation. Production v1.0 is the Add Wine scanner. Production v2.0 retains the guarded-producer path. Lab enables spatial merging before guarded extraction. Saved historical baseline rows are scored, never rerun.",style=MaterialTheme.typography.bodySmall)
            }
            item {
                Text(state.message)
                Text("Checkpointed: ${state.completed}/168")
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick={vm.start()},enabled=!state.busy && state.board!=null && state.completed<168) { Text("Start / resume remaining photos") }
                Row {
                    TextButton(onClick=vm::stop,enabled=state.busy) { Text("Stop after image") }
                    TextButton(onClick={vm.start(true)},enabled=!state.busy && state.completed>0) { Text("Retry failed / partial") }
                }
                TextButton(onClick={newRun=true},enabled=!state.busy) { Text("New run (keep old results)") }
            }
            state.board?.let { board ->
                item {
                    Text("Scored results",style=MaterialTheme.typography.titleMedium)
                    Text("Swipe sideways to see all metrics. Saved Production v2.0 is your full phone run from build 28bce9e81cc3; it is not rerun. Compare same-attempted-photo rows when stopping early. The older Frozen baseline is not Production v1.0. Complete = all six correct, including expected blanks; failures get no correct-blank credit.")
                    SelectionContainer {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row { board.headers.forEach { Text(it,Modifier.width(145.dp).padding(6.dp),style=MaterialTheme.typography.labelMedium) } }
                            board.rows.forEach { row -> HorizontalDivider(Modifier.width((145*board.headers.size).dp));Row { row.forEach { Text(it,Modifier.width(145.dp).padding(6.dp)) } } }
                        }
                    }
                    TextButton(onClick={clipboard.setText(AnnotatedString("Build ${BuildConfig.BUILD_COMMIT}\n"+board.text))}) { Text("Copy scored stats") }
                    Button(onClick={vm.prepareExport(false)},enabled=!state.busy && state.csv!=null) { Text("Prepare scored CSV export") }
                    TextButton(onClick={vm.prepareExport(true)},enabled=!state.busy && state.completed>0) { Text("Prepare raw diagnostics CSV export") }
                    Text("CSV includes every scored field and its expected/recognized value, failures, and saved baseline comparisons. No images or account information are exported.",style=MaterialTheme.typography.bodySmall)
                }
            }
            item {
                TextButton(onClick={importCsv.launch(arrayOf("text/*","application/csv","application/octet-stream"))},enabled=!state.busy) { Text("Split an existing CSV for upload") }
                state.exportParts.forEachIndexed { index,file ->
                    OutlinedButton(onClick={exportIndex=index;export.launch(file.name)},enabled=!state.busy) {
                        Text("Save ${if(state.exportParts.size>1) "part ${index+1}/${state.exportParts.size}" else "CSV"} · ${"%.2f".format(file.length()/1_000_000.0)} MB")
                    }
                }
                Text("Exports over 25 MB are split into numbered CSVs, each with a header and complete records. Save and upload every part. A 35 MB export normally produces two files. Existing exports can be split without rerunning the test.",style=MaterialTheme.typography.bodySmall)
            }
            item { TextButton(onClick={showDetails=!showDetails}) { Text(if(showDetails) "Hide per-photo results" else "Show per-photo results") } }
            if(showDetails) items(state.board?.details.orEmpty()) { detail ->
                Card(Modifier.fillMaxWidth()) { SelectionContainer { Column(Modifier.padding(12.dp)) {
                    Text(detail.first(),style=MaterialTheme.typography.titleSmall)
                    detail.drop(1).forEach { Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=6.dp)) }
                } } }
            }
            item { TextButton(onClick={vm.stop();finish()}) { Text("Close — keep checkpoints") } }
        }
        if(newRun) AlertDialog(onDismissRequest={newRun=false},title={Text("Start a new benchmark?")},
            text={Text("Existing runs are kept on the device. Export this run before changing runs or installing another APK. A new run starts all 168 images again.")},
            confirmButton={TextButton(onClick={newRun=false;vm.load(state.mode,true)}) { Text("New run") }},
            dismissButton={TextButton(onClick={newRun=false}) { Text("Cancel") }})
    }
}
