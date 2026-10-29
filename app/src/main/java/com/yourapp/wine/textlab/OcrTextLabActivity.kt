package com.yourapp.wine.textlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme

class OcrTextLabActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WineTrackerTheme { Surface { TextLabScreen(onClose={finish()}) } } }
    }
}

@Composable
private fun TextLabScreen(onClose:()->Unit,vm:TextLabViewModel=viewModel()) {
    val state by vm.state.collectAsState()
    val clipboard=LocalClipboardManager.current
    var showPastRuns by remember { mutableStateOf(false) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(uris.isNotEmpty()) vm.choose(uris) }
    val csvExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportCsv) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp),
        contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("OCR Text Lab",style=MaterialTheme.typography.headlineSmall)
            Text("Test the production ML Kit and PP-OCRv6 Medium engines on multiple images. Full images are selected by default; optional crops remain per image. Photos stay on this device.")
            Button(onClick={picker.launch(arrayOf("image/*"))},enabled=!state.busy) { Text("Choose images") }
            Text("Choosing again replaces the image queue, not your saved CSV.",style=MaterialTheme.typography.bodySmall)
            Text(state.message)
            Text("Saved runs are kept in this app's private storage and restored when Text Lab opens again. Clearing app data removes them.",style=MaterialTheme.typography.bodySmall)
            if(state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick=vm::cancel) { Text("Stop") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Past runs (${state.savedRuns.size})",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={showPastRuns=!showPastRuns},enabled=state.savedRuns.isNotEmpty()) {
                    Text(if(showPastRuns) "Hide" else "Browse")
                }
            }
            if(state.savedRuns.isEmpty()) Text("No saved runs yet. Completed and checkpointed batches will appear here.",style=MaterialTheme.typography.bodySmall)
        }
        if(showPastRuns) items(state.savedRuns,key={it.file.name}) { run ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(if(run.file.nameWithoutExtension==state.viewingRunId) "Currently viewing · ${run.file.nameWithoutExtension}" else run.file.nameWithoutExtension,
                            style=MaterialTheme.typography.titleSmall)
                        Text("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date(run.modifiedAt))} · ${run.bytes/1024} KB",
                            style=MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled=!state.busy,onClick={vm.openSavedRun(run.file)}) { Text("View") }
                }
            }
        }
        if(state.images.isNotEmpty()) item {
            Text("Image ${state.activeImage+1} of ${state.images.size}: ${state.current?.name}",style=MaterialTheme.typography.titleMedium)
            Row {
                TextButton(enabled=!state.busy && state.activeImage>0,onClick={vm.showImage(state.activeImage-1)}) { Text("Previous") }
                TextButton(enabled=!state.busy && state.activeImage<state.images.lastIndex,onClick={vm.showImage(state.activeImage+1)}) { Text("Next") }
            }
            state.current?.error?.let { Text(it+". This image's failure will be included in the batch CSV.") }
        }
        state.photo?.let { photo ->
            item {
                Text("This crop belongs only to this image. Previous/Next keeps each image's selection.")
                if(!state.busy) CropCanvas(photo,state.selection,vm::select)
                Text("Upright photo: ${photo.originalW} × ${photo.originalH}. Working: ${photo.width} × ${photo.height} (maximum edge 1920).",style=MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled=!state.busy,onClick={vm.select(null)}) { Text("Clear box") }
                    TextButton(enabled=!state.busy,onClick={vm.select(TextCrop(0f,0f,1f,1f))}) { Text("Select full image") }
                }
                val pixels=state.selection?.pixels(photo.width,photo.height)
                if(pixels==null) Text("Select at least 8 × 8 pixels, or select the full image.")
                else Text("Crop: ${pixels.width} × ${pixels.height} working pixels")
            }
        }
        if(state.images.isNotEmpty()) item {
            Row {
                Checkbox(state.enlarge,{vm.enlarge(it)},enabled=!state.busy)
                Text("Enlarge every selected crop up to 2×")
            }
            Text("Candidates for every image",style=MaterialTheme.typography.titleMedium)
            TextLabChoices.all.forEach { candidate ->
                Row {
                    Checkbox(candidate.id in state.selectedCandidates,{ enabled ->
                        vm.candidate(candidate.id,enabled)
                    },enabled=!state.busy)
                    Text(candidate.title)
                }
            }
            Row {
                Checkbox(state.detectLines,{vm.detectLines(it)},enabled=!state.busy)
                Text("Detect text lines first (full-label mode)")
            }
            Row {
                Checkbox(state.compareEvidence,{vm.compareEvidence(it)},enabled=!state.busy)
                Text("Compare experimental evidence pipeline (keeps production baseline)")
            }
            Row {
                Checkbox(state.compareGazetteer,{vm.compareGazetteer(it)},enabled=!state.busy)
                Text("Compare SQLite trigram gazetteer (experimental, keeps baseline)")
            }
            if(state.compareGazetteer) Text("Appellation role tags before producer rules; YELLOW suggestions only. Dataset provenance and real OCR accuracy are not yet validated.",style=MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(state.residualCandidates,{vm.residualCandidates(it)},enabled=!state.busy)
                Text("Find residual producer/cuvée candidates (experimental)")
            }
            if(state.residualCandidates) Text("Tags known text and retains the remaining fragments for review. Not automatic producer or wine-name assignment. Raw text and qualifiers remain in CSV.",style=MaterialTheme.typography.bodySmall)
            if(state.compareEvidence) {
                Row {
                    Checkbox(state.anchorHybrid,{vm.anchorHybrid(it)},enabled=!state.busy)
                    Text("Use guarded hybrid (baseline producer/vintage + revised wine/origin)")
                }
                if(state.anchorHybrid) {
                    Row {
                        Checkbox(state.repairProducer,{vm.repairProducer(it)},enabled=!state.busy)
                        Text("Repair producers with corroborated full names (experimental)")
                    }
                    Text("Producer-only repair; preserves the other five hybrid fields. Conservative two-engine mode, not title-based guesses. All suggestions require confirmation.",style=MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(state.repairPlaces,{vm.repairPlaces(it)},enabled=!state.busy)
                        Text("Repair appellations with bounded spelling (experimental)")
                    }
                    if(state.repairPlaces) Text("Appellation-only repair of an unreadable phrase; needs a separate printed origin anchor. Both engines' readings are searched, so one engine is enough. One or two edits only, no ambiguous pick. Suggestions are never written to the form.",style=MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(state.resolverSingleReading,{vm.resolverSingleReading(it)},enabled=!state.busy)
                        Text("Accept one engine reading for producer and linked wine (experimental)")
                    }
                    if(state.resolverSingleReading) Text("Lab v5 only; Prod v4.0 keeps the two-engine rule. Same eligibility, edit distance, single-identity, separation and existing-value rules, so the difference measured is the engine quorum alone.",style=MaterialTheme.typography.bodySmall)
                }
                Text("Off: previous evidence architecture. On: development candidate, not validated on fresh labels. Incomplete producer headings remain blank. Production is unchanged.",style=MaterialTheme.typography.bodySmall)
            }
            Text("Experimental extraction uses the same OCR readings, not another model run. Requires text-line detection. Both results are exported; no intake fields are changed.",style=MaterialTheme.typography.bodySmall)
            Text("Images and candidates run sequentially. Each candidate and each image's ML Kit reference gets ${TextLabBudgets.OCR_MS / 1000} seconds. Allow up to about ${(state.selectedCandidates.size+1)} minutes PER IMAGE plus preparation; native calls may overrun. Stop preserves partial and unrun results.",style=MaterialTheme.typography.bodySmall)
            Text("Sessions start fresh; files may be cached. Scores are not directly comparable. With detection off, crop every image to one line. The field table uses shared local rules and is not production autofill.",style=MaterialTheme.typography.bodySmall)
            if(!state.cropsReady) Text("One or more loaded images needs a valid crop. Review with Previous/Next or select full image.")
            if(state.selectedCandidates.isEmpty()) Text("Enable at least one candidate.")
            Button(enabled=!state.busy && state.cropsReady && state.selectedCandidates.isNotEmpty(),onClick=vm::run) { Text("Run all ${state.images.size} images") }
        }
        if(!state.busy && state.cropPreview!=null) item {
            Text("Test crop for the reviewed image",style=MaterialTheme.typography.titleMedium)
            AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(state.cropPreview).size(900)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
                contentDescription="Selected text crop",contentScale=ContentScale.Fit,
                modifier=Modifier.fillMaxWidth().heightIn(min=80.dp,max=280.dp))
        }
        if(state.lastCsv!=null) item {
            Button(enabled=!state.busy,onClick={csvExporter.launch("wine-ocr-batch-${state.lastCsv?.name}")}) { Text(if(state.viewingRunId!=null) "Export displayed run CSV" else "Export last batch CSV") }
            Text("One CSV includes every queued image and enabled candidate, including errors and partial results. Selected saved run: ${state.lastCsv?.name}",style=MaterialTheme.typography.bodySmall)
        }
        if(state.formTable.isNotEmpty()) item {
            Text("Scanning form preview · combined engines",style=MaterialTheme.typography.titleMedium)
            Text("One baseline row per wine, plus an experimental row when selected. Same saved readings, not a camera/shutter replay. Confirm suggestions before saving.",style=MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row { TextLabWineTable.formHeaders.forEach { Text(it,Modifier.width(170.dp).padding(8.dp),style=MaterialTheme.typography.labelLarge) } }
                    state.formTable.forEach { row ->
                        HorizontalDivider()
                        Row { row.forEach { Text(it,Modifier.width(170.dp).padding(8.dp),style=MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
        if(state.wineTable.isNotEmpty()) item {
            Text("Head-to-head wine fields",style=MaterialTheme.typography.titleMedium)
            Text("Suggestions, not ground truth. — means unknown; † marks a spelling suggestion (details in CSV). Swipe horizontally for all six fields. Each engine/pass is independent in this table; values require confirmation.",style=MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row { TextLabWineTable.headers.forEach { Text(it,Modifier.width(170.dp).padding(8.dp),style=MaterialTheme.typography.labelLarge) } }
                    state.wineTable.forEach { row ->
                        HorizontalDivider()
                        Row { row.forEach { Text(it,Modifier.width(170.dp).padding(8.dp),style=MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
        if(state.result.isNotBlank()) item {
            TextButton(onClick={clipboard.setText(AnnotatedString(state.result))}) { Text("Copy OCR result") }
            SelectionContainer { Text(state.result) }
        }
        item { TextButton(onClick=onClose) { Text("Close Text Lab") } }
    }
}

/** Box aspect is exactly the upright preview aspect: no letterbox offsets enter normalized drag coordinates. */
@Composable
private fun CropCanvas(photo:TextPhoto,crop:TextCrop?,onCrop:(TextCrop?)->Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat()/photo.height)) {
        AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(photo.preview).size(900)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
            contentDescription="Draw a rectangle over the text to recognize",contentScale=ContentScale.FillBounds,
            modifier=Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize().pointerInput(photo.preview) {
            var start=Offset.Zero
            detectDragGestures(onDragStart={ point ->
                start=point
                onCrop(TextCrop.between(point.x/size.width,point.y/size.height,point.x/size.width,point.y/size.height))
            },onDragCancel={onCrop(null)},onDrag={ change,_ ->
                change.consume()
                onCrop(TextCrop.between(start.x/size.width,start.y/size.height,
                    change.position.x/size.width,change.position.y/size.height))
            })
        }) {
            crop?.let {
                val topLeft=Offset(it.left*size.width,it.top*size.height)
                val extent=Size((it.right-it.left)*size.width,(it.bottom-it.top)*size.height)
                drawRect(Color.Yellow.copy(alpha=.15f),topLeft,extent)
                drawRect(Color.Black,topLeft,extent,style=Stroke(5.dp.toPx()))
                drawRect(Color.Yellow,topLeft,extent,style=Stroke(2.dp.toPx()))
            }
        }
    }
}
