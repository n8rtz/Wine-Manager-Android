// HOST GLiNER-LANE REPLAY ONLY. Never compiled into the app.
//
// Runs the real lane architecture over the packaged cached-OCR journals:
//   stage 1  cached ML Kit + PP-OCRv6 readings -> GlinerFusion.fuse -> GlinerDocument.build
//   stage 2  a stand-in for GLiNER (the INT4 weights are not in this repository):
//              none    no lanes at all, so every fused line stays residual
//              oracle  the reference answers are matched to word spans, i.e. a perfect labeller
//   stage 3  the real GlinerLaneRecords bridge -> TextLabFormPreview + TextLabSpatialPreview
//
// So everything except the model's grouping is the shipping code. The oracle mode is a ceiling,
// not a prediction: it answers "if GLiNER grouped this label perfectly, what would the existing
// post-processing score?".
//
// The gazetteer bridge and TSV plumbing mirror HostLabReplay.kt (tools/logic-checks), which stays
// untouched so the Lab v7 parity gate keeps running against it.
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.gazetteer.LinkedWineResolver
import com.yourapp.wine.gazetteer.ProducerResolver
import com.yourapp.wine.gliner.GlinerDocument
import com.yourapp.wine.gliner.GlinerEntity
import com.yourapp.wine.gliner.GlinerFusion
import com.yourapp.wine.gliner.GlinerLabelResult
import com.yourapp.wine.gliner.GlinerLane
import com.yourapp.wine.gliner.GlinerLaneConfig
import com.yourapp.wine.gliner.GlinerLaneGroup
import com.yourapp.wine.gliner.GlinerLaneRecords
import com.yourapp.wine.gliner.GlinerOcrLine
import com.yourapp.wine.gliner.GlinerResidualLine
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.textlab.TextLabEvidencePreview
import com.yourapp.wine.textlab.TextLabFormPreview
import com.yourapp.wine.textlab.TextLabJournal
import com.yourapp.wine.textlab.TextLabSpatialPreview
import com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import com.yourapp.wine.textlab.evidence.ProducerRepair
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.Base64

private fun b64decode(value: String): String =
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)

private fun b64encode(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun jsonEscape(value: String): String = buildString {
    for (ch in value) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
    }
}

