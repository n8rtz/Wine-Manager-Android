package com.yourapp.wine.phonecorpus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.yourapp.wine.diagnostics.PipelineFunnel

private fun passFailLabel(passed:Int,failed:Int,passColor:Color,failColor:Color,shared:Boolean=false,notReached:Int=0,notRecorded:Int=0,notApplicable:Int=0):AnnotatedString = buildAnnotatedString {
    if(shared) append("Shared ")
    withStyle(SpanStyle(color=passColor)) { append(passed.toString()) }
    append("/")
    withStyle(SpanStyle(color=failColor)) { append(failed.toString()) }
    if(notReached+notRecorded+notApplicable>0) append(" · NR $notReached · ? $notRecorded · NA $notApplicable")
}

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
        var showFunnel by rememberSaveable { mutableStateOf(true) }
        var expandedFunnelSteps by rememberSaveable(state.mode) { mutableStateOf(emptySet<String>()) }
        var exportIndex by rememberSaveable { mutableStateOf(0) }
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.saveExportPart(it,exportIndex) } }
        val importCsv=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::splitExistingCsv) }
        val importResults=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importResults) }
        DisposableEffect(state.busy) {
            if(state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text("Curated 107-photo benchmark",style=MaterialTheme.typography.headlineSmall)
                Text("Build ${BuildConfig.BUILD_COMMIT} · 107 uncolored labels are run. 55 red, 4 yellow, and 2 omitted labels are excluded from OCR. All 168 source photos remain packaged for provenance.")
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
                Text("Each option runs only its selected implementation. Production v1.0 is the Add Wine scanner. Production v2.0 retains the guarded-producer path. Lab preserves raw readings and adds linked primary-LWIN producer and wine evidence; spatial joins are alternatives only. Saved historical baseline rows are scored, never rerun.",style=MaterialTheme.typography.bodySmall)
            }
            item {
                Text(state.message)
                Text("Checkpointed: ${state.completed}/${state.total}")
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick={vm.start()},enabled=!state.busy && !state.readOnly && state.board!=null && state.completed<state.total) { Text("Start / resume remaining photos") }
                Row {
                    TextButton(onClick=vm::stop,enabled=state.busy) { Text("Stop after image") }
                    TextButton(onClick={vm.start(true)},enabled=!state.busy && !state.readOnly && state.completed>0) { Text("Retry failed / partial") }
                }
                if(state.readOnly) Text("This saved or imported result table is persistent and read-only. Start a new run to collect OCR results with the installed build.",style=MaterialTheme.typography.bodySmall)
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
                    TextButton(onClick={vm.prepareExport(true)},enabled=!state.busy && state.completed>0 && !state.readOnly) { Text("Prepare raw diagnostics CSV export") }
                    Text("Scored CSV includes field outcomes and aggregate pipeline_step bottleneck rows. Imported scored files do not contain per-image pipeline gate telemetry; no gates are inferred. Raw CSV retains stage statuses for on-device runs. No images or account information are exported.",style=MaterialTheme.typography.bodySmall)
                }
                item {
                    Text("Pipeline bottleneck matrix",style=MaterialTheme.typography.titleMedium)
                    Text("Three requirement tables: ML Kit OCR, Paddle OCR, then post-OCR. OCR tables are distinct outputs, not parallel execution (the runner executes ML Kit before Paddle). Post-OCR combines shared assignment and dependent resolver gates; evidence hypotheses are jointly assigned, not a serial per-field chain. Counts are passed/failed; NR, ?, and N/A preserve not-reached, unrecorded, and not-applicable cohorts. Tap + for definitions.",
                        style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={showFunnel=!showFunnel}) { Text(if(showFunnel) "Hide table" else "Show table") }
                    if(showFunnel) SelectionContainer {
                        val stepWidth=320.dp
                        val fieldWidth=88.dp
                        val fieldTitles=PhoneCorpusMath.fields.map { field -> when(field) {
                            "wine_name" -> "Wine name"
                            else -> field.replaceFirstChar { it.uppercase() }
                        } }
                        val matrixScroll=rememberScrollState()
                        val gridColor=MaterialTheme.colorScheme.outlineVariant
                        val passColor=if(isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
                        val failColor=MaterialTheme.colorScheme.error
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val viewportWidth=maxWidth
                            Column(Modifier.horizontalScroll(matrixScroll)) {
                            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Pipeline step",Modifier.width(stepWidth).heightIn(min=48.dp).border(1.dp,gridColor).padding(4.dp),
                                    style=MaterialTheme.typography.labelMedium)
                                fieldTitles.forEach { title ->
                                    Text(title,Modifier.width(fieldWidth).heightIn(min=48.dp).border(1.dp,gridColor).padding(4.dp),
                                        style=MaterialTheme.typography.labelMedium,textAlign=TextAlign.Center,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            board.matrix.forEachIndexed { index,row ->
                                val section=when {
                                    row.step.branch.startsWith("OCR table · ML Kit") -> "OCR 1 · ML Kit"
                                    row.step.branch.startsWith("OCR table · Paddle") -> "OCR 2 · Paddle"
                                    else -> "POST-OCR REQUIREMENTS"
                                }
                                val previous=board.matrix.getOrNull(index-1)?.step?.branch
                                val previousSection=when {
                                    previous?.startsWith("OCR table · ML Kit")==true -> "OCR 1 · ML Kit"
                                    previous?.startsWith("OCR table · Paddle")==true -> "OCR 2 · Paddle"
                                    previous==null -> null
                                    else -> "POST-OCR REQUIREMENTS"
                                }
                                if(section!=previousSection) Text(section,Modifier.width(viewportWidth).padding(top=10.dp,bottom=4.dp),style=MaterialTheme.typography.titleSmall)
                                val expanded=row.step.id in expandedFunnelSteps
                                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                    Row(Modifier.width(stepWidth).heightIn(min=56.dp).border(1.dp,gridColor).padding(4.dp),
                                        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                        TextButton(onClick={expandedFunnelSteps=if(expanded) expandedFunnelSteps-row.step.id else expandedFunnelSteps+row.step.id},
                                            modifier=Modifier.width(40.dp),contentPadding=PaddingValues(0.dp)) {
                                            Text(if(expanded) "−" else "+")
                                        }
                                        Column(Modifier.weight(1f).padding(end=4.dp)) {
                                            Text(row.step.title,style=MaterialTheme.typography.bodySmall)
                                            Text(row.step.branch,style=MaterialTheme.typography.labelSmall,
                                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if(row.shared!=null) {
                                        Text(passFailLabel(row.shared.passed,row.shared.failed,passColor,failColor,shared=true,notReached=row.shared.notReached,notRecorded=row.shared.notRecorded,notApplicable=row.shared.notApplicable),
                                            Modifier.width(fieldWidth*PhoneCorpusMath.fields.size).heightIn(min=48.dp).border(1.dp,gridColor).padding(4.dp),
                                            style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center,maxLines=1)
                                    } else {
                                        PhoneCorpusMath.fields.forEach { field ->
                                            val cell=row.fields[field]
                                            Text(cell?.let { passFailLabel(it.passed,it.failed,passColor,failColor,notReached=it.notReached,notRecorded=it.notRecorded,notApplicable=it.notApplicable) } ?: AnnotatedString(""),
                                                Modifier.width(fieldWidth).heightIn(min=48.dp).border(1.dp,gridColor).padding(4.dp),
                                                style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center,maxLines=1)
                                        }
                                    }
                                }
                                if(expanded) Text(row.step.definition,Modifier.width(viewportWidth).border(1.dp,gridColor)
                                    .padding(start=48.dp,end=8.dp,top=8.dp,bottom=8.dp),style=MaterialTheme.typography.bodySmall)
                            }
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick={importResults.launch(arrayOf("text/*","application/csv","application/octet-stream"))},enabled=!state.busy) { Text("Import scored results CSV") }
                Text("Imports a scored Phone Corpus results CSV as a persistent, read-only table. It does not rerun OCR or infer per-photo pipeline gates.",style=MaterialTheme.typography.bodySmall)
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
            text={Text("Existing runs are kept on the device. Export this run before changing runs or installing another APK. A new run starts the 107 included images again; 61 review-excluded images are not run.")},
            confirmButton={TextButton(onClick={newRun=false;vm.load(state.mode,true)}) { Text("New run") }},
            dismissButton={TextButton(onClick={newRun=false}) { Text("Cancel") }})
    }
}
