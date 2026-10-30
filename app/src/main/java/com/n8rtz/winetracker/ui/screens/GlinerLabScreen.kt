package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.viewmodels.GlinerLabRow
import com.n8rtz.winetracker.viewmodels.GlinerLabState
import com.yourapp.wine.gliner.GlinerLabImageResult
import com.yourapp.wine.gliner.GlinerLane

/**
 * Settings > Scanner testing > GLiNER lane lab.
 *
 * One screen, three cards: the model, the run, the results. Every string is inside a
 * [SelectionContainer], and "Copy all results" puts the whole run on the clipboard so a problem can
 * be pasted into a chat without screenshots.
 */
@Composable
internal fun GlinerLabScreen(
    state:GlinerLabState,
    onImport:()->Unit,
    onClearBundle:()->Unit,
    onRefresh:()->Unit,
    onRunAll:()->Unit,
    onRunImage:(String)->Unit,
    onStop:()->Unit,
    onSaveCsv:()->Unit,
    onSaveJson:()->Unit,
    onReport:()->String,
    onThreshold:(Float)->Unit,
    onMaxWidth:(Int)->Unit,
    onToggleRow:(String)->Unit,
    onClose:()->Unit,
) {
    val clipboard=LocalClipboardManager.current
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=14.dp,vertical=10.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),
    ) {
        item {
            SelectionContainer {
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text("GLiNER lane lab",style=MaterialTheme.typography.headlineSmall)
                    Text("ML Kit Latin + PP-OCRv6 ➔ GLiNER-Small INT4 ➔ Lab v7",style=MaterialTheme.typography.bodySmall)
                    Text("build ${BuildConfig.BUILD_COMMIT} · ${state.laneConfig.signatureKey()}",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Model",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                        Text(if(state.bundle.ready) "ready · ${state.bundle.files.joinToString(", ") { it.name }}" else state.bundle.summary())
                        if(state.modelMs>0) Text("load ${state.modelMs} ms · bundle ${state.bundleMs} ms · session ${state.modelMs} ms")
                        Text(if(state.bundle.int4) "INT4 confirmed (MatMulNBits)" else "INT4 not confirmed",
                            style=MaterialTheme.typography.bodySmall)
                        if(state.tokenizer!="not loaded") Text("tokenizer: ${state.tokenizer}",style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick=onImport,enabled=!state.running) { Text("Import files") }
                            TextButton(onClick=onClearBundle,enabled=!state.running) { Text("Remove") }
                            TextButton(onClick=onRefresh,enabled=!state.running) { Text("Re-check") }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Run",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                        SelectionContainer {
                            Text("threshold ${"%.2f".format(state.laneConfig.threshold)} · width ${state.laneConfig.maxWidth}",
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick={ onThreshold(-0.05f) },enabled=!state.running) { Text("thr −") }
                        TextButton(onClick={ onThreshold(0.05f) },enabled=!state.running) { Text("thr +") }
                        TextButton(onClick={ onMaxWidth(-2) },enabled=!state.running) { Text("wid −") }
                        TextButton(onClick={ onMaxWidth(2) },enabled=!state.running) { Text("wid +") }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick=onRunAll,enabled=!state.running && state.bundle.ready) {
                            Text(if(state.finished) "Run all again" else "Run all ${state.total}")
                        }
                        TextButton(onClick=onStop,enabled=state.running) { Text("Stop") }
                        TextButton(onClick=onClose) { Text("Close") }
                    }
                    if(state.running) {
                        LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                        SelectionContainer {
                            Text("${state.done}/${state.total} · ${state.current}",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    SelectionContainer {
                        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            if(state.message.isNotBlank()) Text(state.message,style=MaterialTheme.typography.bodySmall)
                            state.aggregate?.let { aggregate ->
                                Text(aggregate.text(),style=MaterialTheme.typography.bodySmall)
                                Text("per field: "+aggregate.fieldCorrect.entries.joinToString(" · ") { (field,value) ->
                                    "$field $value/${aggregate.fieldTotal[field] ?: 0}"
                                },style=MaterialTheme.typography.bodySmall)
                            }
                            if(state.ran) {
                                Text("Country, region, subregion and vintage stay with the Lab v7 post-processing.",
                                    style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if(state.ran) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick={ clipboard.setText(AnnotatedString(onReport())) }) {
                                Text("Copy all results")
                            }
                            TextButton(onClick=onSaveJson,enabled=!state.running) { Text("Save JSON") }
                            TextButton(onClick=onSaveCsv,enabled=!state.running) { Text("Save CSV") }
                        }
                        SelectionContainer {
                            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                if(state.exports.isNotEmpty()) {
                                    Text("saved: "+state.exports.joinToString(", ") { it.name },style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        items(state.samples,key={ it.sample.image }) { row ->
            ResultRow(row,expanded=row.sample.image in state.expanded,enabled=!state.running,
                onToggle={ onToggleRow(row.sample.image) },onRun={ onRunImage(row.sample.image) })
        }
    }
}

@Composable
private fun ResultRow(row:GlinerLabRow,expanded:Boolean,enabled:Boolean,onToggle:()->Unit,onRun:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick=onToggle)) {
        Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            SelectionContainer {
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("${row.index+1}. ${row.displayName}",style=MaterialTheme.typography.bodyMedium)
                        Text(row.result?.let { "${it.status} · ${it.errorCount} err" } ?: row.status,
                            style=MaterialTheme.typography.bodySmall)
                    }
                    val result=row.result
                    if(result!=null) {
                        result.fieldResults.forEach { field ->
                            Text("${field.label}: ${field.observed.ifBlank { "—" }}",style=MaterialTheme.typography.bodySmall)
                        }
                        if(expanded) {
                            HorizontalDivider(modifier=Modifier.padding(vertical=4.dp))
                            Detail(result)
                        }
                    } else if(expanded) {
                        Text("Not run yet.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(expanded && row.result==null) TextButton(onClick=onRun,enabled=enabled) { Text("Run this label") }
        }
    }
}

@Composable
private fun Detail(result:GlinerLabImageResult) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text("probes (best span per lane, before threshold)",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
        if(result.probes.isEmpty()) Text("none",style=MaterialTheme.typography.bodySmall)
        result.probes.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
        GlinerLane.ordered.forEach { lane ->
            Text("${lane.shortName}: ${result.laneTexts[lane.key].orEmpty().ifBlank { "—" }}",style=MaterialTheme.typography.bodySmall)
        }
        if(result.residualTexts.isNotEmpty()) {
            Text("residual: ${result.residualTexts.joinToString(" | ")}",style=MaterialTheme.typography.bodySmall)
        }
        result.fieldResults.forEach { field ->
            Text("${field.label}: ${field.observed.ifBlank { "—" }} · ${field.state.ifBlank { "-" }} · ${field.outcome} · expected ${field.expected.ifBlank { "—" }}",
                style=MaterialTheme.typography.bodySmall)
        }
        Text("${result.layout} · ${result.wordCount} words · ${result.spanCount} spans",style=MaterialTheme.typography.bodySmall)
        Text("ocr ${result.ocrMs} ms · gliner ${result.glinerMs} ms · post ${result.postMs} ms · total ${result.totalMs} ms",
            style=MaterialTheme.typography.bodySmall)
        if(result.message.isNotBlank()) Text(result.message,style=MaterialTheme.typography.bodySmall)
        if(result.ocrLines.isNotEmpty()) {
            Text("OCR (${result.ocrLines.size})",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
            result.ocrLines.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
        if(result.notes.isNotBlank()) Text(result.notes.trim(),style=MaterialTheme.typography.bodySmall)
    }
}
