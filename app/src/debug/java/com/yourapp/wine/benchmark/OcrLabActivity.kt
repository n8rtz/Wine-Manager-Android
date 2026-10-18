package com.yourapp.wine.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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

/** Only packaged/registered in debug. Never applies a benchmark suggestion to the user's cellar form. */
class OcrLabActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { WineTrackerTheme { Surface { OcrLabScreen(onClose={finish()}) } } }
    }
}

@Composable
private fun OcrLabScreen(onClose:()->Unit,vm:OcrLabViewModel=viewModel()) {
    val state by vm.state.collectAsState()
    var producer by rememberSaveable { mutableStateOf("Blackstone") }
    var name by rememberSaveable { mutableStateOf("Merlot") }
    var country by rememberSaveable { mutableStateOf("California") }
    var vintage by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var imageIndex by rememberSaveable(selected) { mutableStateOf(0) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if(uri!=null) { selected=null;vm.start(uri,mapOf(FieldId.PRODUCER to producer,FieldId.WINE_NAME to name,FieldId.COUNTRY to country,FieldId.VINTAGE to vintage)) }
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) vm.export(uri)
    }
    val ranked=remember(state.results) { state.results.filter { it.status=="complete" }.sortedWith(
        compareByDescending<BenchmarkCaseResult> { it.score?.rawNameSimilarity ?: -1.0 }
            .thenByDescending { it.score?.exactFields ?: 0 }) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        item {
            Text("OCR Lab · 30 treatments",style=MaterialTheme.typography.headlineSmall)
            Text("Debug build ${BuildConfig.BUILD_COMMIT}",style=MaterialTheme.typography.bodySmall)
            Text("Local experiments only. No cellar fields are changed. Allow several minutes; keep this screen open. You can stop at any time.")
            Text("Expected answers are for scoring only. Blank means unscored. Presets are editable examples—check them against your photo. Changes apply to the next run.")
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(enabled=!state.busy,onClick={producer="Blackstone";name="Merlot";country="California";vintage=""}) { Text("Blackstone") }
                TextButton(enabled=!state.busy,onClick={producer="Bodegas Olarra";name="Reciente Gran Reserva";country="Spain";vintage=""}) { Text("Olarra") }
            }
            OutlinedTextField(producer,{producer=it.take(120)},label={Text("Expected producer")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(name,{name=it.take(120)},label={Text("Expected wine name")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(country,{country=it.take(120)},label={Text("Expected Country (state for U.S.)")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(vintage,{vintage=it.take(4)},label={Text("Expected vintage (optional)")},enabled=!state.busy,modifier=Modifier.fillMaxWidth())
        }
        item {
            Button(enabled=!state.busy,onClick={
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Choose photo & run 30 cases") }
            if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=vm::cancel) { Text("Stop") } }
            Text(state.progress)
            if(state.results.isNotEmpty()) {
                Text("${state.results.size}/30 cases recorded. Scored against: ${state.expected.values.joinToString(" · ")}")
                Text("Export includes cropped/processed label images, expected answers, OCR text and device/build diagnostics. Share only if comfortable.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!state.busy,onClick={exporter.launch("wine-ocr-lab.zip")}) { Text("Export results + images ZIP") }
            }
            TextButton(onClick=onClose) { Text("Close OCR Lab") }
        }
        if(ranked.isNotEmpty()) item {
            Text("Complete cases — best raw name match first",style=MaterialTheme.typography.titleMedium)
            Text("Raw % = closest 1–3 OCR lines to expected name, not confidence or full-label accuracy. Field matches can include dictionary corrections. Repeat on other photos before choosing a filter.",style=MaterialTheme.typography.bodySmall)
        }
        items(ranked + state.results.filter { it.status!="complete" },key={it.recipe.id}) { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(result.recipe.id,style=MaterialTheme.typography.titleMedium)
                    val score=result.score
                    Text("${result.status} · ${result.report.elapsedMs} ms · raw name ${score?.rawNameSimilarity?.let { "${(it*100).toInt()}%" } ?: "unscored"} · fields ${score?.exactFields}/${score?.checkedFields}")
                    TextButton(enabled=!state.busy,onClick={selected=if(selected==result.recipe.id) null else result.recipe.id}) { Text("Inspect image, OCR and fields") }
                    if(!state.busy && selected==result.recipe.id) {
                        Text(result.recipe.description,style=MaterialTheme.typography.bodySmall)
                        if(result.previews.isNotEmpty()) {
                            val image=result.previews[imageIndex.coerceIn(result.previews.indices)]
                            Text("Prepared image: ${image.name}")
                            if(result.previews.size>1) TextButton(onClick={imageIndex=(imageIndex+1)%result.previews.size}) { Text("Next pass image") }
                            AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(image).size(600)
                                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
                                contentDescription="Processed label for ${result.recipe.id}",modifier=Modifier.fillMaxWidth().height(320.dp))
                        } else Text("No preview saved (early failure or image-storage cap).")
                        Text("Raw OCR: "+result.report.tokens.joinToString(" | ") { "${it.textRaw} (${it.score})" })
                        ScanDiagnosticsPanel(result.report,state.results.indexOf(result).toLong(),allowDebugOverlay=false)
                    }
                }
            }
        }
    }
}
