package com.yourapp.wine.phonecorpus

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
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

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) vm.onBackgroundPause(resumeWhenForeground = !isFinishing)
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
        var showDiagnostics by remember { mutableStateOf(true) }
        var expandedDiagnostics by remember(state.mode) { mutableStateOf(emptySet<String>()) }

        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Large image dataset test", style = MaterialTheme.typography.headlineSmall)
                Text("125 retained workbook-linked wine labels · owner-reviewed values · fresh on-device OCR")
                Text("This normal-app benchmark uses all 125 retained rows in the updated workbook. Club des Sommeliers #14 and the marked-red rows were removed from both tabs; IMG_2732.jpeg remains excluded. The full legacy 168-photo run is not replayed.", style = MaterialTheme.typography.bodySmall)
                Text("Private on-device benchmark. The source repository declares no image license; do not redistribute these photos or an APK containing them beyond authorized project use.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                PhoneCorpusRunner.Mode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.mode == mode, onClick = { vm.load(mode) }, enabled = !state.busy)
                        Text(mode.title)
                    }
                }
                Text("Lab v4 uses verified LWIN producer data only. Unverified TTB, OSM, and X-Wines name lists are excluded from the producer database.", style = MaterialTheme.typography.bodySmall)
                Text("Only the selected version runs fresh OCR. Other-version rows use already-saved results for the same photos Lab v4 has attempted; they do not run again. Switching apps pauses after the current photo and resumes when you return; if Android kills the process, reopen Lab v4 and tap Resume.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text(state.message)
                Text("${state.mode.title} checkpointed: ${state.completed}/${state.total}")
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = { vm.start() },
                    enabled = !state.busy && state.completed < state.total,
                ) {
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
                    Text("Attempted is the saved-photo tally. Fully correct (for example, 2/5) means all six scorable fields matched on that many photos; it is an accuracy score, not run progress. Expected blanks count as correct when matched; unresolved references are unscorable. Partial runs do not establish full-set accuracy.", style = MaterialTheme.typography.bodySmall)
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
            state.board?.let { board ->
                item {
                    Text("Pipeline bottleneck matrix", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Step-level diagnostics for the selected version only. ML Kit runs before Paddle; post-OCR rows describe the selected pipeline. Counts show passed/failed plus not-reached, not-recorded, and not-applicable cases. Tap + for step definitions.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "Hide diagnostics table" else "Show diagnostics table")
                    }
                    if (showDiagnostics) SelectionContainer {
                        val stepWidth = 320.dp
                        val fieldWidth = 88.dp
                        val fieldTitles = PhoneCorpusMath.fields.map { field ->
                            if (field == "wine_name") "Wine name" else field.replaceFirstChar { it.uppercase() }
                        }
                        val gridColor = MaterialTheme.colorScheme.outlineVariant
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val viewportWidth = maxWidth
                            Column(Modifier.horizontalScroll(rememberScrollState())) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Pipeline step",
                                        Modifier.width(stepWidth).heightIn(min = 48.dp).border(1.dp, gridColor).padding(4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    fieldTitles.forEach { title ->
                                        Text(
                                            title,
                                            Modifier.width(fieldWidth).heightIn(min = 48.dp).border(1.dp, gridColor).padding(4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                board.matrix.forEachIndexed { index, row ->
                                    val section = when {
                                        row.step.branch.startsWith("OCR table · ML Kit") -> "OCR 1 · ML KIT"
                                        row.step.branch.startsWith("OCR table · Paddle") -> "OCR 2 · PADDLE"
                                        else -> "POST-OCR REQUIREMENTS"
                                    }
                                    val previous = board.matrix.getOrNull(index - 1)?.step?.branch
                                    val previousSection = when {
                                        previous?.startsWith("OCR table · ML Kit") == true -> "OCR 1 · ML KIT"
                                        previous?.startsWith("OCR table · Paddle") == true -> "OCR 2 · PADDLE"
                                        previous == null -> null
                                        else -> "POST-OCR REQUIREMENTS"
                                    }
                                    if (section != previousSection) {
                                        Text(section, Modifier.width(viewportWidth).padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                                    }
                                    val expanded = row.step.id in expandedDiagnostics
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Row(
                                            Modifier.width(stepWidth).heightIn(min = 56.dp).border(1.dp, gridColor).padding(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    expandedDiagnostics = if (expanded) expandedDiagnostics - row.step.id else expandedDiagnostics + row.step.id
                                                },
                                                modifier = Modifier.width(40.dp),
                                                contentPadding = PaddingValues(0.dp),
                                            ) { Text(if (expanded) "−" else "+") }
                                            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                                                Text(row.step.title, style = MaterialTheme.typography.bodySmall)
                                                Text(row.step.branch, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (row.shared != null) {
                                            Text(
                                                "${row.shared.passed}/${row.shared.failed} · NR ${row.shared.notReached} · ? ${row.shared.notRecorded} · N/A ${row.shared.notApplicable}",
                                                Modifier.width(fieldWidth * PhoneCorpusMath.fields.size).heightIn(min = 48.dp).border(1.dp, gridColor).padding(4.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                            )
                                        } else {
                                            PhoneCorpusMath.fields.forEach { field ->
                                                val cell = row.fields[field]
                                                Text(
                                                    cell?.let { "${it.passed}/${it.failed} · NR ${it.notReached} · ? ${it.notRecorded} · N/A ${it.notApplicable}" } ?: "—",
                                                    Modifier.width(fieldWidth).heightIn(min = 48.dp).border(1.dp, gridColor).padding(4.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    if (expanded) {
                                        Text(
                                            row.step.definition,
                                            Modifier.width(viewportWidth).border(1.dp, gridColor).padding(start = 48.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("Gazetteer database coverage", style = MaterialTheme.typography.titleMedium)
                Text("Known workbook values and accepted aliases are matched exactly after normalization. Counts are distinct test labels present in each database, checked on the scoring side only; unverified TTB, OSM, and X-Wines lists are not packaged or passed to OCR.", style = MaterialTheme.typography.bodySmall)
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
            items(cases, key = { it.image }) { wineCase ->
                val results = fieldRows[wineCase.image].orEmpty().associateBy { it["field"] }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabelThumbnail(wineCase.image)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("#${wineCase.reviewNumber} · ${wineCase.producer} — ${wineCase.wineName}", style = MaterialTheme.typography.titleSmall)
                            Text(wineCase.sourceFilename, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(wineCase.country, wineCase.region, wineCase.subregion, wineCase.vintage).filter { it != "(blank)" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
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
