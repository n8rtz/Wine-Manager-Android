package com.yourapp.wine.phonecorpus

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/** Bottleneck-matrix cell: passed counts green, failed counts red, matching the corpus screen. */
private fun passFailLabel(passed: Int, failed: Int, passColor: Color, failColor: Color, shared: Boolean = false, notReached: Int = 0, notRecorded: Int = 0, notApplicable: Int = 0): AnnotatedString = buildAnnotatedString {
    if (shared) append("Shared ")
    withStyle(SpanStyle(color = passColor)) { append(passed.toString()) }
    append("/")
    withStyle(SpanStyle(color = failColor)) { append(failed.toString()) }
    if (notReached + notRecorded + notApplicable > 0) append(" · NR $notReached · ? $notRecorded · NA $notApplicable")
}

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

/** Measured diagnostics-matrix widths: each column is as wide as its own longest single-line text. */
private data class MatrixWidths(val step: Dp, val fields: List<Dp>) {
    val total: Dp get() = fields.fold(step) { total, width -> total + width }
}

private val FAILURE_FILTER_OPTIONS = listOf(
    "producer" to "Producer",
    "wine_name" to "Wine name",
    "country" to "Country",
    "region" to "Region",
    "subregion" to "Subregion",
    "vintage" to "Vintage",
)

private val FIELD_DISPLAY_NAMES = FAILURE_FILTER_OPTIONS.toMap()

