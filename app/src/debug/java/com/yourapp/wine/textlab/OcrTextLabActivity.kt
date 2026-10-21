package com.yourapp.wine.textlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    val context=LocalContext.current
    var confirmIndra by remember { mutableStateOf<String?>(null) }
    if(confirmIndra!=null) AlertDialog(onDismissRequest={confirmIndra=null},
        title={Text("Indra model terms and benchmark scope")},
        text={ Column {
            Text("Datalab's models use a modified OpenRAIL-M license, including use restrictions, attribution and output share-alike terms. Enable only if you accept those terms. The enhanced option enables orientation, deskew, adaptive detection and base-language-model rescoring; the raw option omits them. The 12-pass option tests scales, deskew and contrast within one shared 60-second budget. These use the base recognizer, not full multilingual script routing or document layout. The CSV records raw and rescored readings.")
            TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://huggingface.co/datalab-to/indra-ocr/blob/v1.1.0/LICENSE")))}) { Text("Read model license") }
        } },
        confirmButton={TextButton(onClick={confirmIndra?.let { vm.candidate(it,true) };confirmIndra=null}) { Text("Accept and enable") }},
        dismissButton={TextButton(onClick={confirmIndra=null}) { Text("Cancel") }})
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(uris.isNotEmpty()) vm.choose(uris) }
    val csvExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportCsv) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp),
        contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("OCR Text Lab",style=MaterialTheme.typography.headlineSmall)
            Text("Choose multiple images and compare OCR pipelines. Full images are selected by default; optionally draw a separate crop on each. Indra enhanced includes general language-model rescoring; no wine-specific correction or field assignment. Photos stay on this device.")
            Button(onClick={picker.launch(arrayOf("image/*"))},enabled=!state.busy) { Text("Choose images") }
            Text("Choosing again replaces the image queue, not your saved CSV.",style=MaterialTheme.typography.bodySmall)
            Text(state.message)
            if(state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick=vm::cancel) { Text("Stop") }
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
                        if(candidate.id in setOf(IndraSpec.ID,IndraSpec.ENHANCED_ID,IndraSpec.MULTIPASS_ID) && enabled) confirmIndra=candidate.id else vm.candidate(candidate.id,enabled)
                    },enabled=!state.busy)
                    Text(candidate.title)
                }
            }
            Row {
                Checkbox(state.detectLines,{vm.detectLines(it)},enabled=!state.busy)
                Text("Detect text lines first (full-label mode)")
            }
            Text("Images and candidates run sequentially. Each candidate and each image's ML Kit reference gets ${TextLabBudgets.OCR_MS / 1000} seconds. Allow up to about ${(state.selectedCandidates.size+1)} minutes PER IMAGE plus preparation; native calls may overrun. Stop preserves partial and unrun results.",style=MaterialTheme.typography.bodySmall)
            Text("Sessions start fresh per candidate; files may be cached. Scores are not directly comparable. With detection off, crop every image to one line; candidates 2 and 3 then use the same recognizer. Option 6 tests 12 Indra variants with a shared 60-second budget. Option 5 runs enhanced once; option 4 is the raw baseline. Repeated passes are not independent votes. Model terms apply to exported Indra output.",style=MaterialTheme.typography.bodySmall)
            if(!state.cropsReady) Text("One or more loaded images needs a valid crop. Review with Previous/Next or select full image.")
            if(state.selectedCandidates.isEmpty()) Text("Enable at least one candidate.")
            val passModeReady=IndraSpec.MULTIPASS_ID !in state.selectedCandidates || state.detectLines
            if(!passModeReady) Text("Enable Detect text lines first for the 12-pass experiment, or uncheck option 6.")
            Button(enabled=!state.busy && state.cropsReady && state.selectedCandidates.isNotEmpty() && passModeReady,onClick=vm::run) { Text("Run all ${state.images.size} images") }
        }
        if(!state.busy && state.cropPreview!=null) item {
            Text("Test crop for the reviewed image",style=MaterialTheme.typography.titleMedium)
            AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(state.cropPreview).size(900)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
                contentDescription="Selected text crop",contentScale=ContentScale.Fit,
                modifier=Modifier.fillMaxWidth().heightIn(min=80.dp,max=280.dp))
        }
        if(state.lastCsv!=null) item {
            Button(enabled=!state.busy,onClick={csvExporter.launch("wine-ocr-batch-${state.lastCsv?.name}")}) { Text("Export last batch CSV") }
            Text("One CSV includes every queued image and enabled candidate, including errors and partial results. Saved batch: ${state.lastCsv?.name}",style=MaterialTheme.typography.bodySmall)
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