/** Minimal parser for the bridge reply shape: {"rows":[["a","b"],...]} / {"error":"..."}. */
private fun parseBridgeRows(line: String): List<List<String>> {
    if (line.contains("\"error\"")) throw IllegalStateException("sqlite bridge: $line")
    val rows = mutableListOf<List<String>>()
    var i = line.indexOf("[[")
    if (i < 0) return rows
    i += 1
    while (i < line.length) {
        if (line[i] == ']') break
        if (line[i] != '[') { i++; continue }
        i++
        val row = mutableListOf<String>()
        while (i < line.length && line[i] != ']') {
            if (line[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < line.length && line[i] != '"') {
                    if (line[i] == '\\' && i + 1 < line.length) {
                        when (line[i + 1]) {
                            'n' -> { sb.append('\n'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            'r' -> { sb.append('\r'); i += 2 }
                            'u' -> { sb.append(line.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                            else -> { sb.append(line[i + 1]); i += 2 }
                        }
                    } else { sb.append(line[i]); i++ }
                }
                i++
                row += sb.toString()
            } else i++
        }
        i++
        rows += row
    }
    return rows
}

private class HostGazetteerIndex(port: Int) : LinkedWineResolver.Index, AutoCloseable {
    override val version: String = "host-expanded-bridge-v1"
    override val primaryVerified: Boolean = false
    override val labSupplemental: Boolean = true

    private val socket = java.net.Socket("127.0.0.1", port)
    private val writer: BufferedWriter = socket.getOutputStream().bufferedWriter()
    private val reader: BufferedReader = socket.getInputStream().bufferedReader()

    private fun request(json: String): List<List<String>> {
        writer.write(json); writer.newLine(); writer.flush()
        val line = reader.readLine() ?: throw IllegalStateException("sqlite bridge closed")
        return parseBridgeRows(line)
    }

    override fun lookup(key: String, limit: Int, checkBudget: () -> Unit): List<ProducerResolver.Entity> {
        checkBudget()
        val rows = request("""{"op":"producer","key":"${jsonEscape(key)}","limit":$limit}""")
        return rows.map { row ->
            ProducerResolver.Entity(
                row[0], TextNorm.scrub(row[1]), ProducerResolver.normalize(row[2]),
                if (row[3].isBlank()) emptySet() else setOf(row[3]), TextNorm.scrub(row[4]),
            )
        }
    }

    override fun lookupWines(
        key: String, producerIds: Set<String>, limit: Int, checkBudget: () -> Unit,
    ): List<LinkedWineResolver.Wine> {
        checkBudget()
        val ids = producerIds.joinToString(",") { "\"${jsonEscape(it)}\"" }
        val rows = request("""{"op":"wine","key":"${jsonEscape(key)}","producerIds":[$ids],"limit":$limit}""")
        return rows.map { row -> LinkedWineResolver.Wine(row[0], TextNorm.scrub(row[1]), ProducerResolver.normalize(row[2]), row[3], row[4]) }
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { socket.close() }
    }
}

/** Reference answers, already narrowed to the values an oracle labeller would send to a lane. */
private data class OracleRefs(
    val producer: List<String>,
    val wineName: List<String>,
    val places: List<String>,
)

private fun readRefs(path: File): Map<String, OracleRefs> {
    val byImage = linkedMapOf<String, MutableList<Pair<String, String>>>()
    path.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val parts = line.split('\t')
        if (parts.size < 4) return@forEachLine
        val image = b64decode(parts[0])
        val field = b64decode(parts[1])
        val status = b64decode(parts[2])
        val value = b64decode(parts[3])
        if (status != "expected" || value.isBlank()) return@forEachLine
        byImage.getOrPut(image) { mutableListOf() } += field to value
    }
    return byImage.mapValues { (_, entries) ->
        OracleRefs(
            producer = entries.filter { it.first == "producer" }.map { it.second }.distinct(),
            wineName = entries.filter { it.first == "wine_name" }.map { it.second }.distinct(),
            places = entries.filter { it.first in setOf("country", "region", "subregion") }
                .map { it.second }.distinct().sortedByDescending { it.length },
        )
    }
}

/** One accepted value located in the document as a contiguous run of words. */
private data class Found(val field: String, val start: Int, val end: Int, val value: String)

private fun findValue(document: GlinerDocument, value: String): Pair<Int, Int>? {
    val wanted = GlinerDocument.normalize(value)
    if (wanted.isEmpty()) return null
    val words = document.words
    for (start in words.indices) {
        val builder = StringBuilder()
        for (end in start until words.size) {
            builder.append(words[end].normalized)
            if (builder.length > wanted.length) break
            if (builder.toString() == wanted) return start to end
        }
    }
    return null
}

/**
 * Oracle labeller: the reference producer, wine name and place values are matched to word spans.
 * Longest place first so "Margaux" wins over "France" when both are in the run. A word already
 * claimed by a stronger lane is not offered to another lane, mirroring the flat-NER rule.
 */
private fun oracleEntities(document: GlinerDocument, refs: OracleRefs, config: GlinerLaneConfig,
                           oracleAll: Boolean): List<GlinerEntity> {
    val claimed = BooleanArray(document.words.size)
    val entities = mutableListOf<GlinerEntity>()
    fun place(value: String): List<Found> {
        val found = findValue(document, value) ?: return emptyList()
        val (start, end) = found
        return listOf(Found("place", start, end, value))
    }
    val candidates = mutableListOf<Pair<GlinerLane, Found>>()
    // A span labeller answers with a span, not with every accepted synonym of the answer. `oracle`
    // therefore takes the single best (longest) producer and wine-name match; `oracle-all` keeps
    // every alternative, which measures what multiple competing fragments do to the downstream.
    fun addBest(lane: GlinerLane, values: List<String>, all: Boolean) {
        val found = values.sortedByDescending { it.length }.mapNotNull { value ->
            findValue(document, value)?.let { (start, end) -> Found(lane.key, start, end, value) }
        }.sortedByDescending { it.end - it.start }
        if (found.isEmpty()) return
        if (all) found.forEach { candidates += lane to it } else candidates += lane to found.first()
    }
    addBest(GlinerLane.PRODUCER, refs.producer, oracleAll)
    addBest(GlinerLane.WINE_NAME, refs.wineName, oracleAll)
    refs.places.forEach { value -> place(value).forEach { candidates += GlinerLane.APPELLATION to it } }
    candidates.sortedWith(compareBy({ GlinerLane.ordered.indexOf(it.first) }, { -(it.second.end - it.second.start) }))
        .forEach { (lane, found) ->
            if ((found.start..found.end).any { claimed[it] }) return@forEach
            for (index in found.start..found.end) claimed[index] = true
            val words = document.words.subList(found.start, found.end + 1)
            entities += GlinerEntity(
                lane = lane,
                wordStart = found.start,
                wordEnd = found.end,
                text = words.joinToString(" ") { it.text },
                score = 1f,
                box = GlinerDocument.union(words.map { it.box }),
                lineIndices = words.map { it.lineIndex }.distinct().sorted(),
                prompt = config.promptFor(lane),
            )
        }
    return entities.sortedWith(compareBy({ it.wordStart }, { it.wordEnd }))
}

private fun residualLines(document: GlinerDocument, entities: List<GlinerEntity>): List<GlinerResidualLine> {
    val covered = BooleanArray(document.words.size)
    entities.forEach { entity -> for (index in entity.wordStart..entity.wordEnd) if (index in covered.indices) covered[index] = true }
    return document.words.indices.groupBy { document.words[it].lineIndex }.mapNotNull { (line, indices) ->
        val kept = indices.filterNot { covered[it] }
        if (kept.isEmpty()) null else GlinerResidualLine(
            line,
            kept.joinToString(" ") { document.words[it].text },
            GlinerDocument.union(kept.map { document.words[it].box }),
            kept.size,
        )
    }
}

fun main(args: Array<String>) {
    // args: <input.tsv> <output.tsv> <repoRoot> <modulesKey> <year> <mode> <refs.tsv> [stats.tsv]
    val input = File(args[0])
    val output = File(args[1])
    val modules = LabPostOcrModules.fromKeys(args[3].split('+')).withIntegrated()
    val year = args[4].toIntOrNull() ?: 2026
    val mode = args.getOrNull(5) ?: "none"
    val refs = args.getOrNull(6)?.takeIf { it.isNotBlank() }?.let { readRefs(File(it)) } ?: emptyMap()
    val statsWriter = args.getOrNull(7)?.takeIf { it.isNotBlank() }?.let { File(it).bufferedWriter() }
    statsWriter?.write("image\tmode\tfusedLines\twords\tlaneEntities\tproducer\twine_name\tappellation\tresidualLines\trecords\n")

    val config = GlinerLaneConfig()
    val kb = FixtureKnowledgeBase()
    val fill = FillEngine(kb)
    val engine = TextLabEvidencePreview.engine(kb, true, repairPlaces = true, labModules = modules)
    val hybrid = EvidenceAnchorHybrid(kb)
    val repair = ProducerRepair(kb)

    val bridgePort = (System.getenv("HOST_BRIDGE_PORT") ?: error("HOST_BRIDGE_PORT not set")).toInt()
    val index = HostGazetteerIndex(bridgePort)

    index.use {
        output.bufferedWriter().use { out ->
            var journal: TextLabJournal? = null
            var image = ""
            fun flush() {
                val current = journal ?: return
                // Stage 1 (cached): the recorded per-engine readings become fusion inputs, exactly
                // the shape GlinerOcr hands over after both engines have read the label.
                val rawRecords = TextLabFormPreview.records(current)
                val readings = rawRecords.map { record ->
                    GlinerFusion.Reading(record.engineId, record.textRaw, record.bboxRect, record.polyOrig, record.score)
                }
                val fused: List<GlinerOcrLine> = GlinerFusion.fuse(readings)
                val document = GlinerDocument.build(fused)
                // Stage 2 stand-in.
                val entities = if (mode.startsWith("oracle")) {
                    oracleEntities(document, refs[image] ?: OracleRefs(emptyList(), emptyList(), emptyList()),
                        config, oracleAll = mode == "oracle-all")
                } else emptyList()
                // `-additive` keeps every fused line in the residual as well: the lane is extra
                // evidence next to the readings Lab v7 already had, instead of replacing them.
                val residual = residualLines(document, if (mode.endsWith("-additive")) emptyList() else entities)
                val lanes = GlinerLane.ordered.map { lane ->
                    GlinerLaneGroup(lane, config.promptFor(lane), entities.filter { it.lane == lane }, 0)
                }
                val labelResult = GlinerLabelResult(
                    document = document,
                    lanes = lanes,
                    residualLines = residual,
                    entities = entities,
                    modelSignature = "host-$mode",
                    int4 = false,
                    inputSignature = "host",
                    outputShape = "host",
                    layout = "host stand-in ($mode)",
                    wordCount = document.words.size,
                    spanCount = 0,
                    elapsedMs = 0,
                    config = config,
                )
                val records: List<TokenRecord> = GlinerLaneRecords.records(labelResult)
                // Stage 3: the real Lab v7 contender, with the lane records as its input.
                TextLabFormPreview.append(current, fill, year, recordsOverride = records)
                TextLabSpatialPreview.append(
                    current, engine, hybrid, repair, year,
                    producerIndex = index,
                    producerUnavailable = "host expanded producer index required",
                    candidate = "gliner_lane", stagePrefix = "gliner_",
                    policy = ProducerResolver.Policy.singleReading,
                    fillEngine = fill, labModules = modules,
                    recordsOverride = records,
                )
                val summary = current.rows.lastOrNull { it["stage"] == "gliner_evidence_summary" }
                    ?: current.rows.lastOrNull { it["stage"] == "form_summary" }
                val fields = listOf("producer", "wine_name", "country", "region", "subregion", "vintage")
                val values = fields.map { field ->
                    if (summary == null || summary["${field}_state"] == "RED") "" else summary[field].orEmpty()
                }
                out.write(image + "\t" + (summary?.get("status") ?: "missing_summary") +
                    values.joinToString("") { "\t" + b64encode(it) } + "\n")
                out.flush()
                statsWriter?.let { stats ->
                    val counts = GlinerLane.ordered.joinToString("\t") { lane -> entities.count { it.lane == lane }.toString() }
                    stats.write("$image\t$mode\t${fused.size}\t${document.words.size}\t${entities.size}\t$counts\t${residual.size}\t${records.size}\n")
                    stats.flush()
                }
            }
            input.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parts = line.split('\t')
                when (parts[0]) {
                    "IMAGE" -> {
                        flush()
                        image = parts[1]
                        journal = TextLabJournal(setOf("candidate_3"), "host-gliner", true)
                    }
                    "ROW" -> {
                        val count = parts[1].toInt()
                        val map = linkedMapOf<String, String>()
                        var p = 2
                        repeat(count) {
                            val k = b64decode(parts[p]); val v = b64decode(parts[p + 1]); p += 2
                            map[k] = if (k == "raw_text") TextNorm.scrub(v) else v
                        }
                        journal!!.rows += map
                    }
                }
            }
            flush()
        }
    }
    statsWriter?.close()
}
