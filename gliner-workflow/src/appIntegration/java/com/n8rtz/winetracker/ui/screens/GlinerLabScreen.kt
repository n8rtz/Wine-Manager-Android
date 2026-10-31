package com.n8rtz.winetracker.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.viewmodels.GlinerAggregate
import com.yourapp.wine.gliner.GlinerAuditReference
import com.yourapp.wine.gliner.GlinerAuditRow
import com.yourapp.wine.gliner.GlinerLabRunner
import com.yourapp.wine.gliner.GlinerFieldReview
import com.yourapp.wine.gliner.GlinerReviewCase
import com.yourapp.wine.gliner.summarizeGlinerReviews
import com.n8rtz.winetracker.viewmodels.GlinerLabRow
import com.n8rtz.winetracker.viewmodels.GlinerLabState
import com.n8rtz.winetracker.viewmodels.aggregateGlinerRows
import com.yourapp.wine.gliner.GlinerLabImageResult
import com.yourapp.wine.gliner.GlinerCustomImages
import com.yourapp.wine.gliner.GlinerLane
import com.yourapp.wine.phonecorpus.FailedParametersTable
import com.yourapp.wine.phonecorpus.LabelThumbnail
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import com.yourapp.wine.phonecorpus.WineAppLabelsState
import com.yourapp.wine.phonecorpus.WineAppLabelsBuildLog
import com.yourapp.wine.phonecorpus.glinerLabBaselines
import com.yourapp.wine.phonecorpus.ZoomableLabelDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val fieldLabels = listOf("producer" to "Producer", "wine_name" to "Wine name", "country" to "Country",
    "region" to "Region", "subregion" to "Subregion", "vintage" to "Vintage")

