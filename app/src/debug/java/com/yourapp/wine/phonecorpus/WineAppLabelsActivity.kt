package com.yourapp.wine.phonecorpus

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class LabelTestCase(
    val image: String,
    val reviewNumber: Int,
    val sourceFilename: String,
    val producer: String,
    val wineName: String,
    val country: String,
    val region: String,
    val subregion: String,
    val vintage: String,
)

class WineAppLabelsActivity : ComponentActivity() {
    private val vm: WineAppLabelsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WineTrackerTheme { Surface { Screen() } } }
    }

    override fun onStop() {
        if (!isChangingConfigurations) vm.stop()
        super.onStop()
    }

    @Composable
    private fun Screen() {
        val state by vm.state.collectAsState()
        val context = LocalContext.current
        val cases = remember {
            val scoring = JSONObject(context.assets.open("wine-app-labels-v1/scoring.json").bufferedReader().use { it.readText() })
            val images = scoring.getJSONArray("images")
            (0 until images.length()).map { index ->
                val image = images.getJSONObject(index)
                val fields = image.getJSONObject("fields")
                fun target(field: String): String {
                    val value = fields.getJSONObject(field)
                    return if (value.optString("status") == "expected_value") value.optString("target") else "(blank)"
                }
                LabelTestCase(
                    image = image.getString("image"),
                    reviewNumber = image.getInt("review_number"),
                    sourceFilename = image.getString("source_filename"),
                    producer = target("producer"),
                    wineName = target("wine_name"),
                    country = target("country"),
                    region = target("region"),
                    subregion = target("subregion"),
                    vintage = target("vintage"),
                )
            }
        }
        val coverage = remember {
            val data = JSONObject(context.assets.open("wine-app-labels-v1/gazetteer-coverage.json").bufferedReader().use { it.readText() })
            val databases = data.getJSONArray("databases")
            (0 until databases.length()).map { index ->
                val item = databases.getJSONObject(index)
                item.getString("label") to "${item.getInt("matched_labels")}/${item.getInt("total_labels")}"
            }
        }
        val fieldRows = state.board?.csv.orEmpty().filter { it["row_type"] == "field" }.groupBy { it["image"] }

        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Large image dataset test", style = MaterialTheme.typography.headlineSmall)
                Text("125 retained workbook-linked wine labels · owner-reviewed values · fresh on-device OCR")
                Text("This screen uses all 125 retained rows in the updated workbook. Club des Sommeliers #14 and the marked-red rows were removed from both tabs; IMG_2732.jpeg remains excluded. The full legacy 168-photo run is not replayed.", style = MaterialTheme.typography.bodySmall)
                Text("Private debug test. The source repository declares no image license; do not redistribute these photos beyond authorized project use.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                PhoneCorpusRunner.Mode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.mode == mode, onClick = { vm.load(mode) }, enabled = !state.busy)
                        Text(mode.title)
                    }
                }
                Text("Prod v3.0 reproduces the pre-supplement Lab pipeline with LWIN-only producer data; Lab v4 includes the later supplemental candidate sources.", style = MaterialTheme.typography.bodySmall)
                Text("The comparison table keeps one short row per version: Lab v4, Prod v3.0, Prod v2.0, Prod v1.0. Run each option to populate its results on the same 125 workbook photos. Resume skips completed photos; failures and partial runs can be retried.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text(state.message)
                Text("Checkpointed: ${state.completed}/${state.total}")
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { vm.start() }, enabled = !state.busy && state.completed < state.total) {
                    Text("Start / resume remaining photos")
                }
                Row {
                    TextButton(onClick = vm::stop, enabled = state.busy) { Text("Stop after photo") }
                    TextButton(onClick = { vm.start(retryFailures = true) }, enabled = !state.busy && state.completed > 0) {
                        Text("Retry failed / partial")
                    }
                }
                TextButton(onClick = { vm.load(state.mode, fresh = true) }, enabled = !state.busy) {
                    Text("New run (keep old results)")
                }
            }
            state.board?.let { board ->
                item {
                    Text("Scored results", style = MaterialTheme.typography.titleMedium)
                    Text("Expected blanks are scored as blanks; unresolved workbook references remain unscorable. Partial runs do not establish full-set accuracy.", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            val columnWidth = 88.dp
                            Row {
                                board.headers.forEach {
                                    Text(it, Modifier.width(columnWidth).padding(4.dp), style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            board.rows.forEach { row ->
                                HorizontalDivider(Modifier.width((88 * board.headers.size).dp))
                                Row { row.forEach { Text(it, Modifier.width(columnWidth).padding(4.dp), style = MaterialTheme.typography.bodySmall) } }
                            }
                        }
                    }
                }
            }
            item {
                Text("Gazetteer database coverage", style = MaterialTheme.typography.titleMedium)
                Text("Known workbook values and accepted aliases are matched exactly after normalization. Counts are distinct test labels present in each database, checked on the scoring side only; none of these reference values are passed to OCR.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        val width = 88.dp
                        Row {
                            Text("Coverage", Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.labelSmall)
                            coverage.forEach { (label, _) ->
                                Text(label, Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider(Modifier.width((width.value * (coverage.size + 1)).dp))
                        Row {
                            Text("Labels found", Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.bodySmall)
                            coverage.forEach { (_, count) ->
                                Text(count, Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item { Text("Reviewed labels", style = MaterialTheme.typography.titleMedium) }
            items(cases, key = { it.image }) {
                val results = fieldRows[item.image].orEmpty().associateBy { it["field"] }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabelThumbnail(item.image)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("#${item.reviewNumber} · ${item.producer} — ${item.wineName}", style = MaterialTheme.typography.titleSmall)
                            Text(item.sourceFilename, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(item.country, item.region, item.subregion, item.vintage).filter { it != "(blank)" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            if (results.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 3.dp))
                                listOf("producer", "wine_name", "country", "region", "subregion", "vintage").forEach { field ->
                                    val result = results[field] ?: return@forEach
                                    val expected = result["expected"].takeUnless { it.isNullOrBlank() } ?: result["reference_status"].orEmpty()
                                    val actual = result["recognized"].orEmpty().ifBlank { "(blank)" }
                                    Text("$field: $actual  ·  ${result["outcome"]}  ·  expected $expected", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelThumbnail(image: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, image) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                context.assets.open("wine-app-labels-v1/images/$image").use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = image, modifier = Modifier.size(width = 82.dp, height = 112.dp), contentScale = ContentScale.Fit)
    } else {
        Spacer(Modifier.size(width = 82.dp, height = 112.dp))
    }
}
