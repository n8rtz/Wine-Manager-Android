package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.viewmodels.GlinerLabRow
import com.n8rtz.winetracker.viewmodels.GlinerLabState
import com.yourapp.wine.gliner.GlinerLabImageResult
import com.yourapp.wine.gliner.GlinerLane

/**
 * Settings > Scanner testing > GLiNER lane lab.
 *
 * Laid out like the 124-label benchmark page: a compact header, the run controls, then tables -
 * one row per label with a column per field, and the same expandable `+` cell that opens the
 * detail. Every cell is inside a [SelectionContainer] and "Copy all results" puts the whole run on
 * the clipboard, so a bad run can be pasted into a chat instead of screenshotted.
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
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=12.dp,vertical=10.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),
    ) {
        item {
            SelectionContainer {
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text("GLiNER lane lab",style=MaterialTheme.typography.headlineSmall)
                    Text("build ${BuildConfig.BUILD_COMMIT} · ${state.laneConfig.signatureKey()}",
                        style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
                    verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(if(state.bundle.ready) "model ready · INT4 "+(if(state.bundle.int4) "✓" else "✗")
                                else "model missing · ${state.bundle.summary()}",
                                style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
                            if(state.modelMs>0) Text("load ${state.modelMs} ms · bundle ${state.bundleMs} ms",
                                style=MaterialTheme.typography.bodySmall)
                            if(state.tokenizer!="not loaded") Text(state.tokenizer,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick=onImport,enabled=!state.running) { Text("Import") }
                        TextButton(onClick=onClearBundle,enabled=!state.running) { Text("Remove") }
                        TextButton(onClick=onRefresh,enabled=!state.running) { Text("Re-check") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
                    verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick={ onThreshold(-0.05f) },enabled=!state.running,
                                contentPadding=PaddingValues(horizontal=8.dp)) { Text("thr −") }
                            TextButton(onClick={ onThreshold(0.05f) },enabled=!state.running,
                                contentPadding=PaddingValues(horizontal=8.dp)) { Text("thr +") }
                            TextButton(onClick={ onMaxWidth(-2) },enabled=!state.running,
                                contentPadding=PaddingValues(horizontal=8.dp)) { Text("wid −") }
                            TextButton(onClick={ onMaxWidth(2) },enabled=!state.running,
                                contentPadding=PaddingValues(horizontal=8.dp)) { Text("wid +") }
                        }
                        SelectionContainer {
                            Text("${"%.2f".format(state.laneConfig.threshold)} · w${state.laneConfig.maxWidth}",
                                style=MaterialTheme.typography.bodySmall)
                        }
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
                            Text("${state.done}/${state.total} · ${state.current}",
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    SelectionContainer {
                        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            if(state.message.isNotBlank()) Text(state.message,style=MaterialTheme.typography.bodySmall)
                            state.aggregate?.let { Text(it.text(),style=MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if(state.ran) {
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick={ clipboard.setText(AnnotatedString(onReport())) }) { Text("Copy all") }
                    TextButton(onClick=onSaveJson,enabled=!state.running) { Text("Save JSON") }
                    TextButton(onClick=onSaveCsv,enabled=!state.running) { Text("Save CSV") }
                }
            }
            state.aggregate?.let { aggregate ->
                item {
                    Text("Per field",style=MaterialTheme.typography.titleMedium)
                    FieldScoreTable(aggregate.fieldCorrect,aggregate.fieldTotal)
                }
            }
            item {
                Text(if(state.finished) "Labels (${state.samples.count { it.result!=null }}/${state.samples.size})"
                    else "Labels",style=MaterialTheme.typography.titleMedium)
            }
            items(state.samples,key={ it.sample.image }) { row ->
                LabelRow(row,expanded=row.sample.image in state.expanded,enabled=!state.running,
                    onToggle={ onToggleRow(row.sample.image) },
                    onRun={ onRunImage(row.sample.image) })
            }
        }
    }
}

/** Field | Correct | Scored, the same three-column shape as the benchmark page's score table. */
@Composable
private fun FieldScoreTable(correct:Map<String,Int>,total:Map<String,Int>) {
    val passColor=if(isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val fieldWidth=140.dp
    val numberWidth=64.dp
    val rows=total.keys.ifEmpty { correct.keys }.toList()
    SelectionContainer {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Text("Field",Modifier.width(fieldWidth).padding(4.dp),
                    style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
                Text("Correct",Modifier.width(numberWidth).padding(4.dp),
                    style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
                Text("Scored",Modifier.width(numberWidth).padding(4.dp),
                    style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
            }
            rows.forEach { key ->
                HorizontalDivider(Modifier.width(fieldWidth+numberWidth*2))
                val scored=total[key] ?: 0
                val right=correct[key] ?: 0
                val color=when { scored==0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    right==scored -> passColor else -> MaterialTheme.colorScheme.error }
                Row {
                    Text(key,Modifier.width(fieldWidth).padding(4.dp),
                        style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false)
                    Text("$right",Modifier.width(numberWidth).padding(4.dp),
                        style=MaterialTheme.typography.bodySmall,color=color,maxLines=1,softWrap=false)
                    Text("$scored",Modifier.width(numberWidth).padding(4.dp),
                        style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false)
                }
            }
        }
    }
}

/**
 * One label, collapsed: a `Parameter | Expected | Actual` table for the six fields, with the actual
 * value green when it matches, red when it does not, and grey when the pipeline left it blank.
 * Everything else - lanes, probes, timings, raw OCR - hides behind one "Raw OCR" toggle that starts
 * closed, which is how the device photo lab keeps its rows readable.
 */
@Composable
private fun LabelRow(
    row:GlinerLabRow,
    expanded:Boolean,
    enabled:Boolean,
    onToggle:()->Unit,
    onRun:()->Unit,
) {
    var open by remember(row.sample.image) { mutableStateOf(false) }
    val passColor=if(isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val nameWidth=96.dp
    val valueWidth=150.dp
    val result=row.result
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("${row.index+1}. ${row.displayName}",style=MaterialTheme.typography.bodyMedium)
                    Text(result?.let { "${it.status} · ${it.errorCount} err" } ?: row.status,
                        style=MaterialTheme.typography.bodySmall)
                }
            }
            if(result!=null) {
                SelectionContainer {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row {
                            Text("Parameter",Modifier.width(nameWidth).padding(4.dp),
                                style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
                            Text("Expected",Modifier.width(valueWidth).padding(4.dp),
                                style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
                            Text("Actual",Modifier.width(valueWidth).padding(4.dp),
                                style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
                        }
                        result.fieldResults.forEach { field ->
                            HorizontalDivider(Modifier.width(nameWidth+valueWidth*2))
                            val color=when {
                                field.observed.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                                field.observed.trim().equals(field.expected.trim(),ignoreCase=true) -> passColor
                                else -> MaterialTheme.colorScheme.error
                            }
                            Row {
                                Text(field.label,Modifier.width(nameWidth).padding(4.dp),
                                    style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false)
                                Text(field.expected.ifBlank { "—" },Modifier.width(valueWidth).padding(4.dp),
                                    style=MaterialTheme.typography.bodySmall,maxLines=1,
                                    overflow=TextOverflow.Ellipsis,softWrap=false)
                                Text(field.observed.ifBlank { "—" },Modifier.width(valueWidth).padding(4.dp),
                                    style=MaterialTheme.typography.bodySmall,color=color,maxLines=1,
                                    overflow=TextOverflow.Ellipsis,softWrap=false)
                            }
                        }
                    }
                }
                if(!result.message.isBlank()) {
                    SelectionContainer { Text(result.message,style=MaterialTheme.typography.bodySmall) }
                }
                val lines=result.ocrLines.size
                TextButton(onClick={ open=!open },contentPadding=PaddingValues(0.dp)) {
                    Text(if(open) "− Raw OCR ($lines lines)" else "+ Raw OCR ($lines lines)",
                        style=MaterialTheme.typography.bodySmall)
                }
                if(open) {
                    SelectionContainer { Diagnostics(result) }
                }
            } else {
                Text(if(expanded) "not run" else row.status,style=MaterialTheme.typography.bodySmall)
                if(!expanded) {
                    TextButton(onClick=onToggle,enabled=enabled) { Text("Select") }
                }
                if(expanded) {
                    TextButton(onClick=onRun,enabled=enabled) { Text("Run this label") }
                }
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