/** GLiNER results use the same layout, field scoring and failed-label cards as the Lab v7 page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlinerLabScreen(
    state:GlinerLabState,
    labState:WineAppLabelsState,
    onImport:()->Unit, onClearBundle:()->Unit, onRefresh:()->Unit,
    onRunAll:()->Unit, onRunImage:(String)->Unit, onStop:()->Unit,
    onChooseImages:()->Unit, onRunCustom:()->Unit, onRunCustomImage:(String)->Unit,
    onImportAudit:()->Unit, onUseBundledAudit:()->Unit, onRunAudit:()->Unit, onSaveAudit:()->Unit, onSaveReviewedAudit:()->Unit,
    onSaveAuditField:(String,String,String,Boolean)->Unit,
    onRemoveCustom:(String)->Unit, onClearCustom:()->Unit,
    onConfirmField:(String,String,Boolean)->Unit, onCorrectField:(String,String,String)->Unit,
    onExpectedBlank:(String,String,Boolean)->Unit, onConfirmAll:(String)->Unit, onDismissReviewPrompt:()->Unit,
    onSaveCsv:()->Unit, onSaveJson:()->Unit, onReport:()->String,
    onThreshold:(Float)->Unit, onMaxWidth:(Int)->Unit, onToggleRow:(String)->Unit, onClose:()->Unit,
) {
    val clipboard=LocalClipboardManager.current
    val listState=rememberLazyListState()
    val scope=rememberCoroutineScope()
    var showCustom by remember { mutableStateOf(true) }
    var showAuditReferences by remember { mutableStateOf(false) }
    var showAuditResults by remember { mutableStateOf(true) }
    var confirmAuditImport by remember { mutableStateOf(false) }
    var confirmBuiltInAudit by remember { mutableStateOf(false) }
    var confirmAuditReviews by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemoveImage by remember { mutableStateOf<String?>(null) }
    var confirmRerunAll by remember { mutableStateOf(false) }
    var confirmRerunImage by remember { mutableStateOf<String?>(null) }
    var customZoom by remember { mutableStateOf<String?>(null) }
    var showIncomplete by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var selectedFields by remember { mutableStateOf(emptySet<String>()) }
    var expandedRaw by remember { mutableStateOf(emptySet<String>()) }
    var expandedImage by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.running) {
        while(state.running) { now=SystemClock.elapsedRealtime(); delay(250) }
    }
    val incomplete=state.samples.filter { row ->
        row.result?.let { result -> result.status!="complete" || result.fieldResults.any {
            row.sample.target(it.field).status!="unresolved" && !PhoneCorpusMath.correct(it.outcome)
        } } == true
    }
    fun failedKeys(row:GlinerLabRow):Set<String> = row.result?.fieldResults.orEmpty().filter {
        row.sample.target(it.field).status!="unresolved" && !PhoneCorpusMath.correct(it.outcome)
    }.map { it.field }.toSet()
    val filtered=if(selectedFields.isEmpty()) incomplete else incomplete.filter { row -> failedKeys(row).any { it in selectedFields } }
    val elapsed=if(state.running) (now-state.runStartedMs).coerceAtLeast(0) else state.runElapsedMs
    LazyColumn(
        Modifier.fillMaxSize().testTag("gliner_lab_content").statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp,vertical=12.dp),
        state=listState,
        verticalArrangement=Arrangement.spacedBy(12.dp),
    ) {
        item {
            SelectionContainer {
                Column {
                    Text("GLiNER lane lab",style=MaterialTheme.typography.headlineSmall)
                    Text("build ${BuildConfig.BUILD_COMMIT} · ${state.laneConfig.signatureKey()}",style=MaterialTheme.typography.bodySmall)
                    Text(if(state.bundle.ready) "model ready · INT4 "+(if(state.bundle.int4) "✓" else "✗") else "model missing · ${state.bundle.summary()}")
                    if(state.modelMs>0) Text("model load ${state.modelMs} ms · bundle ${state.bundleMs} ms",style=MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                TextButton(onClick=onImport,enabled=!state.running) { Text("Import") }
                TextButton(onClick=onClearBundle,enabled=!state.running) { Text("Remove") }
                TextButton(onClick=onRefresh,enabled=!state.running) { Text("Re-check") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={ onThreshold(-0.05f) },enabled=!state.running,contentPadding=PaddingValues(horizontal=8.dp)) { Text("thr −") }
                TextButton(onClick={ onThreshold(0.05f) },enabled=!state.running,contentPadding=PaddingValues(horizontal=8.dp)) { Text("thr +") }
                TextButton(onClick={ onMaxWidth(-2) },enabled=!state.running,contentPadding=PaddingValues(horizontal=8.dp)) { Text("wid −") }
                TextButton(onClick={ onMaxWidth(2) },enabled=!state.running,contentPadding=PaddingValues(horizontal=8.dp)) { Text("wid +") }
                SelectionContainer { Text("${"%.2f".format(state.laneConfig.threshold)} · w${state.laneConfig.maxWidth}",style=MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SelectionContainer {
                Column {
                    if(state.message.isNotBlank()) Text(state.message)
                    Text("GLiNER completed: ${state.samples.count { it.result!=null }}/${state.samples.size}")
                    if(state.running) Text("${state.done}/${state.total} · ${state.current}",style=MaterialTheme.typography.bodySmall)
                }
            }
            if(state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick=onRunAll,enabled=!state.running && state.bundle.ready && state.samples.isNotEmpty()) {
                Text(if(state.ran) "New GLiNER run" else "Start GLiNER run")
            }
            SelectionContainer { RunTimers(elapsed,state.done,state.total) }
            Row {
                TextButton(onClick=onStop,enabled=state.running) { Text("Stop") }
                TextButton(onClick=onClose) { Text("Close") }
            }
        }
        item(key="custom_heading") {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Your images (${state.customSamples.size})",style=MaterialTheme.typography.titleMedium)
                Text("Select multiple photos. Predictions stay separate from the curated scores; check each field after the run.",style=MaterialTheme.typography.bodySmall)
                if(state.customLoadError.isNotBlank()) Text(state.customLoadError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick=onChooseImages,enabled=!state.running && !state.importingImages && state.customLoaded && state.customLoadError.isBlank(),
                        modifier=Modifier.testTag("gliner_choose_images")) { Text("Choose images") }
                    TextButton(onClick={ if(state.customSamples.any { row -> row.reviews.values.any { it.reviewed } }) confirmRerunAll=true else onRunCustom() },
                        enabled=!state.running && !state.importingImages && state.bundle.ready && state.customSamples.isNotEmpty()) { Text("Run your images") }
                    TextButton(onClick={ confirmClear=true },enabled=!state.running && !state.importingImages && (state.customSamples.isNotEmpty() || state.customLoadError.isNotBlank())) { Text(if(state.customLoadError.isNotBlank()) "Reset imports" else "Remove all") }
                    TextButton(onClick={ showCustom=!showCustom }) { Text(if(showCustom) "Hide" else "Show") }
                    if(state.customRan) TextButton(onClick=onSaveJson,enabled=!state.running) { Text("Save JSON") }
                }
                val summary=summarizeGlinerReviews(state.customSamples.filter { it.result!=null }.map { row ->
                    GlinerReviewCase(row.result?.status=="complete",row.sample.matchesCorpus,row.reviews)
                },PhoneCorpusMath.fields)
                if(state.customRan) {
                    Text("${summary.processed} processed · ${summary.fullyReviewed} fully reviewed · ${summary.allConfirmed}/${summary.fullyReviewed} labels correct · ${summary.confirmedFields}/${summary.reviewedFields} reviewed fields correct",style=MaterialTheme.typography.bodySmall)
                    Text("Save JSON to keep results and reviews; they can be lost if the app process ends.",style=MaterialTheme.typography.bodySmall)
                }
                if(state.customSamples.any { it.sample.matchesCorpus }) Text("Exact bundled-image matches are excluded from these totals. Different photos of the same label are not detected.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            }
        }
        item(key="audit_heading") {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Personal audit",style=MaterialTheme.typography.titleMedium)
                val available=state.auditReferences.count { ref -> state.customSamples.any { it.sample.image==ref.image } }
                val warnings=state.auditReferences.sumOf { it.warnings.size }
                Text("${state.auditReferences.size} saved references · $available photos available · $warnings review warnings",style=MaterialTheme.typography.bodySmall)
                Text("The 26-photo reference set is built in; photos must still be on this phone. Curated scores are unchanged.",style=MaterialTheme.typography.bodySmall)
                if(available<state.auditReferences.size) Text("Re-select the original photos with Choose images before running the full audit.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                if(state.auditLoadError.isNotBlank()) Text(state.auditLoadError,color=MaterialTheme.colorScheme.error)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Button(onClick=onRunAudit,enabled=!state.running && !state.importingImages && state.bundle.ready && state.auditReferences.isNotEmpty() && state.customLoaded) { Text("Run audit test") }
                    TextButton(onClick={ if(state.auditReferences.isNotEmpty()) confirmBuiltInAudit=true else onUseBundledAudit() },
                        enabled=!state.running && !state.importingImages && state.auditLoaded) { Text("Load built-in audit") }
                    TextButton(onClick={ if(state.auditReferences.isNotEmpty()) confirmAuditImport=true else onImportAudit() },
                        enabled=!state.running && !state.importingImages && state.auditLoaded) { Text("Import reviewed JSON") }
                    TextButton(onClick=onSaveAudit,enabled=!state.running && state.auditRunState.isNotBlank()) { Text("Save audit JSON") }
                    TextButton(onClick={ confirmAuditReviews=true },enabled=!state.running && !state.importingImages && state.auditLoaded &&
                        state.customSamples.any { row -> row.result?.status=="complete" && row.reviews.values.any { it.reviewed } }) { Text("Save reviewed fields") }
                }
                if(state.auditReferences.isNotEmpty()) TextButton(onClick={ showAuditReferences=!showAuditReferences }) {
                    Text(if(showAuditReferences) "Hide saved references" else "Review saved references")
                }
                if(state.auditRunState.isNotBlank()) {
                    val completed=state.auditRows.filter { it.status=="complete" && !it.exactCorpusMatch }
                    val eligible=completed.filter { row -> PhoneCorpusMath.fields.all { row.reference.targets.getValue(it).status!="unresolved" } }
                    val full=eligible.count { row -> PhoneCorpusMath.fields.all { PhoneCorpusMath.correct(row.outcomes[it].orEmpty()) } }
                    val known=completed.sumOf { row -> PhoneCorpusMath.fields.count { row.reference.targets.getValue(it).status!="unresolved" } }
                    val correct=completed.sumOf { row -> PhoneCorpusMath.fields.count { PhoneCorpusMath.correct(row.outcomes[it].orEmpty()) } }
                    Text("Latest audit: ${when { state.auditRunState=="running" && !state.runningAudit -> "interrupted"; state.auditRunState=="imported_baseline" -> "imported baseline (not a new run)"; else -> state.auditRunState }} · ${state.auditRows.size} results · ${state.auditRows.count { it.status!="complete" }} failed",style=MaterialTheme.typography.bodySmall)
                    Text("$full/${eligible.size} fully correct · $correct/$known reference fields match",style=MaterialTheme.typography.bodySmall)
                    if(state.auditRows.any { it.exactCorpusMatch }) Text("Exact bundled-image matches excluded from totals.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    Text("Matches use normalized comparison with saved aliases; unresolved fields are excluded. This is not independent accuracy.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ showAuditResults=!showAuditResults }) { Text(if(showAuditResults) "Hide audit results" else "Show audit results") }
                }
            }
        }
        if(showAuditReferences) items(state.auditReferences,key={ "auditref:${it.image}" }) { ref ->
            AuditReferenceCard(ref,enabled=!state.running && !state.importingImages,
                onSave={ field,value,blank -> onSaveAuditField(ref.image,field,value,blank) })
        }
        if(showAuditResults) items(state.auditRows,key={ "auditresult:${it.reference.image}" }) { row ->
            AuditResultCard(row)
        }
        if(showCustom) items(state.customSamples,key={ "custom:${it.sample.image}" }) { row ->
            CustomImageReview(row,enabled=!state.running && !state.importingImages,modelReady=state.bundle.ready,
                onRun={ if(row.reviews.values.any { it.reviewed }) confirmRerunImage=row.sample.image else onRunCustomImage(row.sample.image) },onRemove={ confirmRemoveImage=row.sample.image },
                onZoom={ customZoom=row.sample.image },onConfirm={ field,checked -> onConfirmField(row.sample.image,field,checked) },
                onCorrect={ field,value -> onCorrectField(row.sample.image,field,value) },
                onBlank={ field,checked -> onExpectedBlank(row.sample.image,field,checked) },
                onConfirmAll={ onConfirmAll(row.sample.image) })
        }
        item {
            SelectionContainer {
                ScoredResults(state.aggregate ?: aggregateGlinerRows(state.samples,state.stopped),labState,
                    state.samples.filter { it.sample.included && it.result!=null }.map { it.sample.image }.toSet())
            }
        }
        if(state.ran || state.customRan) {
            item {
                Text("Export",style=MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick={ clipboard.setText(AnnotatedString(onReport())) }) { Text("Copy all results") }
                    TextButton(onClick=onSaveJson,enabled=!state.running) { Text("Save JSON") }
                    TextButton(onClick=onSaveCsv,enabled=!state.running) { Text("Save CSV") }
                }
            }
        }
        item {
            TextButton(onClick={ showIncomplete=!showIncomplete },modifier=Modifier.fillMaxWidth()) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Incomplete labels (${incomplete.size})",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                    Text(if(showIncomplete) "▲" else "▼",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if(showIncomplete) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        fieldLabels.forEach { (key,label) ->
                            val selected=key in selectedFields
                            FilterChip(selected=selected,onClick={ selectedFields=if(selected) selectedFields-key else selectedFields+key },
                                label={ Text("$label (${incomplete.count { key in failedKeys(it) }})") })
                        }
                    }
                    if(selectedFields.isNotEmpty()) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text("Showing ${filtered.size}/${incomplete.size}",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={ selectedFields=emptySet() }) { Text("Clear filters") }
                    }
                    if(!state.ran) Text("No scored labels yet.",style=MaterialTheme.typography.bodySmall)
                    else if(incomplete.isEmpty()) Text("All scored labels are completely correct.",style=MaterialTheme.typography.bodySmall)
                    else if(filtered.isEmpty()) Text("No labels failed the selected filter(s).",style=MaterialTheme.typography.bodySmall)
                }
            }
            items(filtered,key={ "incomplete:${it.sample.image}" }) { row ->
                val open=row.sample.image in expandedRaw
                IncompleteLabel(row,open,{ expandedRaw=if(open) expandedRaw-row.sample.image else expandedRaw+row.sample.image },
                    { expandedImage=row.sample.image })
            }
        }
        item {
            TextButton(onClick={ showAll=!showAll },modifier=Modifier.fillMaxWidth()) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("All labels (${state.samples.size})",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                    Text(if(showAll) "▲" else "▼",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if(showAll) items(state.samples,key={ "all:${it.sample.image}" }) { row ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { Text("${row.displayName} · ${row.result?.status ?: row.status}",style=MaterialTheme.typography.bodySmall) }
                TextButton(onClick={ onRunImage(row.sample.image) },enabled=!state.running && state.bundle.ready) { Text("Run") }
            }
        }
    }
    expandedImage?.let { image -> ZoomableLabelDialog(image=image,onDismiss={ expandedImage=null }) }
    customZoom?.let { image ->
        val context=LocalContext.current
        Dialog(onDismissRequest={ customZoom=null }) {
            AsyncImage(model=ImageRequest.Builder(context).data(GlinerCustomImages.file(context,image)).size(1600).build(),
                contentDescription="Imported image",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxWidth().height(600.dp).clickable { customZoom=null })
        }
    }
    if(state.reviewPrompt) AlertDialog(onDismissRequest=onDismissReviewPrompt,
        title={ Text("Review your images") },
        text={ Text("Check each correct parameter. For an incorrect parameter, enter the correct value or mark it blank.") },
        confirmButton={ TextButton(onClick={ onDismissReviewPrompt(); showCustom=true; scope.launch { listState.animateScrollToItem(2) } }) { Text("Review now") } },
        dismissButton={ TextButton(onClick=onDismissReviewPrompt) { Text("Later") } })
    if(confirmAuditReviews) AlertDialog(onDismissRequest={ confirmAuditReviews=false },
        title={ Text("Save reviewed fields?") },
        text={ Text("Current reviewed fields replace saved references for the same photos. Unreviewed fields remain unchanged; past audit runs are kept.") },
        confirmButton={ TextButton(onClick={ confirmAuditReviews=false; onSaveReviewedAudit() }) { Text("Save") } },
        dismissButton={ TextButton(onClick={ confirmAuditReviews=false }) { Text("Cancel") } })
    if(confirmBuiltInAudit) AlertDialog(onDismissRequest={ confirmBuiltInAudit=false },
        title={ Text("Load built-in audit?") },
        text={ Text("This replaces your current audit references with the built-in 26-photo set. Previous audit runs remain saved.") },
        confirmButton={ TextButton(onClick={ confirmBuiltInAudit=false; onUseBundledAudit() }) { Text("Load") } },
        dismissButton={ TextButton(onClick={ confirmBuiltInAudit=false }) { Text("Cancel") } })
    if(confirmAuditImport) AlertDialog(onDismissRequest={ confirmAuditImport=false },
        title={ Text("Replace audit references?") },
        text={ Text("The selected JSON replaces the saved reference set. Previous audit runs remain saved.") },
        confirmButton={ TextButton(onClick={ confirmAuditImport=false; onImportAudit() }) { Text("Choose JSON") } },
        dismissButton={ TextButton(onClick={ confirmAuditImport=false }) { Text("Cancel") } })
    if(confirmRerunAll || confirmRerunImage!=null) AlertDialog(
        onDismissRequest={ confirmRerunAll=false; confirmRerunImage=null },
        title={ Text("Rerun images?") },
        text={ Text("Rerunning clears the previous predictions and review for the selected images. Export first if you want to keep them.") },
        confirmButton={ TextButton(onClick={
            val image=confirmRerunImage
            val all=confirmRerunAll
            confirmRerunImage=null; confirmRerunAll=false
            if(all) onRunCustom() else if(image!=null) onRunCustomImage(image)
        }) { Text("Rerun") } },
        dismissButton={ TextButton(onClick={ confirmRerunAll=false; confirmRerunImage=null }) { Text("Cancel") } })
    if(confirmClear || confirmRemoveImage!=null) AlertDialog(onDismissRequest={ confirmClear=false; confirmRemoveImage=null },
        title={ Text("Remove imported images?") },
        text={ Text("Private copies and their current results will be removed. Your original photos are not changed.") },
        confirmButton={ TextButton(onClick={
            val image=confirmRemoveImage
            val all=confirmClear
            confirmClear=false; confirmRemoveImage=null
            if(all) onClearCustom() else if(image!=null) onRemoveCustom(image)
        }) { Text("Remove") } },
        dismissButton={ TextButton(onClick={ confirmClear=false; confirmRemoveImage=null }) { Text("Cancel") } })
}

@Composable
private fun AuditReferenceCard(ref:GlinerAuditReference,enabled:Boolean,onSave:(String,String,Boolean)->Unit) {
    var open by remember(ref.image) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            TextButton(onClick={ open=!open }) { Text(ref.name + if(open) " ▲" else " ▼") }
            ref.warnings.forEach { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            if(open) PhoneCorpusMath.fields.forEach { field ->
                AuditReferenceField(ref,field,enabled) { value,blank -> onSave(field,value,blank) }
            }
        }
    }
}

@Composable
private fun AuditReferenceField(ref:GlinerAuditReference,field:String,enabled:Boolean,onSave:(String,Boolean)->Unit) {
    val target=ref.targets.getValue(field)
    var value by remember(ref.image,field,target.display,target.status) { mutableStateOf(target.display) }
    var blank by remember(ref.image,field,target.display,target.status) { mutableStateOf(target.status=="expected_blank") }
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
        OutlinedTextField(value=value,onValueChange={ value=it },singleLine=true,
            enabled=enabled && !blank,label={ Text(GlinerLabRunner.fieldLabels[field] ?: field) },modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=blank,onCheckedChange={ blank=it },enabled=enabled)
            Text("Expected blank",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ onSave(value,blank) },enabled=enabled) { Text("Save") }
        }
        if(!blank && value.isBlank()) Text("Empty text without the checkbox means unreviewed.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AuditResultCard(row:GlinerAuditRow) {
    var open by remember(row.reference.image,row.diagnostics) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            TextButton(onClick={ open=!open }) { Text("${row.reference.name} · ${row.status}" + if(open) " ▲" else " ▼") }
            if(row.status!="complete") Text(row.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(row.exactCorpusMatch) Text("Exact bundled-image match · excluded from totals",style=MaterialTheme.typography.bodySmall)
            if(open && row.status=="complete") SelectionContainer {
                Column {
                    PhoneCorpusMath.fields.forEach { field ->
                        val target=row.reference.targets.getValue(field)
                        val expected=when(target.status) { "expected_blank" -> "(blank)"; "unresolved" -> "(unreviewed)"; else -> target.display }
                        val actual=row.fields[field].orEmpty().ifBlank { "(blank)" }
                        Text("${GlinerLabRunner.fieldLabels[field] ?: field}: $actual · expected $expected · ${row.outcomes[field].orEmpty()}",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomImageReview(
    row:GlinerLabRow,enabled:Boolean,modelReady:Boolean,onRun:()->Unit,onRemove:()->Unit,onZoom:()->Unit,
    onConfirm:(String,Boolean)->Unit,onCorrect:(String,String)->Unit,onBlank:(String,Boolean)->Unit,onConfirmAll:()->Unit,
) {
    val context=LocalContext.current
    var open by remember(row.sample.image) { mutableStateOf(true) }
    var raw by remember(row.sample.image) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                AsyncImage(model=ImageRequest.Builder(context).data(GlinerCustomImages.file(context,row.sample.image)).size(250).build(),
                    contentDescription="View ${row.displayName}",contentScale=ContentScale.Fit,
                    modifier=Modifier.size(72.dp).clickable(onClick=onZoom))
                Column(Modifier.weight(1f).padding(start=8.dp)) {
                    Text(row.displayName,style=MaterialTheme.typography.titleSmall)
                    Text(row.result?.status ?: row.status,style=MaterialTheme.typography.bodySmall)
                    if(row.sample.matchesCorpus) Text("Exact bundled-image match",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick=onRemove,enabled=enabled) { Text("Remove") }
            }
            Row {
                TextButton(onClick=onRun,enabled=enabled && modelReady) { Text(if(row.result==null) "Run" else "Rerun") }
                if(row.result!=null) TextButton(onClick={ open=!open }) { Text(if(open) "Hide fields" else "Review fields") }
                if(row.result?.status=="complete") TextButton(onClick=onConfirmAll,enabled=enabled) { Text("All correct") }
            }
            val result=row.result
            if(result!=null && result.status!="complete") Text(result.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(open && result?.status=="complete") {
                Text("Check if correct; otherwise enter the correct value or mark it blank.",style=MaterialTheme.typography.bodySmall)
                result.fieldResults.forEach { field ->
                    val review=row.reviews[field.field] ?: GlinerFieldReview()
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=review.confirmed,onCheckedChange={ onConfirm(field.field,it) },enabled=enabled)
                        Column(Modifier.weight(1f)) {
                            Text(field.label,style=MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(field.observed.ifBlank { "(blank)" },style=MaterialTheme.typography.bodyMedium) }
                            Text(when(review.verdict) { "correct" -> "Confirmed"; "incorrect" -> "Correction entered"; else -> "Not reviewed" },style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    if(!review.confirmed) {
                        OutlinedTextField(value=review.corrected,onValueChange={ onCorrect(field.field,it) },
                            label={ Text("Correct ${field.label.lowercase(Locale.ROOT)}") },singleLine=true,
                            enabled=enabled && !review.expectedBlank,modifier=Modifier.fillMaxWidth())
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=review.expectedBlank,onCheckedChange={ onBlank(field.field,it) },enabled=enabled)
                            Text("Correct value is blank",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick={ raw=!raw }) { Text(if(raw) "Hide raw OCR" else "Raw OCR") }
                if(raw) SelectionContainer { Text(result.ocrLines.joinToString("\n"),style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun RunTimers(elapsed:Long,done:Int,total:Int) {
    fun time(ms:Long):String = String.format(Locale.ROOT,"%02dm %02ds",ms/60000,(ms/1000)%60)
    val avg=if(done>0) elapsed/done else 0L
    val remaining=if(done>0) avg*(total-done).coerceAtLeast(0) else 0L
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("Run time" to time(elapsed),"Avg / image" to if(done>0) String.format(Locale.ROOT,"%.1fs",avg/1000f) else "—",
            "Est. remaining" to if(done>0) time(remaining) else "—").forEach { (title,value) ->
            Column(Modifier.weight(1f).border(0.5.dp,MaterialTheme.colorScheme.outlineVariant).padding(6.dp)) {
                Text(title,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value,style=MaterialTheme.typography.bodyMedium,maxLines=1)
            }
        }
    }
}

@Composable
private fun ScoredResults(a:GlinerAggregate,labState:WineAppLabelsState,completedImages:Set<String>) {
    val pass=if(isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val fail=MaterialTheme.colorScheme.error
    val baselines=glinerLabBaselines(labState,completedImages)
    val glinerValues=listOf("GLiNER",a.attempted.toString(),a.eligible.toString(),"${a.complete}/${a.eligible}",a.oneError.toString(),a.multiError.toString(),a.failed.toString())
    val glinerFields=fieldLabels.associate { (key,_) ->
        val correct=a.fieldCorrect[key] ?: 0
        key to WineAppLabelsBuildLog.FieldScore(correct,(a.fieldTotal[key] ?: 0)-correct)
    }
    Column {
        Text("Scored results",style=MaterialTheme.typography.titleMedium)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val headers=listOf("Pipeline","Attempted","Eligible","Fully correct","One error","2+ errors","Failed")
            val rows=listOf(glinerValues)+baselines.map { it.summary ?: listOf(it.title,"—","—","—","—","—","—") }
            Row { headers.forEach { Text(it,Modifier.width(88.dp).padding(4.dp),style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis) } }
            rows.forEach { values ->
                HorizontalDivider(Modifier.width((88*headers.size).dp))
                Row { values.forEach { Text(it,Modifier.width(88.dp).padding(4.dp),style=MaterialTheme.typography.bodySmall) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val columns=fieldLabels+("total_correct" to "Total correct")
            val rows=listOf("GLiNER" to glinerFields)+baselines.map { it.title to it.fields }
            Row {
                Text("Build",Modifier.width(128.dp).padding(4.dp),style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
                columns.forEach { (_,label) -> Text(label,Modifier.width(88.dp).padding(4.dp),style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false) }
            }
            rows.forEach { (title,scores) ->
                HorizontalDivider(Modifier.width((128+88*columns.size).dp))
                Row {
                    Text(title,Modifier.width(128.dp).padding(4.dp),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    columns.forEach { (key,_) ->
                        val score=if(key=="total_correct") {
                            if(fieldLabels.all { it.first in scores }) WineAppLabelsBuildLog.FieldScore(
                                scores.values.sumOf { it.correct },scores.values.sumOf { it.incorrect }) else null
                        } else scores[key]
                        val cell=buildAnnotatedString {
                            if(score==null) append("—") else {
                                withStyle(SpanStyle(color=pass)) { append("${score.correct}") }
                                append("/")
                                withStyle(SpanStyle(color=fail)) { append("${score.incorrect}") }
                            }
                        }
                        Text(cell,Modifier.width(88.dp).padding(4.dp),style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false)
                    }
                }
            }
        }
        if(baselines.any { it.summary==null }) Text(
            if(labState.busy) "Loading saved baselines…" else "— = no saved baseline on this device.",
            style=MaterialTheme.typography.bodySmall)
        if(completedImages.isNotEmpty()) baselines.filter { it.missing>0 }.forEach { baseline ->
            Text("${baseline.title}: ${completedImages.size-baseline.missing}/${completedImages.size} completed photos have saved baseline results.",
                style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun IncompleteLabel(row:GlinerLabRow,open:Boolean,onToggle:()->Unit,onImage:()->Unit) {
    val result=row.result ?: return
    val sample=row.sample
    val failed=result.fieldResults.filter { sample.target(it.field).status!="unresolved" && !PhoneCorpusMath.correct(it.outcome) }
        .map { field -> field.field to mapOf("expected" to field.expected,"recognized" to field.observed,
            "reference_status" to sample.target(field.field).status,"outcome" to field.outcome) }
    fun target(key:String):String = sample.target(key).let { if(it.status=="expected_value") it.display else "(blank)" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                LabelThumbnail(sample.image,onClick=onImage)
                SelectionContainer(Modifier.weight(1f)) {
                    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text("#${sample.reviewNumber} · ${target("producer")}",style=MaterialTheme.typography.titleSmall)
                        Text(target("wine_name"),style=MaterialTheme.typography.bodyMedium)
                        val location=listOf("country","region","subregion","vintage").map(::target).filter { it!="(blank)" }.joinToString(" · ")
                        if(location.isNotEmpty()) Text(location,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sample.sourceFilename,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            if(failed.isNotEmpty()) SelectionContainer { FailedParametersTable(failed) }
            else if(result.status!="complete") SelectionContainer { Text("Execution failed before field scoring: ${result.message}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().clickable(onClick=onToggle).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Raw OCR",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
                Text(if(open) "▲" else "▼",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
            }
            if(open) SelectionContainer {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp,MaterialTheme.colorScheme.outlineVariant).padding(8.dp)) { Diagnostics(result) }
            }
        }
    }
}

/** Everything the collapsed row hides: lanes, probes, residual, timings, per-field state, raw OCR. */
@Composable
private fun Diagnostics(result:GlinerLabImageResult) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        GlinerLane.ordered.forEach { lane ->
            Text("${lane.shortName}: ${result.laneTexts[lane.key].orEmpty().ifBlank { "—" }}",
                style=MaterialTheme.typography.bodySmall)
        }
        result.probes.forEach { Text("probe: $it",style=MaterialTheme.typography.bodySmall) }
        if(result.residualTexts.isNotEmpty()) {
            Text("residual ${result.residualTexts.size}: ${result.residualTexts.joinToString(" | ")}",
                style=MaterialTheme.typography.bodySmall)
        }
        result.fieldResults.forEach { field ->
            Text("${field.label} · ${field.state.ifBlank { "-" }} · ${field.outcome}",
                style=MaterialTheme.typography.bodySmall)
        }
        Text("${result.layout} · ${result.wordCount} w · ${result.spanCount} spans · "+
            "ocr ${result.ocrMs} · gliner ${result.glinerMs} · post ${result.postMs} ms",
            style=MaterialTheme.typography.bodySmall)
        if(result.ocrLines.isNotEmpty()) {
            Text("OCR ${result.ocrLines.size}",style=MaterialTheme.typography.bodySmall,
                fontWeight=FontWeight.SemiBold)
            result.ocrLines.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
        if(result.notes.isNotBlank()) Text(result.notes.trim(),style=MaterialTheme.typography.bodySmall)
    }
}
