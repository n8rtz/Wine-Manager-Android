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
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::choose) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp),
        contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("OCR Text Lab",style=MaterialTheme.typography.headlineSmall)
            Text("Test one word or single text line at a time. Compare raw recognizers—no wine-name correction or field assignment. Photos stay on this device.")
            Button(onClick={picker.launch(arrayOf("image/*"))},enabled=!state.busy) { Text("Choose image") }
            Text(state.message)
            if(state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick=vm::cancel) { Text("Stop") }
            }
        }
        state.photo?.let { photo ->
            item {
                Text("Draw a new box to replace the selection. The full photo is preserved; only the selected rectangle goes to OCR.")
                if(!state.busy) CropCanvas(photo,state.selection,vm::select)
                Text("Upright photo: ${photo.originalW} × ${photo.originalH}. Working image: ${photo.width} × ${photo.height} (maximum edge 1920).",style=MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled=!state.busy,onClick={vm.select(null)}) { Text("Clear box") }
                    TextButton(enabled=!state.busy,onClick={vm.select(TextCrop(0f,0f,1f,1f))}) { Text("Select full image") }
                }
                Row {
                    Checkbox(state.enlarge,{vm.enlarge(it)},enabled=!state.busy)
                    Text("Enlarge crop up to 2× before OCR")
                }
                Row {
                    Checkbox(state.comparePaddle,{vm.compare(it)},enabled=!state.busy)
                    Text("Compare with PP-OCRv5 mobile (experimental)")
                }
                Text("PP-OCRv5 reads a single line, not an entire label. Both engines share an 8-second budget; first use includes model loading. Scores are not directly comparable.",style=MaterialTheme.typography.bodySmall)
                Text("No sharpening or thresholding. ML Kit uses grayscale NV21; PP-OCRv5 uses normalized BGR at 48 px height. Crop output stays within 1280 px width / 1920 px edge.",style=MaterialTheme.typography.bodySmall)
                val pixels=state.selection?.pixels(photo.width,photo.height)
                pixels?.let { Text("Crop: ${it.width} × ${it.height} working pixels") }
                if(state.selection!=null && pixels==null) Text("Box is too small. Select at least 8 × 8 working pixels.")
                Button(enabled=!state.busy && pixels!=null,onClick=vm::run) { Text(if(state.comparePaddle) "Compare OCR on crop" else "Run ML Kit on crop") }
            }
        }
        if(!state.busy && state.cropPreview!=null) item {
            Text("Shared crop (before each model’s input conversion)",style=MaterialTheme.typography.titleMedium)
            AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(state.cropPreview).size(900)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
                contentDescription="Selected text crop",contentScale=ContentScale.Fit,
                modifier=Modifier.fillMaxWidth().heightIn(min=80.dp,max=280.dp))
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
