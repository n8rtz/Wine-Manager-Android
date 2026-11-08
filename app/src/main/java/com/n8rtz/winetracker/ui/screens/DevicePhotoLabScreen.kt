package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.viewmodels.DevicePhotoLabState
import com.yourapp.wine.phonecorpus.DevicePhotoLabResult
import java.io.File

/** Results are deliberately unscored: no standardized-label expected values enter this screen. */
@Composable
internal fun DevicePhotoLabScreen(
    state:DevicePhotoLabState,
    onSelectPhotos:()->Unit,
    onStop:()->Unit,
    onSave:(Int,File)->Unit,
    onClose:()->Unit,
) {
    val clipboard=LocalClipboardManager.current
    val context=LocalContext.current
    fun copyAllText():String = buildString {
        appendLine("Lab v7 · ${BuildConfig.BUILD_COMMIT} · ${state.modules.signatureKey()} · device photos · unscored")
        state.photos.forEach { photo ->
            appendLine("\n${photo.index+1}. ${photo.name} — ${photo.status}")
            DevicePhotoLabResult.fieldLabels.forEach { (key,label) ->
                appendLine("$label: ${photo.result?.fields?.get(key).orEmpty().ifBlank { "(not assigned)" }}")
            }
            photo.result?.let { result ->
                if(result.error.isNotBlank()) appendLine("Error: ${result.error}")
                if(result.incompleteOcr) appendLine("Warning: incomplete OCR")
                if(result.ocrLines.isNotEmpty()) {
                    appendLine("Raw OCR (${result.ocrLines.size} lines):")
                    result.ocrLines.forEach { appendLine(it) }
                }
            }
        }
        if(state.crashReport.isNotBlank()) {
            appendLine("\nPrevious session crash report:")
            appendLine(state.crashReport)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),
    ) {
        item {
            SelectionContainer {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Device wine label audit/test",style=MaterialTheme.typography.headlineSmall)
                    Text("Lab v7 · fresh OCR · build ${BuildConfig.BUILD_COMMIT}")
                    Text("Configuration: ${state.modules.summaryLabel()}")
                    Text("lab_modules=${state.modules.signatureKey()}",style=MaterialTheme.typography.bodySmall)
                    Text("Unscored photos: no standardized dataset, cached OCR, or changes to benchmark scores.")
                    Text("Keep this screen open while running. Save or copy results before leaving this session.")
                    Text("Photo import uses the normal app limit of 32 MiB per image; larger files report an error.")
                    Text(state.message)
                }
            }
        }
        if(state.crashReport.isNotBlank()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Previous session ended unexpectedly",style=MaterialTheme.typography.titleSmall,
                            color=MaterialTheme.colorScheme.error)
                        Text("Crash report (also at the end of Copy all results):",style=MaterialTheme.typography.bodySmall)
                        Text(state.crashReport.take(1200),style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Column {
                Button(onClick=onSelectPhotos,enabled=!state.busy) {
                    Text(if(state.photos.isEmpty()) "Select photos and run Lab v7" else "Select new photos and run again")
                }
                if(state.busy && !state.finished) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
                    TextButton(onClick=onStop) { Text("Stop after current photo") }
                }
                TextButton(onClick=onClose,enabled=!state.busy) { Text("Back to dataset tests") }
            }
        }
        if(state.photos.isNotEmpty() && state.finished) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Save results",style=MaterialTheme.typography.titleMedium)
                    TextButton(enabled=!state.busy,onClick={ clipboard.setText(AnnotatedString(copyAllText())) }) {
                        Text("Copy all results")
                    }
                    state.exports.forEachIndexed { index,file ->
                        TextButton(enabled=!state.busy,onClick={ onSave(index,file) }) {
                            Text("Save ${file.name} · ${"%.2f".format(file.length()/1_000_000.0)} MB")
                        }
                    }
                }
            }
        }
        if(state.photos.isNotEmpty()) {
            item {
                Text(if(state.finished) "Results (${state.photos.size} photos)" else "Photo results",
                    style=MaterialTheme.typography.titleLarge)
                TextButton(enabled=state.finished && !state.busy,onClick={
                    clipboard.setText(AnnotatedString(copyAllText()))
                }) { Text("Copy all results") }
            }
        }
        items(state.photos,key={ "photo-${it.index}" }) { photo ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model=ImageRequest.Builder(context).data(photo.localPhoto ?: photo.uri).size(800).build(),
                        contentDescription="Selected photo ${photo.index+1}: ${photo.name}",
                        modifier=Modifier.fillMaxWidth().height(280.dp),contentScale=ContentScale.Fit,
                    )
                    SelectionContainer {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${photo.index+1}. ${photo.name}",style=MaterialTheme.typography.titleMedium)
                            Text(photo.status)
                            photo.result?.let { result ->
                                if(result.error.isNotBlank()) Text(result.error,color=MaterialTheme.colorScheme.error)
                                if(result.incompleteOcr) Text("OCR was incomplete; these assignments may be partial.")
                            }
                            DevicePhotoLabResult.fieldLabels.forEach { (key,label) ->
                                val value=photo.result?.fields?.get(key).orEmpty()
                                Text("$label: ${value.ifBlank { "(not assigned)" }}")
                            }
                        }
                    }
                    photo.result?.takeIf { it.ocrLines.isNotEmpty() }?.let { result ->
                        Text("Raw OCR (${result.ocrLines.size} lines, first 40 shown)",style=MaterialTheme.typography.titleSmall)
                        result.ocrLines.take(40).forEach { line ->
                            Text(line,style=MaterialTheme.typography.bodySmall)
                        }
                        if(result.ocrLines.size>40) Text("… ${result.ocrLines.size-40} more lines — Copy all results includes the full raw OCR.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if(state.exports.isNotEmpty()) {
            item {
                SelectionContainer {
                    Column {
                        Text("Optional diagnostic export",style=MaterialTheme.typography.titleMedium)
                        Text("Save the manifest and every JSON part. Includes OCR and decisions, not photo bytes.")
                        Text("OCR and filenames can contain private information. The scores CSV is optional.")
                    }
                }
            }
            items(state.exports.withIndex().toList(),key={ "export-${it.index}" }) { (index,file) ->
                TextButton(enabled=!state.busy,onClick={ onSave(index,file) }) {
                    Text("Save ${file.name} · ${"%.2f".format(file.length()/1_000_000.0)} MB")
                }
            }
        }
    }
}
