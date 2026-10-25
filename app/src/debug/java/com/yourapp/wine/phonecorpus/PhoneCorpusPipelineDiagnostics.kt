package com.yourapp.wine.phonecorpus

import com.yourapp.wine.diagnostics.PipelineFunnel as F

/** Reduces existing observations to scalar label gates; never executes OCR or a resolver. */
internal object PhoneCorpusPipelineDiagnostics {
    private val finished = setOf("complete", "no_text", "no_regions")
    private val successfulSummary = setOf("suggestions", "partial_suggestions", "no_fields", "complete")
    private val verifiedPairVersion = Regex("[^:;]+:(?:profile=[^;]+;)?producer=[0-9a-f]{64};wine=[0-9a-f]{64};support=(?:[0-9a-f]{64}|none)")
    fun capture(image: String, attempt: Int, mode: String, rows: List<Map<String, String>>,
        recorded: Map<String, F.Gate>, fields: Map<String, String>?, status: String?, fresh: Boolean): F.Label {
        val spatial = mode == "LAB" || mode == "PRODUCTION_V3"
        val production = mode == "PRODUCTION_V1"
        val gates = F.steps.associate { step -> step.id to when {
            step.labOnly && !spatial -> F.Gate(F.State.NOT_APPLICABLE)
            step.productionOnly && !production -> F.Gate(F.State.NOT_APPLICABLE)
            step.id.startsWith("score.") -> F.Gate(F.State.NOT_RECORDED, "Scoring not attached")
            production && step.id.startsWith("extract.") && step.id !in setOf("extract.form", "extract.kb") -> F.Gate(F.State.NOT_APPLICABLE)
            fresh && !production -> F.Gate(F.State.NOT_REACHED, "Earlier stage did not supply this step")
            else -> F.Gate(F.State.NOT_RECORDED, "Stage telemetry absent from this run")
        } }.toMutableMap()
        gates.putAll(recorded)
        fun mark(id: String, passed: Boolean, reason: String = "") { gates[id] = F.Gate(if (passed) F.State.PASSED else F.State.BLOCKED, if (passed) "" else reason.take(240)) }
        fun stage(name: String) = rows.filter { it["stage"] == name }
        for ((id, name) in listOf("input.services" to "service_setup", "extract.kb" to "knowledge_setup")) {
            val setup = stage(name).lastOrNull()
            if (setup != null) mark(id, setup["status"] == "complete", setup["details"].orEmpty().ifBlank { setup["status"].orEmpty() })
        }
        val prepare = stage("preparation").lastOrNull()
        if (prepare != null) mark("input.prepared", prepare["status"] == "complete", prepare["status"].orEmpty())
        if (production) {
            for (engine in listOf("mlkit", "paddle")) {
                val started = gates["$engine.started"]
                if (started?.state == F.State.NOT_REACHED) for (step in F.steps.filter { it.id.startsWith("$engine.") }) {
                    if (gates[step.id]?.state == F.State.NOT_RECORDED) gates[step.id] = started
                }
            }
        }
        if (!production) {
            for ((engine, candidate, summaryStage) in listOf(Triple("mlkit", "mlkit_reference", "summary"), Triple("paddle", "candidate_3", "candidate_summary"))) {
                val branch = rows.filter { it["candidate"] == candidate }
                val summary = branch.lastOrNull { it["stage"] == summaryStage }
                val started = branch.any { it["stage"] in setOf("engine_start", "candidate_start", "recognition", summaryStage) }
                if (started) mark("$engine.started", true)
                if (summary != null) mark("$engine.completed", summary["status"] in finished, summary["status"].orEmpty())
                val readings = branch.filter { it["stage"] == "recognition" && it["status"] in setOf("complete", "greedy_fallback") && !it["raw_text"].isNullOrBlank() }
                if (started) {
                    mark("$engine.text", readings.isNotEmpty(), "no successful nonblank OCR readings")
                    mark("$engine.mapped", readings.any { usable(it, engine) }, "no reading with valid score and mapped boxes")
                }
                if (engine == "paddle" && started) {
                    val detection = branch.lastOrNull { it["stage"] == "detection" }
                    if (detection != null) mark("paddle.detected", detection["status"] == "complete", detection["status"].orEmpty())
                    val regionCount = summary?.get("regions")?.toIntOrNull() ?: detection?.get("region_count")?.toIntOrNull()
                    if (regionCount != null) mark("paddle.regions", regionCount > 0, "detector returned no text regions")
                    if (branch.any { it["stage"] == "line_start" }) mark("paddle.lines", true)
                    else if (summary != null) mark("paddle.lines", false, "no text lines reached recognition")
                }
            }
            if (gates["mlkit.started"]?.state == F.State.PASSED || gates["paddle.started"]?.state == F.State.PASSED) {
                // Reaching either OCR branch proves shared preparation returned, including for older traces.
                mark("input.prepared", true)
                mark("ocr.both_completed", listOf("mlkit", "paddle").all { gates["$it.completed"]?.state == F.State.PASSED }, "one or both engines failed / incomplete / not reached")
                mark("ocr.both_text", listOf("mlkit", "paddle").all { gates["$it.mapped"]?.state == F.State.PASSED }, "one or both engines supplied no usable observation")
            }
            val form = stage("form_summary").lastOrNull()
            if (form != null) {
                mark("extract.form", form["status"] in successfulSummary, form["status"].orEmpty())
                if (form["status"] in successfulSummary) mark("extract.kb", true)
            }
            val prefix = if (spatial) "spatial_evidence_" else "evidence_"
            val summary = stage(prefix + "summary").lastOrNull()
            if (summary != null) {
                mark("extract.assignment", summary["status"] in successfulSummary, summary["status"].orEmpty())
                mark("extract.observations", stage(prefix + "observation").isNotEmpty(), "no valid evidence observations")
                mark("extract.hypotheses", stage(prefix + "hypothesis").isNotEmpty(), "no field hypotheses")
            }
            if (spatial && summary != null) legacyResolverEvidence(rows, summary, gates)
            rows.forEach { gates.putAll(F.fromFields(it)) }
        }
        if (status != null) mark("final.output", status == "complete", status)
        if (fields != null) {
            PhoneCorpusMath.fields.forEach { field -> mark("final.$field", !fields[field].isNullOrBlank(), "final field blank") }
        }
        return F.Label(image, attempt, gates)
    }

