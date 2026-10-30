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
 * The screen shows the new structure top to bottom: PP-OCRv6, the GLiNER-Small INT4 graph that
 * labels the three lanes, and the existing Lab v7 post-processing that turns those lanes into
 * producer / wine name / country / region / subregion / vintage. It runs the same packaged
 * 124-label dataset as the Lab v7 screen and scores with the same target semantics.
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
    onThreshold:(Float)->Unit,
    onMaxWidth:(Int)->Unit,
    onToggleRow:(String)->Unit,
    onClose:()->Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp,vertical=12.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),
    ) {
        item {
            SelectionContainer {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("GLiNER lane lab",style=MaterialTheme.typography.headlineSmall)
                    Text("ML Kit Latin + PP-OCRv6 ➔ GLiNER-Small INT4 ➔ existing Lab v7 post-processing",style=MaterialTheme.typography.titleSmall)
                    Text("Build ${BuildConfig.BUILD_COMMIT} · ${BuildConfig.BUILD_BRANCH} · ${BuildConfig.NATIVE_ABIS}")
                    Text("Same 124 packaged workbook labels as Lab v7. Fresh OCR every photo; nothing is written to a wine form.")
                    Text("Post-OCR module set: ${state.modules.signatureKey()}",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { ArchitectureCard(state) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("2 · GLiNER-Small INT4 bundle",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                    Text(state.bundle.summary())
                    state.bundle.files.forEach { file ->
                        Text("${file.role}: ${file.name} · ${if(file.present) "${file.bytes} bytes" else "missing"}"+
                            (if(file.pinned) " · sha ${file.sha256.take(12)}…" else " · unpinned")+
                            (if(file.present && file.issue.isNotBlank()) " · ${file.issue}" else ""),
                            style=MaterialTheme.typography.bodySmall)
                    }
                    if(state.bundle.int4) Text("INT4 detected: MatMulNBits nodes present in the graph.",style=MaterialTheme.typography.bodySmall)
                    else Text("INT4 not confirmed: the graph has no MatMulNBits node.",style=MaterialTheme.typography.bodySmall)
                    state.bundle.notes.forEach { Text("• $it",style=MaterialTheme.typography.bodySmall) }
                    Text("Tokenizer: ${state.tokenizer}",style=MaterialTheme.typography.bodySmall)
                    Text("Import the export produced by tools/fetch-gliner-small-int4.py: the INT4 ONNX, tokenizer.json and gliner_config.json. Files are copied into app-private storage; the lab never downloads at run time.",
                        style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick=onImport,enabled=!state.running) { Text("Import model files") }
                        TextButton(onClick=onClearBundle,enabled=!state.running) { Text("Remove imported files") }
                        TextButton(onClick=onRefresh,enabled=!state.running) { Text("Re-check") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("3 · Run",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                    Text(state.laneConfig.summaryLabel(),style=MaterialTheme.typography.bodySmall)
                    Text("Prompts: "+GlinerLane.ordered.joinToString(" · ") { "${it.shortName}=\"${state.laneConfig.promptFor(it)}\"" },
                        style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick={ onThreshold(-0.05f) },enabled=!state.running) { Text("Threshold −") }
                        TextButton(onClick={ onThreshold(0.05f) },enabled=!state.running) { Text("Threshold +") }
                        TextButton(onClick={ onMaxWidth(-2) },enabled=!state.running) { Text("Width −") }
                        TextButton(onClick={ onMaxWidth(2) },enabled=!state.running) { Text("Width +") }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick=onRunAll,enabled=!state.running && state.bundle.ready) {
                            Text("Run all ${state.total} labels")
                        }
                        TextButton(onClick=onStop,enabled=state.running) { Text("Stop") }
                        TextButton(onClick=onSaveCsv,enabled=!state.running && state.aggregate!=null) { Text("Save scored CSV") }
                    }
                    if(state.running) {
                        LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                        Text("${state.done}/${state.total} · ${state.current}",style=MaterialTheme.typography.bodySmall)
                    }
                    if(state.message.isNotBlank()) Text(state.message,style=MaterialTheme.typography.bodyMedium)
                    val aggregate=state.aggregate
                    if(aggregate!=null) {
                        HorizontalDivider(modifier=Modifier.padding(vertical=4.dp))
                        Text(aggregate.text(),fontWeight=FontWeight.SemiBold)
                        Text("Per-field correct: "+aggregate.fieldCorrect.entries.joinToString(" · ") { (field,value) ->
                            "$field $value/${aggregate.fieldTotal[field] ?: 0}"
                        },style=MaterialTheme.typography.bodySmall)
                        Text("Same accepted-value normalization as the Lab v7 scoreboard; unattempted labels are excluded.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(state.exports.isNotEmpty()) {
                        Text("Exported: ${state.exports.joinToString(", ") { it.name }}",style=MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick=onClose) { Text("Close") }
                }
            }
        }
        items(state.samples,key={ it.sample.image }) { row ->
            DatasetRow(row,expanded=row.sample.image in state.expanded,enabled=!state.running,
                onToggle={ onToggleRow(row.sample.image) },onRun={ onRunImage(row.sample.image) })
        }
    }
}

@Composable
private fun ArchitectureCard(state:GlinerLabState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("1 · Pipeline",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
            Text("ML Kit Latin + PP-OCRv6 Medium det+rec → both engines read the same decoded label, started together, "+
                "then their lines are fused. "+(if(state.modelsReady) "Pinned PP-OCRv6 models present." else "Pinned PP-OCRv6 models missing in this build."),
                style=MaterialTheme.typography.bodySmall)
            Text("GLiNER-Small INT4 → labels PRODUCER / WINE_NAME / APPELLATION and keeps the unclaimed text as residual observations",
                style=MaterialTheme.typography.bodySmall)
            Text("Existing post-processing → evidence extractor, producer repair, producer gazetteer, linked-wine resolver, Lab v7 modules",
                style=MaterialTheme.typography.bodySmall)
            Text("Each fused line keeps every engine that read it with that engine's own measured score, so the existing "+
                "two-engine rules still see the ensemble rather than one fused engine.",
                style=MaterialTheme.typography.bodySmall)
            Text("Country, region, subregion and vintage are never asked from GLiNER: they stay with the existing hierarchy and year rules.",
                style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DatasetRow(row:GlinerLabRow,expanded:Boolean,enabled:Boolean,onToggle:()->Unit,onRun:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick=onToggle)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${row.index+1}. ${row.displayName}",style=MaterialTheme.typography.bodyMedium)
                Text(row.result?.let { if(it.complete) "correct" else "${it.errorCount} error(s)" } ?: row.status,
                    style=MaterialTheme.typography.bodySmall)
            }
            val result=row.result
            if(result!=null) {
                Text(result.fields.entries.joinToString(" · ") { (field,value) -> "$field=${value.ifBlank { "—" }}" },
                    style=MaterialTheme.typography.bodySmall)
            }
            if(expanded) {
                HorizontalDivider(modifier=Modifier.padding(vertical=4.dp))
                if(result==null) {
                    Text("Not run yet.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=onRun,enabled=enabled) { Text("Run this label only") }
                } else {
                    Detail(result)
                }
            }
        }
    }
}

@Composable
private fun Detail(result:GlinerLabImageResult) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text("Lanes",style=MaterialTheme.typography.titleSmall)
        GlinerLane.ordered.forEach { lane ->
            Text("${lane.shortName}: ${result.laneTexts[lane.key].orEmpty().ifBlank { "—" }}",style=MaterialTheme.typography.bodySmall)
        }
        Text("residual: ${result.residualTexts.joinToString(" | ").ifBlank { "—" }}",style=MaterialTheme.typography.bodySmall)
        Text("Fields",style=MaterialTheme.typography.titleSmall)
        result.fieldResults.forEach { field ->
            Text("${field.label}: ${field.observed.ifBlank { "—" }} · expected ${field.expected.ifBlank { "—" }} · ${field.outcome}",
                style=MaterialTheme.typography.bodySmall)
        }
        Text("OCR lines (${result.ocrLines.size})",style=MaterialTheme.typography.titleSmall)
        result.ocrLines.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
        Text("${result.layout} · ${result.wordCount} words · ${result.spanCount} spans · ${result.modelSignature}"+
            if(result.int4) " · INT4" else " · not INT4",style=MaterialTheme.typography.bodySmall)
        Text("timings: ocr ${result.ocrMs} ms · gliner ${result.glinerMs} ms · post ${result.postMs} ms · total ${result.totalMs} ms",
            style=MaterialTheme.typography.bodySmall)
        if(result.message.isNotBlank()) Text(result.message,style=MaterialTheme.typography.bodySmall)
        if(result.notes.isNotBlank()) Text(result.notes.trim(),style=MaterialTheme.typography.bodySmall)
    }
}
