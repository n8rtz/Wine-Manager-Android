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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.viewmodels.GlinerAggregate
import com.n8rtz.winetracker.viewmodels.GlinerLabRow
import com.n8rtz.winetracker.viewmodels.GlinerLabState
import com.n8rtz.winetracker.viewmodels.aggregateGlinerRows
import com.yourapp.wine.gliner.GlinerLabImageResult
import com.yourapp.wine.gliner.GlinerLane
import com.yourapp.wine.phonecorpus.FailedParametersTable
import com.yourapp.wine.phonecorpus.LabelThumbnail
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import com.yourapp.wine.phonecorpus.WineAppLabelsState
import com.yourapp.wine.phonecorpus.WineAppLabelsBuildLog
import com.yourapp.wine.phonecorpus.glinerLabBaselines
import com.yourapp.wine.phonecorpus.ZoomableLabelDialog
import kotlinx.coroutines.delay
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
    onSaveCsv:()->Unit, onSaveJson:()->Unit, onReport:()->String,
    onThreshold:(Float)->Unit, onMaxWidth:(Int)->Unit, onToggleRow:(String)->Unit, onClose:()->Unit,
) {
    val clipboard=LocalClipboardManager.current
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
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp,vertical=12.dp),
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
        item {
            SelectionContainer {
                ScoredResults(state.aggregate ?: aggregateGlinerRows(state.samples,state.stopped),labState,
                    state.samples.filter { it.sample.included && it.result!=null }.map { it.sample.image }.toSet())
            }
        }
        if(state.ran) {
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