private fun isFieldFailure(row: Map<String, String>): Boolean =
    row["reference_status"] != "unresolved" && !PhoneCorpusMath.correct(row["outcome"].orEmpty())

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

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val state by vm.state.collectAsState()
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        var exportIndex by rememberSaveable { mutableStateOf(0) }
        // Same save flow as the legacy Large image dataset test page: prepare, then Save each part.
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { vm.saveExportPart(it, exportIndex) }
        }
        val splitExisting = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(vm::splitExistingCsv)
        }
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
            }.sortedBy { it.reviewNumber }
        }
        val coverage = remember {
            val data = JSONObject(context.assets.open("wine-app-labels-v1/gazetteer-coverage.json").bufferedReader().use { it.readText() })
            val parameters = data.getJSONArray("parameters")
            (0 until parameters.length()).map { index ->
                val item = parameters.getJSONObject(index)
                item.getString("label") to "${item.getInt("matched_labels")}/${item.getInt("total_labels")}"
            }
        }
        val fieldRows = state.board?.csv.orEmpty().filter { it["row_type"] == "field" }.groupBy { it["image"] }
        val failedFieldsByImage = remember(fieldRows) {
            fieldRows.mapNotNull { (image, rows) ->
                val key = image ?: return@mapNotNull null
                val failed = rows.filter(::isFieldFailure).mapNotNull { it["field"] }.toSet()
                val incomplete = failed.isNotEmpty() || rows.any { it["form_status"] == "execution_failed" }
                if (incomplete) key to failed else null
            }.toMap()
        }
        val incompleteCases = remember(cases, failedFieldsByImage) {
            cases.filter { it.image in failedFieldsByImage }
        }
        var showDiagnostics by remember { mutableStateOf(true) }
        var expandedDiagnostics by remember(state.mode) { mutableStateOf(emptySet<String>()) }
        var showIncompleteLabels by remember { mutableStateOf(false) }
        var selectedFailureFields by remember { mutableStateOf(emptySet<String>()) }
        var expandedRawOcrImages by remember(state.mode) { mutableStateOf(emptySet<String>()) }
        var expandedImage by rememberSaveable { mutableStateOf<String?>(null) }
        var nowRealtimeMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(state.labRunning) {
            if (state.labRunning) {
                while (true) {
                    nowRealtimeMs = SystemClock.elapsedRealtime()
                    delay(250L)
                }
            } else {
                nowRealtimeMs = SystemClock.elapsedRealtime()
            }
        }
        val filteredIncompleteCases = remember(incompleteCases, failedFieldsByImage, selectedFailureFields) {
            if (selectedFailureFields.isEmpty()) {
                incompleteCases
            } else {
                incompleteCases.filter { wineCase ->
                    failedFieldsByImage[wineCase.image].orEmpty().any { it in selectedFailureFields }
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Large image dataset test", style = MaterialTheme.typography.headlineSmall)
                        Text("124 retained workbook-linked wine labels · owner-reviewed values · fresh on-device OCR")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Post-OCR test modules", style = MaterialTheme.typography.titleSmall)
                            DisableSelection {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { vm.setAllLabModules(false) },
                                        enabled = !state.busy && state.labModules != LabPostOcrModules.none,
                                    ) { Text("All off") }
                                    TextButton(
                                        onClick = { vm.setBestBuildLabModules() },
                                        enabled = !state.busy && state.labModules != LabPostOcrModules.bestBuild,
                                    ) { Text("Best build") }
                                    TextButton(
                                        onClick = { vm.setAllLabModules(true) },
                                        enabled = !state.busy && state.labModules != LabPostOcrModules.all,
                                    ) { Text("All on") }
                                }
                            }
                            Text(
                                "Best build = the 5 modules your best Lab v5 run (636dda970fa4) used.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "All off = pre-improvement baseline. Each combo isolates its own run & keeps Cached OCR.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LabPostOcrModules.ModuleId.entries.forEach { moduleId ->
                                val checked = state.labModules.isEnabled(moduleId)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    DisableSelection {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { vm.setLabModule(moduleId, it) },
                                            enabled = !state.busy,
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(moduleId.uiTitle, style = MaterialTheme.typography.bodyMedium)
                                        Text(moduleId.uiDetail, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val mode = PhoneCorpusRunner.Mode.LAB
                SelectionContainer {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(mode.title)
                            Text(
                                if (state.useCachedOcr) {
                                    "Cached OCR (${state.ocrCached}/${state.total} ready) · ${state.labCompleted}/${state.total} checkpointed"
                                } else {
                                    "Runs fresh OCR on every pass · ${state.labCompleted}/${state.total} checkpointed"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DisableSelection {
                                Checkbox(
                                    checked = state.useCachedOcr,
                                    onCheckedChange = vm::setUseCachedOcr,
                                    enabled = !state.busy,
                                )
                            }
                            Text("Cached OCR", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.message.isNotBlank()) Text(state.message)
                        Text("Lab v5 checkpointed: ${state.labCompleted}/${state.total}")
                        val frozen = state.captures.values.map { it.count }.minOrNull() ?: 0
                        Text(
                            if (state.captures.size == 3 && frozen >= state.total) "Prod v1.0/v2.0/v4.0 frozen on this device: $frozen/${state.total} photos"
                            else "Prod baselines captured: $frozen/${state.total} photos · ${state.captureRemaining} still to capture",
                        )
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = { vm.start() },
                    enabled = !state.busy && (state.labCompleted < state.total || state.captureRemaining > 0),
                ) {
                    Text(if (state.captureRemaining > 0) "Start / resume (first pass also captures Prod)" else "Start / resume Lab v5 only")
                }
                SelectionContainer {
                    BenchmarkTimersRow(state = state, nowRealtimeMs = nowRealtimeMs)
                }
                Row {
                    TextButton(onClick = vm::stop, enabled = state.busy) { Text("Stop after photo") }
                    TextButton(
                        onClick = { vm.start(retryFailures = true) },
                        enabled = !state.busy && (state.labCompleted > 0 || state.captures.isNotEmpty()),
                    ) { Text("Retry failed / partial") }
                }
                TextButton(onClick = { vm.load(PhoneCorpusRunner.Mode.LAB, fresh = true) }, enabled = !state.busy) {
                    Text("New Lab run (keeps old results and frozen Prod)")
                }
            }
            state.board?.let { board ->
                item {
                    SelectionContainer {
                        Column {
                            Text("Scored results", style = MaterialTheme.typography.titleMedium)
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
                            Spacer(Modifier.height(8.dp))
                            BuildFieldComparisonTable(
                                currentScores = state.currentBuildFieldScores,
                                bestBuildScores = state.bestBuildFieldScores,
                                prodV4Scores = state.prodV4FieldScores,
                            )
                        }
                    }
                }
                item {
                    SelectionContainer {
                        Text("CSV export", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString("Build ${BuildConfig.BUILD_COMMIT}\n" + board.text)) },
                        enabled = !state.busy,
                    ) { Text("Copy scored stats") }
                    Button(
                        onClick = { vm.prepareExport(raw = false) },
                        enabled = !state.busy && board.csv.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Prepare scored CSV export") }
                    OutlinedButton(
                        onClick = { vm.prepareExport(raw = true) },
                        enabled = !state.busy && board.csv.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Prepare raw diagnostics CSV export") }
                    state.exportParts.forEachIndexed { index, file ->
                        OutlinedButton(
                            onClick = { exportIndex = index; export.launch(file.name) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Save ${if (state.exportParts.size > 1) "part ${index + 1}/${state.exportParts.size}" else "CSV"} · ${"%.2f".format(file.length() / 1_000_000.0)} MB")
                        }
                    }
                    TextButton(
                        onClick = { splitExisting.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) },
                        enabled = !state.busy,
                    ) { Text("Split an existing CSV for upload") }
                }
            }
            state.board?.let { board ->
                item {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Pipeline bottleneck matrix", style = MaterialTheme.typography.titleMedium)
                            Text("Passed (green) / failed (red). Tap + for a step definition.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "Hide diagnostics table" else "Show diagnostics table")
                    }
                    if (showDiagnostics) SelectionContainer {
                        val fieldTitles = PhoneCorpusMath.fields.map { field ->
                            when (field) {
                                "wine_name" -> "Wine name"
                                "subregion" -> "Sub-region"
                                else -> field.replaceFirstChar { it.uppercase() }
                            }
                        }
                        val gridColor = MaterialTheme.colorScheme.outlineVariant
                        val passColor = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
                        val failColor = MaterialTheme.colorScheme.error
                        val labelStyle = MaterialTheme.typography.labelSmall
                        val cellStyle = MaterialTheme.typography.bodySmall
                        val measurer = rememberTextMeasurer()
                        val density = LocalDensity.current
                        // Columns are measured, not guessed: every step label, header and cell gets
                        // the width its own text needs on ONE line, so words never wrap onto two or
                        // three lines. The grid scrolls horizontally only when the measured width
                        // exceeds the screen.
                        fun textWidth(text: AnnotatedString, style: TextStyle): Dp = with(density) {
                            measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp()
                        }
                        // 4.dp padding on each side plus a little rounding headroom so a measured
                        // line is never clipped by a fractional-pixel boundary.
                        val cellPadding = 10.dp
                        val expandButtonWidth = 24.dp
                        val matrixWidths = remember(board, labelStyle, cellStyle, passColor, failColor) {
                            val stepText = listOf(AnnotatedString("Step")) +
                                board.matrix.map { AnnotatedString(it.step.shortTitle) }
                            val step = stepText.maxOf { textWidth(it, labelStyle) } + cellPadding + expandButtonWidth
                            val fields = PhoneCorpusMath.fields.mapIndexed { index, field ->
                                val cells = board.matrix.mapNotNull { row ->
                                    row.fields[field]?.let {
                                        passFailLabel(it.passed, it.failed, passColor, failColor, notReached = it.notReached,
                                            notRecorded = it.notRecorded, notApplicable = it.notApplicable)
                                    }
                                }
                                maxOf(
                                    textWidth(AnnotatedString(fieldTitles[index]), labelStyle),
                                    cells.maxOfOrNull { textWidth(it, cellStyle) } ?: 0.dp,
                                ) + cellPadding
                            }
                            // A shared step spans all six field columns; if its own text is wider,
                            // the last column grows so it still fits on one line.
                            val shared = board.matrix.mapNotNull { it.shared }.maxOfOrNull { cell ->
                                textWidth(passFailLabel(cell.passed, cell.failed, passColor, failColor, shared = true,
                                    notReached = cell.notReached, notRecorded = cell.notRecorded, notApplicable = cell.notApplicable), cellStyle)
                            } ?: 0.dp
                            val fieldsTotal = fields.fold(0.dp) { total, width -> total + width }
                            val widened = if (shared + cellPadding > fieldsTotal) {
                                fields.mapIndexed { index, width ->
                                    if (index == fields.lastIndex) width + (shared + cellPadding - fieldsTotal) else width
                                }
                            } else fields
                            MatrixWidths(step = step, fields = widened)
                        }
                        val stepWidth = matrixWidths.step
                        val fieldWidths = matrixWidths.fields
                        val gridWidth = matrixWidths.total
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Step",
                                    Modifier.width(stepWidth).heightIn(min = 48.dp).border(1.dp, gridColor).padding(4.dp),
                                    style = labelStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                fieldTitles.forEachIndexed { index, title ->
                                    Text(
                                        title,
                                        Modifier.width(fieldWidths[index]).heightIn(min = 48.dp).border(1.dp, gridColor).padding(horizontal = 4.dp, vertical = 4.dp),
                                        style = labelStyle,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        softWrap = false,
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
                                    Text(section, Modifier.width(gridWidth).padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                                }
                                val expanded = row.step.id in expandedDiagnostics
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        Modifier.width(stepWidth).heightIn(min = 56.dp).border(1.dp, gridColor).padding(horizontal = 2.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        DisableSelection {
                                            TextButton(
                                                onClick = {
                                                    expandedDiagnostics = if (expanded) expandedDiagnostics - row.step.id else expandedDiagnostics + row.step.id
                                                },
                                                modifier = Modifier.width(24.dp),
                                                contentPadding = PaddingValues(0.dp),
                                            ) { Text(if (expanded) "−" else "+") }
                                        }
                                        Text(
                                            row.step.shortTitle,
                                            Modifier.weight(1f),
                                            style = labelStyle,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    if (row.shared != null) {
                                        Text(
                                            passFailLabel(row.shared.passed, row.shared.failed, passColor, failColor, shared = true, notReached = row.shared.notReached, notRecorded = row.shared.notRecorded, notApplicable = row.shared.notApplicable),
                                            Modifier.width(fieldWidths.fold(0.dp) { total, width -> total + width }).heightIn(min = 48.dp).border(1.dp, gridColor).padding(horizontal = 4.dp, vertical = 4.dp),
                                            style = cellStyle,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    } else {
                                        PhoneCorpusMath.fields.forEachIndexed { index, field ->
                                            val cell = row.fields[field]
                                            Text(
                                                cell?.let { passFailLabel(it.passed, it.failed, passColor, failColor, notReached = it.notReached, notRecorded = it.notRecorded, notApplicable = it.notApplicable) } ?: AnnotatedString("—"),
                                                Modifier.width(fieldWidths[index]).heightIn(min = 48.dp).border(1.dp, gridColor).padding(horizontal = 4.dp, vertical = 4.dp),
                                                style = cellStyle,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                                if (expanded) {
                                    Column(
                                        Modifier.width(gridWidth).border(1.dp, gridColor).padding(horizontal = 8.dp, vertical = 8.dp),
                                    ) {
                                        Text(row.step.title, style = MaterialTheme.typography.titleSmall)
                                        Text(row.step.branch, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(row.step.definition, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SelectionContainer {
                    Column {
                        Text("Database coverage", style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            val width = 88.dp
                            Row {
                                Text("Parameter", Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, softWrap = false)
                                coverage.forEach { (label, _) ->
                                    Text(label, Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, softWrap = false)
                                }
                            }
                            HorizontalDivider(Modifier.width((width.value * (coverage.size + 1)).dp))
                            Row {
                                Text("Labels found", Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, softWrap = false)
                                coverage.forEach { (_, count) ->
                                    Text(count, Modifier.width(width).padding(4.dp), style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { showIncompleteLabels = !showIncompleteLabels }, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Incomplete labels (${incompleteCases.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (showIncompleteLabels) "▲" else "▼", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (showIncompleteLabels) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FAILURE_FILTER_OPTIONS.forEach { (fieldKey, label) ->
                                val selected = fieldKey in selectedFailureFields
                                val count = incompleteCases.count { fieldKey in failedFieldsByImage[it.image].orEmpty() }
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedFailureFields = if (selected) {
                                            selectedFailureFields - fieldKey
                                        } else {
                                            selectedFailureFields + fieldKey
                                        }
                                    },
                                    label = { Text("$label ($count)") },
                                )
                            }
                        }
                        if (selectedFailureFields.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Showing ${filteredIncompleteCases.size}/${incompleteCases.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { selectedFailureFields = emptySet() }) {
                                    Text("Clear filters")
                                }
                            }
                        }
                        if (fieldRows.isEmpty()) {
                            Text("No scored labels yet.", style = MaterialTheme.typography.bodySmall)
                        } else if (incompleteCases.isEmpty()) {
                            Text("All scored labels are completely correct.", style = MaterialTheme.typography.bodySmall)
                        } else if (filteredIncompleteCases.isEmpty()) {
                            Text("No labels failed the selected filter(s).", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(filteredIncompleteCases, key = { it.image }) { wineCase ->
                    val results = fieldRows[wineCase.image].orEmpty().associateBy { it["field"] }
                    val failedResults = PhoneCorpusMath.fields.mapNotNull { field ->
                        results[field]?.takeIf(::isFieldFailure)?.let { field to it }
                    }
                    val showRawOcr = wineCase.image in expandedRawOcrImages
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LabelThumbnail(wineCase.image, onClick = { expandedImage = wineCase.image })
                                SelectionContainer(Modifier.weight(1f)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "#${wineCase.reviewNumber} · ${wineCase.producer}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            wineCase.wineName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        val locationLine = listOf(
                                            wineCase.country,
                                            wineCase.region,
                                            wineCase.subregion,
                                            wineCase.vintage,
                                        ).filter { it != "(blank)" }.joinToString(" · ")
                                        if (locationLine.isNotEmpty()) {
                                            Text(
                                                locationLine,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            wineCase.sourceFilename,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (failedResults.isNotEmpty()) {
                                SelectionContainer {
                                    FailedParametersTable(failedResults)
                                }
                            } else if (results.values.any { it["form_status"] == "execution_failed" }) {
                                SelectionContainer {
                                    Text(
                                        "Execution failed before field scoring.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Column {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedRawOcrImages = if (showRawOcr) {
                                                expandedRawOcrImages - wineCase.image
                                            } else {
                                                expandedRawOcrImages + wineCase.image
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Raw OCR",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (showRawOcr) "▲" else "▼",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (showRawOcr) {
                                    val rawOcr by produceState<String?>(
                                        initialValue = null,
                                        wineCase.image,
                                        state.mode,
                                        state.labCompleted,
                                    ) {
                                        value = vm.loadRawOcrText(wineCase.image, state.mode)
                                    }
                                    SelectionContainer {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                                .padding(8.dp),
                                        ) {
                                            Text(
                                                rawOcr ?: "Loading OCR...",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        expandedImage?.let { image ->
            ZoomableLabelDialog(image = image, onDismiss = { expandedImage = null })
        }
    }
}

@Composable
private fun FailedParametersTable(failedResults: List<Pair<String, Map<String, String>>>) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val passColor = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val failColor = MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, gridColor),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Parameter",
                Modifier.weight(0.26f).padding(horizontal = 6.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
            )
            Box(Modifier.fillMaxHeight().width(0.5.dp).background(gridColor))
            Text(
                "Expected",
                Modifier.weight(0.37f).padding(horizontal = 6.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
            )
            Box(Modifier.fillMaxHeight().width(0.5.dp).background(gridColor))
            Text(
                "Output",
                Modifier.weight(0.37f).padding(horizontal = 6.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        failedResults.forEach { (field, result) ->
            val expected = result["expected"].takeUnless { it.isNullOrBlank() }
                ?: if (result["reference_status"] == "expected_blank") "(blank)"
                else result["reference_status"].orEmpty()
            val output = result["recognized"].orEmpty().ifBlank { "(blank)" }
            HorizontalDivider(thickness = 0.5.dp, color = gridColor)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Text(
                    FIELD_DISPLAY_NAMES[field] ?: field,
                    Modifier.weight(0.26f).padding(horizontal = 6.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(Modifier.fillMaxHeight().width(0.5.dp).background(gridColor))
                Text(
                    expected,
                    Modifier.weight(0.37f).padding(horizontal = 6.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = passColor,
                )
                Box(Modifier.fillMaxHeight().width(0.5.dp).background(gridColor))
                Text(
                    output,
                    Modifier.weight(0.37f).padding(horizontal = 6.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = failColor,
                )
            }
        }
    }
}

@Composable
private fun LabelThumbnail(image: String, onClick: () -> Unit) {
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
        Image(
            bitmap!!.asImageBitmap(),
            contentDescription = image,
            modifier = Modifier.size(width = 82.dp, height = 112.dp).clickable(onClick = onClick),
            contentScale = ContentScale.Fit,
        )
    } else {
        Spacer(Modifier.size(width = 82.dp, height = 112.dp).clickable(onClick = onClick))
    }
}

@Composable
private fun ZoomableLabelDialog(image: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val bitmap by produceState<android.graphics.Bitmap?>(null, image) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("wine-app-labels-v1/images/$image").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    var scale by remember(image) { mutableStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clipToBounds()
                .onSizeChanged { containerSize = it }
                .pointerInput(image) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var movedPastSlop = false
                        var multiTouch = currentEvent.changes.count { it.pressed } > 1
                        var wasCanceled = false
                        var totalPan = Offset.Zero
                        var zoomAccumulator = 1f
                        val touchSlop = viewConfiguration.touchSlop
                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (canceled) {
                                wasCanceled = true
                            } else {
                                if (event.changes.count { it.pressed } > 1) multiTouch = true
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (!movedPastSlop) {
                                    zoomAccumulator *= zoomChange
                                    totalPan += panChange
                                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                    val zoomMotion = abs(1f - zoomAccumulator) * centroidSize
                                    if (zoomMotion > touchSlop || totalPan.getDistance() > touchSlop) {
                                        movedPastSlop = true
                                    }
                                }
                                if (movedPastSlop) {
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if ((zoomChange != 1f || panChange != Offset.Zero) && zoomChange.isFinite() && panChange != Offset.Unspecified) {
                                        val oldScale = scale
                                        val newScale = (oldScale * zoomChange).coerceIn(1f, 8f)
                                        val effectiveZoom = newScale / oldScale
                                        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                        val pivot = if (centroid != Offset.Unspecified) centroid else center
                                        val rawOffset = (offset + panChange) * effectiveZoom +
                                            (center - pivot) * (effectiveZoom - 1f)
                                        val maxX = (containerSize.width * (newScale - 1f)) / 2f
                                        val maxY = (containerSize.height * (newScale - 1f)) / 2f
                                        scale = newScale
                                        offset = Offset(
                                            x = rawOffset.x.coerceIn(-maxX, maxX),
                                            y = rawOffset.y.coerceIn(-maxY, maxY),
                                        )
                                    }
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                }
                            }
                        } while (!canceled && event.changes.any { it.pressed })

                        if (!wasCanceled && !movedPastSlop && !multiTouch) {
                            currentOnDismiss()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { loaded ->
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = image,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}

@Composable
private fun BenchmarkTimersRow(state: WineAppLabelsState, nowRealtimeMs: Long) {
    val liveElapsedMs = WineAppLabelsTimers.liveElapsedMs(
        accumulatedRunMs = state.elapsedRunMs,
        segmentStartedRealtimeMs = state.runSegmentStartedRealtimeMs,
        nowRealtimeMs = nowRealtimeMs,
    )
    val remainingMs = WineAppLabelsTimers.estimatedRemainingMs(
        remainingImages = state.remainingImages,
        etaPerImageMs = state.etaPerImageMs,
        currentImageStartedRealtimeMs = state.currentImageStartedRealtimeMs,
        nowRealtimeMs = nowRealtimeMs,
    )
    val items = listOf(
        "Run time" to WineAppLabelsTimers.formatElapsedMmSs(liveElapsedMs),
        "Avg / image" to WineAppLabelsTimers.formatAverageSeconds(state.avgImageMs),
        "Est. remaining" to WineAppLabelsTimers.formatCountdownMmSs(remainingMs),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun BuildFieldComparisonTable(
    currentScores: Map<String, WineAppLabelsBuildLog.FieldScore>,
    bestBuildScores: Map<String, WineAppLabelsBuildLog.FieldScore>,
    prodV4Scores: Map<String, WineAppLabelsBuildLog.FieldScore>,
) {
    val passColor = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val failColor = MaterialTheme.colorScheme.error
    val buildColumnWidth = 96.dp
    val fieldColumnWidth = 88.dp
    val totalWidth = buildColumnWidth + (fieldColumnWidth.value * FAILURE_FILTER_OPTIONS.size).dp
    val buildRows = listOf(
        "Current build" to currentScores,
        "Best build" to bestBuildScores,
        PhoneCorpusRunner.Mode.PRODUCTION_V4.title to prodV4Scores,
    )
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Text(
                "Build",
                Modifier.width(buildColumnWidth).padding(4.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
            FAILURE_FILTER_OPTIONS.forEach { (_, label) ->
                Text(
                    label,
                    Modifier.width(fieldColumnWidth).padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        buildRows.forEach { (title, scores) ->
            HorizontalDivider(Modifier.width(totalWidth))
            Row {
                Text(
                    title,
                    Modifier.width(buildColumnWidth).padding(4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                )
                FAILURE_FILTER_OPTIONS.forEach { (fieldKey, _) ->
                    val cell = scores[fieldKey]?.let { score ->
                        passFailLabel(score.correct, score.incorrect, passColor, failColor)
                    } ?: AnnotatedString("—")
                    Text(
                        cell,
                        Modifier.width(fieldColumnWidth).padding(4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
