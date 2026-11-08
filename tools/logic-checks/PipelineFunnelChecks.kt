import com.yourapp.wine.diagnostics.PipelineFunnel as F
import com.yourapp.wine.phonecorpus.*
import com.yourapp.wine.gazetteer.*
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    var checks = 0
    fun verify(value: Boolean) { checks++;check(value) { "Pipeline funnel check $checks failed" } }
    fun state(label: F.Label, id: String) = label.gates.getValue(id).state
    fun count(labels: List<F.Label>, id: String) = F.aggregate(labels).single { it.step.id == id }
    val recorder = F.Recorder("producer.")
    repeat(20) { recorder.visit("mlkit.eligible", false, "single word") }
    recorder.visit("mlkit.eligible", true);recorder.visit("mlkit.eligible", false, "boilerplate")
    val once = F.Label("one", 1, recorder.snapshot())
    verify(count(listOf(once), "producer.mlkit.eligible").passed == 1)
    verify(F.fromFields(recorder.fields()) == recorder.snapshot())
    val retry = once.copy(attempt = 2, gates = mapOf("producer.mlkit.eligible" to F.Gate(F.State.BLOCKED, "retry failed")))
    verify(count(listOf(once, retry), "producer.mlkit.eligible").let { it.labels == 1 && it.passed == 0 && it.blocked == 1 })
    verify(count(emptyList(), "producer.independent").let { it.labels == 0 && it.passed == 0 })
    verify(F.steps.map { it.id }.distinct().size == F.steps.size)
    verify(F.aggregate(listOf(once, retry, once.copy(image = "two"))).all {
        it.passed + it.blocked + it.notReached + it.notRecorded + it.notApplicable == it.labels
    })

    fun row(candidate: String, stage: String, status: String = "complete", extra: Map<String, String> = emptyMap()) =
        mapOf("candidate" to candidate, "stage" to stage, "status" to status) + extra
    fun reading(candidate: String) = row(candidate, "recognition", extra = mapOf("raw_text" to "Domaine Example",
        "mlkit_confidence" to "0.9", "ctc_posterior" to "0.8", "base_box_normalized" to "0.1 0.1 0.8 0.2", "original_box_normalized" to "0.1 0.1 0.8 0.2"))
    val partialRows = listOf(reading("mlkit_reference"), row("mlkit_reference", "summary"),
        row("candidate_3", "candidate_start", "running"), reading("candidate_3"), row("candidate_3", "candidate_summary", "timed_out", mapOf("regions" to "1")))
    val partial = PhoneCorpusPipelineDiagnostics.capture("partial", 1, "LAB", partialRows, emptyMap(), mapOf("producer" to "Estate"), "complete", true)
    verify(state(partial, "mlkit.completed") == F.State.PASSED)
    verify(state(partial, "paddle.completed") == F.State.BLOCKED)
    verify(state(partial, "paddle.text") == F.State.PASSED) // Partial real observations are not erased by a timeout.
    verify(state(partial, "ocr.both_completed") == F.State.BLOCKED && state(partial, "ocr.both_text") == F.State.PASSED)
    verify(state(partial, "producer.independent") == F.State.NOT_REACHED)
    val noText = PhoneCorpusPipelineDiagnostics.capture("empty", 1, "PRODUCTION_V2", listOf(row("mlkit_reference", "summary", "no_text")), emptyMap(), emptyMap(), "complete", true)
    verify(state(noText, "mlkit.completed") == F.State.PASSED && state(noText, "mlkit.text") == F.State.BLOCKED)
    verify(state(noText, "producer.independent") == F.State.NOT_APPLICABLE)

    val qualityStopped = PhoneCorpusPipelineDiagnostics.capture("quality-stop", 1, "PRODUCTION_V1", emptyList(), mapOf(
        "input.quality" to F.Gate(F.State.BLOCKED, "RED image quality requires retake"),
        "mlkit.started" to F.Gate(F.State.NOT_REACHED, "no engine pass attempted"),
        "paddle.started" to F.Gate(F.State.NOT_REACHED, "no engine pass attempted")), emptyMap(), "complete", true)
    verify(state(qualityStopped, "input.quality") == F.State.BLOCKED && state(qualityStopped, "mlkit.completed") == F.State.NOT_REACHED)
    verify(state(qualityStopped, "paddle.detected") == F.State.NOT_REACHED && state(qualityStopped, "paddle.lines") == F.State.NOT_REACHED)
    val legacy = PhoneCorpusPipelineDiagnostics.capture("old", 1, "LAB", emptyList(), emptyMap(), emptyMap(), "complete", false)
    verify(state(legacy, "producer.mlkit.lookup") == F.State.NOT_RECORDED)
    val unavailable = PhoneCorpusPipelineDiagnostics.capture("bad-asset", 1, "LAB", listOf(row("spatial_lab", "gazetteer_setup", "unavailable", mapOf(
        "funnel.asset.manifest.status" to "PASSED", "funnel.asset.wine.status" to "BLOCKED", "funnel.asset.wine.reason" to "hash mismatch"))), emptyMap(), null, "failed", true)
    verify(state(unavailable, "asset.manifest") == F.State.PASSED && state(unavailable, "asset.wine") == F.State.BLOCKED)
    verify(state(unavailable, "producer.invoked") == F.State.NOT_REACHED)
    verify(state(unavailable, "final.output") == F.State.BLOCKED)
    val abstained = PhoneCorpusPipelineDiagnostics.capture("old-abstention", 1, "LAB", listOf(row("spatial_lab", "spatial_evidence_summary", extra = mapOf(
        "producer_gazetteer_status" to "unchanged", "producer_gazetteer_reason" to "no independently supported producer; unchanged"))), emptyMap(), emptyMap(), "complete", false)
    verify(state(abstained, "producer.independent") == F.State.BLOCKED && state(abstained, "producer.unambiguous") == F.State.NOT_REACHED)
    verify(state(abstained, "producer.mlkit.lookup") == F.State.NOT_RECORDED)

    fun legacySummary(values: Map<String,String>, extra: List<Map<String,String>> = emptyList()) = PhoneCorpusPipelineDiagnostics.capture("legacy", 1, "LAB",
        listOf(row("spatial_lab", "spatial_evidence_summary", extra = mapOf("producer_gazetteer_status" to "unchanged") + values)) + extra,
        emptyMap(), emptyMap(), "complete", false)
    val versionOnly = legacySummary(mapOf("producer_gazetteer_version" to "fixture-version"))
    verify(state(versionOnly, "asset.manifest") == F.State.NOT_RECORDED && state(versionOnly, "asset.wine") == F.State.NOT_RECORDED)
    val pairedVersion = legacySummary(mapOf("producer_gazetteer_version" to "primary-v2:producer=${"a".repeat(64)};wine=${"b".repeat(64)}"))
    verify(state(pairedVersion, "asset.producer") == F.State.PASSED && state(pairedVersion, "asset.wine") == F.State.PASSED)
    val primaryUnavailable = legacySummary(mapOf("producer_gazetteer_reason" to "primary LWIN export required; unchanged"))
    verify(state(primaryUnavailable, "producer.primary") == F.State.BLOCKED)
    val unknownEngine = legacySummary(emptyMap(), listOf(row("spatial_lab", "producer_gazetteer_candidate")))
    verify(state(unknownEngine, "producer.paddle.lookup") == F.State.NOT_RECORDED)

    val estate = ProducerResolver.Entity("estate", "Domaine Example", "domaine example")
    val wine = LinkedWineResolver.Wine("wine", "Moonlight Cuvee", "moonlight cuvee", "estate", "[1234567]")
    val index = object : LinkedWineResolver.Index {
        override val version = "test";override val primaryVerified = true
        override fun lookup(key: String, limit: Int, checkBudget: () -> Unit) = listOf(estate)
        override fun lookupWines(key: String, producerIds: Set<String>, limit: Int, checkBudget: () -> Unit) = listOf(wine)
    }
    val producers = listOf(ProducerResolver.Span("Domaine Example", "mlkit", setOf(1)), ProducerResolver.Span("Domaine Example", "paddle-v6-medium", setOf(2)))
    val wines = listOf(ProducerResolver.Span("Moonlight Cuvee", "mlkit", setOf(3)), ProducerResolver.Span("Moonlight Cuvee", "paddle-v6-medium", setOf(4)))
    val resolved = ProducerResolver(index).resolve("", producers)
    val pg = F.fromFields(resolved.telemetry)
    verify(pg["producer.independent"]?.state == F.State.PASSED && pg["producer.changed"]?.state == F.State.PASSED)
    val protected = ProducerResolver(index).resolve("Manual Good Estate", producers)
    verify(protected.value == "Manual Good Estate" && F.fromFields(protected.telemetry)["producer.existing"]?.state == F.State.BLOCKED)
    val confirmed = ProducerResolver(index).resolve("Domaine Example", producers)
    verify(F.fromFields(confirmed.telemetry)["producer.selected"]?.state == F.State.PASSED && F.fromFields(confirmed.telemetry)["producer.changed"]?.state == F.State.BLOCKED)
    val expired = ProducerResolver(index).resolve("Keep", producers) { throw ProducerResolver.BudgetExceeded() }
    verify(expired.value == "Keep" && F.fromFields(expired.telemetry)["producer.execution"]?.state == F.State.BLOCKED)
    val rejected = ProducerResolver(index).resolve("", producers.map { it.copy(text = "Blackstone") })
    verify(F.fromFields(rejected.telemetry)["producer.mlkit.eligible"]?.reason == "single-word producer span")
    val linked = LinkedWineResolver(index).resolve("", "", resolved, producers + wines)
    verify(linked.wineId == "wine" && F.fromFields(linked.telemetry)["wine.independent"]?.state == F.State.PASSED)
    val overlap = LinkedWineResolver(index).resolve("", "", resolved, producers + wines.map { it.copy(observationIds = setOf(1, 2)) })
    verify(overlap.wineId == null && F.fromFields(overlap.telemetry)["wine.mlkit.disjoint"]?.state == F.State.BLOCKED)
    val noProducer = LinkedWineResolver(index).resolve("", "", ProducerResolver.Decision("", "no producer", emptyList()), wines)
    verify(F.fromFields(noProducer.telemetry)["wine.producer_compatible"]?.state == F.State.BLOCKED && "wine.mlkit.eligible" !in F.fromFields(noProducer.telemetry))

    val failedLookup = IllegalStateException("fixture lookup failure")
    var failureFields = emptyMap<String,String>()
    val brokenProducer = object : ProducerResolver.Index by index {
        override fun lookup(key: String, limit: Int, checkBudget: () -> Unit): List<ProducerResolver.Entity> = throw failedLookup
    }
    val thrownProducer = runCatching { ProducerResolver(brokenProducer, onFailureTelemetry={ failureFields=it }).resolve("Keep", producers) }.exceptionOrNull()
    verify(thrownProducer === failedLookup)
    verify(F.fromFields(failureFields).let { it["producer.mlkit.eligible"]?.state == F.State.PASSED &&
        it["producer.mlkit.lookup"]?.state == F.State.BLOCKED && it["producer.execution"]?.state == F.State.BLOCKED })
    val brokenWine = object : LinkedWineResolver.Index by index {
        override fun lookupWines(key: String, producerIds: Set<String>, limit: Int, checkBudget: () -> Unit): List<LinkedWineResolver.Wine> = throw failedLookup
    }
    val thrownWine = runCatching { LinkedWineResolver(brokenWine, onFailureTelemetry={ failureFields=it }).resolve("", "Keep", resolved, producers + wines) }.exceptionOrNull()
    verify(thrownWine === failedLookup)
    verify(F.fromFields(failureFields).let { it["wine.mlkit.lookup"]?.state == F.State.BLOCKED && it["wine.execution"]?.state == F.State.BLOCKED })

    val json = JSONObject().put("image", "partial").put("attempt", 3)
    PhoneCorpusFunnelJson.write(json, partial)
    val checkpoint = JSONObject(json.toString())
    val decoded = PhoneCorpusFunnelJson.read(checkpoint, "LAB", "lab-v4-expanded-producers")
    verify(decoded.attempt == 3 && decoded.gates == partial.gates)
    verify(decoded.gates.size == F.steps.size)
    val csv = count(listOf(partial), "paddle.completed").csv("lab-v4-expanded-producers")
    verify(csv["row_type"] == "pipeline_step" && csv["labels_blocked"] == "1" && csv["labels_reached"] == "1")
    verify(count(listOf(partial), "paddle.completed").cells().size == F.headers.size)

    // A pre-refresh nested pipeline must still restore its final-field and funnel diagnostics
    // when loaded into the newly named Lab v5 / Prod v4.0 modes.
    val legacyLab = JSONObject().put("image", "legacy-lab").put("status", "complete")
        .put("pipelines", org.json.JSONArray().put(JSONObject().put("pipeline", "lab-v4-expanded-producers")
            .put("status", "complete").put("fields", JSONObject().put("producer", "Legacy Cellars"))))
    val legacyLabLabel = PhoneCorpusFunnelJson.read(legacyLab, "LAB", "lab-v5-expanded-producers")
    verify(state(legacyLabLabel, "final.producer") == F.State.PASSED)
    val legacyProd = JSONObject().put("image", "legacy-prod").put("status", "complete")
        .put("pipelines", org.json.JSONArray().put(JSONObject().put("pipeline", "production-v3.0-lwin-only")
            .put("status", "complete").put("fields", JSONObject().put("region", "Maipo Valley"))))
    val legacyProdLabel = PhoneCorpusFunnelJson.read(legacyProd, "PRODUCTION_V4", "production-v4.0-lwin-only")
    verify(state(legacyProdLabel, "final.region") == F.State.PASSED)

    val root = File(args.first())
    val context = android.content.Context(File(root, "app/src/debug/assets"))
    val fixture = JSONObject(File(args[1]).readText())
    val expected = fixture.getJSONObject("expected")
    val array = fixture.getJSONArray("predictions")
    val predictions = (0 until array.length()).map { array.getJSONObject(it) }
    val board = PhoneCorpusScoreboard(context).build(predictions, PhoneCorpusRunner.Mode.LAB)
    verify(board.rows.first()[0] == "Lab v5")
    verify(board.csv.firstOrNull { it["row_type"] == "field" }?.get("pipeline") == "lab-v5-expanded-producers")
    verify(board.rows.first().let { it[1] == "168" && it[2] == "160" && it[3] == expected.getString("complete") && it[4] == expected.getString("one_error") && it[5] == expected.getString("multi_error") })
    verify(board.funnel.single { it.step.id == "producer.changed" }.passed == expected.getInt("producer_changed"))
    verify(board.funnel.single { it.step.id == "wine.changed" }.passed == expected.getInt("wine_changed"))
    verify(board.funnel.single { it.step.id == "producer.invoked" }.passed == expected.getInt("producer_invoked"))
    verify(board.funnel.single { it.step.id == "wine.invoked" }.passed == expected.getInt("wine_invoked"))
    val completeCount = expected.getString("complete").substringBefore('/').toInt()
    verify(board.funnel.single { it.step.id == "score.complete" }.let { it.passed == completeCount && it.blocked == 160-completeCount && it.notApplicable == 8 })
    verify(board.funnel.last().step.id == "score.complete")
    verify(board.funnel.last().passed == board.rows.first()[3].substringBefore('/').toInt())
    verify(board.funnel.none { it.step.productionOnly })
    verify(board.matrix.last().step.id == "score.complete")
    verify(board.matrix.last().shared?.passed == completeCount)
    verify(board.matrix.single { it.step.id == "matrix.resolver.changed" }.fields.isEmpty()) // Saved fixture has no resolver telemetry.
    verify(board.matrix.count { it.step.id == "matrix.field.populated" } == 1)
    verify(board.matrix.count { it.step.id == "matrix.field.correct" } == 1)
    verify(board.matrix.size < board.funnel.size)
    val instrumentedPredictions=predictions.mapIndexed { index,prediction ->
        val copy=JSONObject(prediction.toString())
        val gates=org.json.JSONArray()
        for(step in F.steps) {
            val status=when(step.id) {
                "producer.changed" -> if(index<2) F.State.PASSED else if(index<4) F.State.BLOCKED else F.State.NOT_REACHED
                "wine.changed" -> if(index<1) F.State.PASSED else if(index<4) F.State.BLOCKED else F.State.NOT_REACHED
                else -> F.State.NOT_RECORDED
            }
            gates.put(JSONObject().put("step_id",step.id).put("step_status",status.name))
        }
        copy.put("pipeline_funnel_version",F.VERSION).put("pipeline_funnel",gates)
        copy
    }
    val instrumentedBoard=PhoneCorpusScoreboard(context).build(instrumentedPredictions,PhoneCorpusRunner.Mode.LAB)
    val changed=instrumentedBoard.matrix.single { it.step.id == "matrix.resolver.changed" }
    verify(changed.fields.getValue("producer").let { it.passed==2 && it.failed==2 })
    verify(changed.fields.getValue("wine_name").let { it.passed==1 && it.failed==3 })
    for (field in PhoneCorpusMath.fields) {
        val fieldRows=board.csv.filter { it["row_type"] == "field" && it["pipeline"] == PhoneCorpusRunner.Mode.LAB.pipelineId && it["field"] == field }
        val expectedPassed=fieldRows.count { it["outcome"] in setOf("correct_value","correct_blank") }
        val expectedFailed=fieldRows.count { it["outcome"] in setOf("wrong_value","missing_value","unsupported_fill","execution_failed") }
        val actual=board.matrix.single { it.step.id == "matrix.field.correct" }.fields.getValue(field)
        verify(actual.passed == expectedPassed && actual.failed == expectedFailed)
    }
    verify(board.funnel.all { it.labels == 168 })
    verify(board.csv.count { it["row_type"] == "field" } == 5040) // Saved comparisons preserved, not a second live funnel.
    verify(board.csv.count { it["row_type"] == "pipeline_step" } == board.funnel.size)
    val productionBoard = PhoneCorpusScoreboard(context).build(predictions, PhoneCorpusRunner.Mode.PRODUCTION_V2)
    verify(productionBoard.funnel.none { it.step.labOnly })
    verify(productionBoard.funnel.any { it.step.productionOnly })
    verify(productionBoard.funnel.last().step.id == "score.complete")
    verify(productionBoard.matrix.none { it.step.labOnly })
    verify(productionBoard.matrix.any { it.step.productionOnly })
    verify(productionBoard.matrix.last().step.id == "score.complete")
    verify(productionBoard.csv.count { it["row_type"] == "pipeline_step" } == productionBoard.funnel.size)
    val metadata = fixture.getJSONObject("metadata")
    val output = File(args[2]);PhoneCorpusScoreboard.export(board, output, metadata.keys().asSequence().associateWith { metadata.optString(it) })
    verify(output.readText().contains("pipeline_step") && board.text.contains("PIPELINE BOTTLENECK MATRIX"))
    println("PASS: $checks actual Kotlin funnel, resolver telemetry, checkpoint, scorer and CSV checks; no Android runtime or OCR.")
}
