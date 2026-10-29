// HOST LAB REPLAY ONLY. Runs the real Lab v5 post-OCR Kotlin pipeline
// (TextLabFormPreview + TextLabSpatialPreview + Producer/LinkedWine resolvers)
// on the JVM against packaged cached-OCR journals. Never compiled into the app.
// Gazetteer SQLite access goes through tools/host-lab/sqlite_bridge.py, which
// mirrors ProducerGazetteer SQL against the same packaged .sqlite assets.
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.gazetteer.LinkedWineResolver
import com.yourapp.wine.gazetteer.ProducerResolver
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.TextNorm
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
                i++ // closing quote
                row += sb.toString()
            } else i++
        }
        i++ // row closing bracket
        rows += row
    }
    return rows
}

private class HostGazetteerIndex(port: Int) :
    LinkedWineResolver.Index, AutoCloseable {
    override val version: String = "host-expanded-bridge-v1"
    override val primaryVerified: Boolean = false
    override val labSupplemental: Boolean = true

    // JVM child processes are blocked in the host sandbox, so the shell driver
    // starts sqlite_bridge.py as a localhost server and we connect by socket.
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
        return rows.map { row ->
            LinkedWineResolver.Wine(row[0], TextNorm.scrub(row[1]), ProducerResolver.normalize(row[2]), row[3], row[4])
        }
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { socket.close() }
    }
}

fun main(args: Array<String>) {
    // args: <input.tsv> <output.tsv> <repoRoot> <modulesKey> [year]
    val input = File(args[0])
    val output = File(args[1])
    val root = File(args[2])
    val modules = LabPostOcrModules.fromKeys(args[3].split('+'))
    val year = args.getOrNull(4)?.toIntOrNull() ?: 2026
    val debugWriter = args.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { File(it).bufferedWriter() }

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
                TextLabFormPreview.append(current, fill, year)
                TextLabSpatialPreview.append(
                    current, engine, hybrid, repair, year,
                    producerIndex = index,
                    producerUnavailable = "host expanded producer index required",
                    candidate = "spatial_lab", stagePrefix = "spatial_",
                    policy = ProducerResolver.Policy.singleReading,
                    fillEngine = fill, labModules = modules,
                )
                debugWriter?.let { dbg ->
                    for (row in current.rows) {
                        val keys = listOf("candidate","stage","status","details","wine_name","wine_name_reason","producer","country","region","subregion","candidate_values","evidence_details","raw_text","producer_final_near_match_status","producer_reason")
                        dbg.write(image + keys.joinToString("") { "\t" + b64encode(row[it].orEmpty()) } + "\n")
                    }
                    dbg.flush()
                }
                val summary = current.rows.lastOrNull { it["stage"] == "spatial_evidence_summary" }
                val fields = listOf("producer", "wine_name", "country", "region", "subregion", "vintage")
                val values = fields.map { field ->
                    if (summary == null || summary["${field}_state"] == "RED") "" else summary[field].orEmpty()
                }
                out.write(image + "\t" + (summary?.get("status") ?: "missing_summary") +
                    values.joinToString("") { "\t" + b64encode(it) } + "\n")
                out.flush()
            }
            input.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parts = line.split('\t')
                when (parts[0]) {
                    "IMAGE" -> { flush(); image = parts[1]; journal = TextLabJournal(setOf("candidate_3"), "host-lab", true) }
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
    debugWriter?.close()
}