    private fun usable(row: Map<String, String>, engine: String): Boolean {
        val score = row[if (engine == "mlkit") "mlkit_confidence" else "ctc_posterior"]?.toFloatOrNull() ?: return false
        fun box(key: String): Boolean {
            val parts = row[key]?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() } ?: return false
            return parts.size == 4 && parts.all { it.isFinite() && it in 0f..1f } && parts[2] > parts[0] && parts[3] > parts[1]
        }
        return score.isFinite() && score in 0f..1f && box("base_box_normalized") && box("original_box_normalized")
    }

    /** Only facts established by old traces. Absence of a candidate is NOT evidence of a failed lookup. */
    private fun legacyResolverEvidence(rows: List<Map<String, String>>, summary: Map<String, String>, gates: MutableMap<String, F.Gate>) {
        fun mark(id: String, passed: Boolean, reason: String = "") { gates[id] = F.Gate(if (passed) F.State.PASSED else F.State.BLOCKED, if (passed) "" else reason.take(240)) }
        // Only the paired native loader's complete version/hash stamp proves all setup steps.
        if (verifiedPairVersion.matches(summary["producer_gazetteer_version"].orEmpty())) {
            listOf("asset.manifest", "asset.producer", "asset.wine", "asset.support").forEach { mark(it, true) }
        }
        for (kind in listOf("producer", "wine")) {
            val state = summary["${kind}_gazetteer_status"].orEmpty()
            val reason = summary["${kind}_gazetteer_reason"].orEmpty()
            if (state.isBlank() || state == "unavailable") continue
            mark("$kind.invoked", true);mark("$kind.primary", !reason.startsWith("primary "), reason)
            mark("$kind.execution", !reason.contains("budget reached") && !reason.contains("lookup failed"), reason)
            val matches = rows.filter { it["stage"] == if (kind == "producer") "producer_gazetteer_candidate" else "linked_wine_candidate" }
            for (row in matches) {
                val engine = when {
                    row["source_engine"] == "mlkit" -> "mlkit"
                    row["source_engine"]?.startsWith("paddle") == true -> "paddle"
                    else -> continue // Missing/unknown provenance is not evidence of the Paddle branch.
                }
                listOf("eligible", "lookup", "retrieved", "distance").forEach { mark("$kind.$engine.$it", true) }
                if (kind == "wine") mark("wine.$engine.disjoint", true)
            }
            val accepted = if (kind == "producer") summary["producer_gazetteer_entity_id"].orEmpty().isNotBlank() || reason == "existing producer retained"
                else summary["wine_gazetteer_id"].orEmpty().isNotBlank()
            val independent = accepted || reason.contains("protected") || reason.contains("separation") || reason.contains("rival too close") || reason.startsWith("ambiguous")
            if (independent) mark("$kind.independent", true)
            else if (reason.startsWith("no independently supported producer") || reason == "no independently observed linked wine") mark("$kind.independent", false, reason)
            if (reason.startsWith("ambiguous")) mark("$kind.unambiguous", false, reason)
            else if (independent) mark("$kind.unambiguous", true)
            if (reason.contains("separation") || reason.contains("rival too close")) mark("$kind.separated", false, reason)
            else if (accepted || reason.contains("existing ${kind} protected")) mark("$kind.separated", true)
            if (reason.contains("existing ${kind} protected")) mark("$kind.existing", false, reason)
            if (accepted) {
                mark("$kind.existing", true);mark("$kind.selected", true)
            }
            val before = summary[if (kind == "producer") "producer_before_gazetteer" else "wine_before_gazetteer"]
            val after = summary[if (kind == "producer") "producer" else "wine_name"]
            if (before != null && after != null) mark("$kind.changed", before != after, reason.ifBlank { "field unchanged" })
            if (kind == "wine") {
                if (reason == "no independently observed producer for wine link") mark("wine.producer_compatible", false, reason)
                else if (reason == "no independently observed linked wine" || matches.isNotEmpty() || accepted) {
                    listOf("wine.producer_observed", "wine.producer_compatible", "wine.producer_bound").forEach { mark(it, true) }
                }
                if (accepted) mark("wine.producer_consistent", true)
            }
            // Explicit terminal reasons establish that downstream gates were not reached, even in legacy runs.
            val downstream = when {
                kind == "wine" && reason == "no independently observed producer for wine link" ->
                    listOf("producer_bound", "mlkit.eligible", "mlkit.lookup", "mlkit.retrieved", "mlkit.disjoint", "mlkit.distance",
                        "paddle.eligible", "paddle.lookup", "paddle.retrieved", "paddle.disjoint", "paddle.distance",
                        "independent", "unambiguous", "separated", "producer_consistent", "existing", "selected")
                reason.startsWith("no independently supported producer") || reason == "no independently observed linked wine" ->
                    listOf("unambiguous", "separated", "producer_consistent", "existing", "selected")
                reason.startsWith("ambiguous") -> listOf("separated", "producer_consistent", "existing", "selected")
                reason.contains("separation") || reason.contains("rival too close") -> listOf("producer_consistent", "existing", "selected")
                reason.contains("existing ${kind} protected") -> listOf("selected")
                else -> emptyList()
            }
            for (suffix in downstream) {
                val id = "$kind.$suffix"
                if (F.steps.any { it.id == id } && gates[id]?.state != F.State.PASSED) gates[id] = F.Gate(F.State.NOT_REACHED, reason)
            }
        }
    }
}
