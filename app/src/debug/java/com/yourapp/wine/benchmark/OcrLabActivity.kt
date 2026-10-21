package com.yourapp.wine.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.ui.ScanDiagnosticsPanel
import java.util.Locale

class OcrLabActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val exportRequested=intent.getBooleanExtra("export_csv",false)
        setContent { WineTrackerTheme { Surface { OcrLabScreen(exportRequested,onClose={finish()}) } } }
    }
}

@Composable
private fun OcrLabScreen(exportRequested:Boolean,onClose:()->Unit,vm:OcrLabViewModel=viewModel()) {
    val state by vm.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedImage by rememberSaveable { mutableStateOf<String?>(null) }
    var imageIndex by rememberSaveable(selected,selectedImage) { mutableStateOf(0) }
    var cellarLabelId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var autoExport by rememberSaveable { mutableStateOf(exportRequested) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.choose(uris) }
    val csvExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if(uri!=null) vm.export(uri,csv=true) }
    val zipExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if(uri!=null) vm.export(uri) }
    LaunchedEffect(state.historyReady,autoExport) {
        if(state.historyReady && autoExport) { autoExport=false;if(state.savedRows>0) csvExporter.launch("wine-ocr-tests.csv") }
    }
    val run=state.runs.firstOrNull { it.id==selectedImage } ?: state.runs.firstOrNull()
    val results=run?.results.orEmpty()
    val ranked=remember(results) { results.filter { it.status=="complete" }.sortedWith(
        compareByDescending<BenchmarkCaseResult> { it.score?.rawNameSimilarity ?: -1.0 }.thenByDescending { it.score?.exactFields ?: 0 }) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        item {
            Text("OCR Lab · multi-label benchmark",style=MaterialTheme.typography.headlineSmall)
            Text("Debug build ${BuildConfig.BUILD_COMMIT}",style=MaterialTheme.typography.bodySmall)
            Text("Production stack only, run sequentially. Up to 10 photos per batch; allow several minutes per photo. No cellar fields are changed.")
            Text("Actual details are used ONLY to score results. Blank = unscored, not expected-absent. Check each photo separately; answers are never copied between labels.")
            OutlinedButton(enabled=!state.busy,onClick=vm::loadBundledSamples) { Text("Load bundled test wines (3)") }
            Text("Blackstone, Olarra and Rhône originals with saved expected details. Replaces this queue, not CSV history. Review once; edits and confirmations are remembered.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!state.busy,onClick={picker.launch(arrayOf("image/*"))}) { Text("Choose multiple label images") }
            Text("Selecting photos replaces the editable queue, not saved CSV history.",style=MaterialTheme.typography.bodySmall)
        }
        items(state.labels,key={"edit-${it.id}"}) { label ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(label.label,style=MaterialTheme.typography.titleMedium)
                    if(!state.busy) LabelImage(label.uri,"Selected label ${label.label}",180)
                    OutlinedTextField(label.label,{vm.edit(label.id,null,it)},label={Text("Image nickname / sample ID")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(enabled=!state.busy,onClick={cellarLabelId=label.id}) { Text("Search cellar to fill actual details") }
                    FieldId.entries.forEach { field ->
                        val title=when(field) {
                            FieldId.PRODUCER -> "Actual maker / producer"
                            FieldId.WINE_NAME -> "Actual wine name"
                            FieldId.COUNTRY -> "Actual Country (state for U.S.)"
                            FieldId.REGION -> "Actual region"
                            FieldId.SUBREGION -> "Actual subregion / appellation"
                            FieldId.VINTAGE -> "Actual vintage"
                        }
                        OutlinedTextField(label.expected[field].orEmpty(),{vm.edit(label.id,field,it)},label={Text(title)},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                    }
                    Row { Checkbox(label.reviewed,{vm.review(label.id,it)},enabled=!state.busy);Text("I checked this image's actual details") }
                }
            }
        }
        item {
            Button(enabled=!state.busy && state.labels.isNotEmpty() && state.labels.all { it.reviewed },onClick={selected=null;selectedImage=null;vm.start()}) {
                Text("Run ${state.labels.size} images × production stack")
            }
            if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=vm::cancel) { Text("Stop") } }
            Text(state.progress)
            Text("Saved CSV history: ${state.savedRows} rows. Retained across screen close and app restart.")
            Text("CSV includes reviewed actual answers, recognized fields, raw text/confidence, recipe, timing and device/build data—no images or original paths. ZIP also includes this batch's processed images.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=!state.busy && state.savedRows>0,onClick={csvExporter.launch("wine-ocr-tests.csv")}) { Text("Export all saved test data (CSV)") }
            OutlinedButton(enabled=!state.busy && state.runs.any { it.results.isNotEmpty() },onClick={zipExporter.launch("wine-ocr-batch.zip")}) { Text("Export current batch CSV + images ZIP") }
            TextButton(enabled=!state.busy && state.savedRows>0,onClick={clearConfirm=true}) { Text("Clear saved test history…") }
            TextButton(onClick=onClose) { Text("Close OCR Lab") }
        }
        if(state.runs.isNotEmpty()) item {
            TextButton(onClick={showSummary=!showSummary}) { Text("${if(showSummary) "Hide" else "Show"} cross-image summary") }
            if(showSummary) {
                Text("Means use complete, scored cases only. Compare coverage before interpreting a high score; incomplete/missing cases are not successes.",style=MaterialTheme.typography.bodySmall)
                BenchmarkPlan.cases.forEach { recipe ->
                    val cases=state.runs.flatMap { it.results }.filter { it.recipe.id==recipe.id && it.status=="complete" }
                    val names=cases.mapNotNull { it.score?.rawNameSimilarity }
                    val mean=if(names.isEmpty()) "unscored" else String.format(Locale.ROOT,"%.1f%%",names.average()*100)
                    Text("${recipe.id}: ${cases.size}/${state.runs.size} complete; raw name $mean (${names.size} scored); fields ${cases.sumOf { it.score?.exactFields ?: 0 }}/${cases.sumOf { it.score?.checkedFields ?: 0 }}",style=MaterialTheme.typography.bodySmall)
                }
            }
            Text("Inspect a label",style=MaterialTheme.typography.titleMedium)
            state.runs.forEach { label ->
                TextButton(onClick={selectedImage=label.id;selected=null}) { Text("${label.label}: ${label.status} · ${label.results.size}/${BenchmarkPlan.cases.size}") }
            }
            run?.let { Text("${it.label} — scored against ${it.expected.values.joinToString(" · ")}") }
            Text("Raw name % = closest 1–3 OCR lines, not confidence/full-label accuracy. Field matches may include dictionary corrections.",style=MaterialTheme.typography.bodySmall)
        }
        items(ranked+results.filter { it.status!="complete" },key={"result-${run?.id}-${it.recipe.id}"}) { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(result.recipe.id,style=MaterialTheme.typography.titleMedium)
                    Text("${result.status} · ${result.report.elapsedMs} ms · raw name ${result.score?.rawNameSimilarity?.let { "${(it*100).toInt()}%" } ?: "unscored"} · fields ${result.score?.exactFields}/${result.score?.checkedFields}")
                    TextButton(enabled=!state.busy,onClick={selected=if(selected==result.recipe.id) null else result.recipe.id}) { Text("Inspect processed image and OCR") }
                    if(!state.busy && selected==result.recipe.id) {
                        Text(result.recipe.description,style=MaterialTheme.typography.bodySmall)
                        if(result.previews.isNotEmpty()) {
                            val image=result.previews[imageIndex.coerceIn(result.previews.indices)]
                            Text("Prepared image: ${image.name}")
                            if(result.previews.size>1) TextButton(onClick={imageIndex=(imageIndex+1)%result.previews.size}) { Text("Next pass image") }
                            LabelImage(image,"Processed label",320)
                        } else Text("No preview saved (early failure or per-image storage cap).")
                        ScanDiagnosticsPanel(result.report,results.indexOf(result).toLong(),allowDebugOverlay=false)
                    }
                }
            }
        }
    }
    cellarLabelId?.let { id ->
        val label=state.labels.firstOrNull { it.id==id }
        if(label!=null && !state.busy) CellarWinePicker(label.label,vm,
            onDismiss={cellarLabelId=null;vm.closeCellarSearch()},
            onSelect={wine -> vm.useCellarWine(id,wine);cellarLabelId=null})
    }
    if(clearConfirm) AlertDialog(onDismissRequest={clearConfirm=false},title={Text("Delete saved test history?")},
        text={Text("Export your CSV first. This permanently deletes saved benchmark rows, not cellar data or original photos.")},
        confirmButton={TextButton(onClick={clearConfirm=false;vm.clearHistory()}) { Text("Delete test history") }},
        dismissButton={TextButton(onClick={clearConfirm=false}) { Text("Cancel") }})
}

@Composable
private fun LabelImage(model:Any,description:String,height:Int) {
    AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(model).size(600)
        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
        contentDescription=description,modifier=Modifier.fillMaxWidth().height(height.dp))
}
