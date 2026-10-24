package com.yourapp.wine.diagnostics

/** Debug-only label-level telemetry. No reference answers, OCR votes, ranking or field decisions. */
internal object PipelineFunnel {
    const val VERSION = 1
    enum class State { PASSED, BLOCKED, NOT_REACHED, NOT_RECORDED, NOT_APPLICABLE }
    data class Gate(val state: State, val reason: String = "")
    data class Step(val id: String, val branch: String, val title: String, val definition: String, val labOnly: Boolean = false, val productionOnly: Boolean = false)
    data class Label(val image: String, val attempt: Int, val gates: Map<String, Gate>)
    data class Count(val step: Step, val labels: Int, val passed: Int, val blocked: Int,
        val notReached: Int, val notRecorded: Int, val notApplicable: Int, val reasons: Map<String, Int>) {
        val reached get() = passed + blocked
        fun cells() = listOf(step.branch, step.title, passed.toString(), reached.toString(), blocked.toString(),
            notReached.toString(), notRecorded.toString(), notApplicable.toString(),
            reasons.entries.joinToString(" | ") { "${it.key}: ${it.value}" })
        fun csv(pipeline: String) = linkedMapOf("row_type" to "pipeline_step", "pipeline" to pipeline,
            "step_id" to step.id, "step_branch" to step.branch, "step" to step.title,
            "labels_attempted" to labels.toString(), "labels_passed" to passed.toString(), "labels_reached" to reached.toString(),
            "labels_blocked" to blocked.toString(), "labels_not_reached" to notReached.toString(),
            "labels_not_recorded" to notRecorded.toString(), "labels_not_applicable" to notApplicable.toString(),
            "step_reasons" to reasons.entries.joinToString(" | ") { "${it.key}: ${it.value}" },
            "step_definition" to step.definition, "funnel_version" to VERSION.toString())
    }
    val headers = listOf("Branch", "Step", "Passed labels", "Reached", "Blocked", "Not reached", "Not recorded", "N/A", "Blocked / missing evidence reasons")
    val explanation = "Distinct labels, latest attempt only; not line/candidate counts or accuracy. Reached = passed + blocked. " +
        "Branches and optional steps are not one decreasing chain. Not reached means an earlier gate prevented the step; " +
        "not recorded means the run lacks telemetry. Saved comparisons are not executed and have no funnel."

    val steps: List<Step> = buildList {
        fun step(id: String, branch: String, title: String, definition: String, lab: Boolean = false, production: Boolean = false) {
            add(Step(id, branch, title, definition, lab, production))
        }
        step("input.verified", "Shared", "Input copied + hash verified", "Packaged photo bytes copied and verified before OCR.")
        step("input.services", "Shared", "Scanner services initialized", "Local scanner service setup returned successfully.")
        step("input.native", "Production preparation", "Scanner native initialization", "The production scanner's OpenCV bootstrap succeeded; crop Lab native work belongs to the Paddle branch.", production = true)
        step("input.decoded", "Shared", "Image decoded", "The input image was decoded successfully.")
        step("input.quality", "Production preparation", "Image quality permits OCR", "Production quality assessment completed without a RED retake gate; YELLOW warnings may proceed. Crop benchmark modes do not run this step.", production = true)
        step("input.localized", "Production preparation", "Label localization completed", "Production localization completed, including an explicit full-frame fallback. Crop benchmark modes do not run this step.", production = true)
        step("input.prepared", "Shared", "OCR image prepared", "Crop/coordinate preparation completed, or production warp completed.")
        for ((engine, title) in listOf("mlkit" to "ML Kit", "paddle" to "Paddle Medium")) {
            step("$engine.started", title, "OCR started", "At least one engine pass was started.")
            if (engine == "paddle") {
                step("paddle.detected", title, "Text detection completed", "Detector returned a valid result, including an empty result.")
                step("paddle.regions", title, "Text regions found", "At least one text region was detected.")
                step("paddle.lines", title, "Line recognition attempted", "At least one detected text line reached recognition.")
            }
            step("$engine.completed", title, "OCR completed without failure/deadline", "All attempted engine work finished; no_text/no_regions are completed, not text successes.")
            step("$engine.text", title, "Nonblank OCR text returned", "At least one nonblank, successful recognition result, including real partial readings.")
            step("$engine.mapped", title, "Usable mapped OCR observation", "At least one nonblank reading with valid confidence and mapped normalized geometry.")
        }
        step("ocr.both_completed", "Rejoin", "Both engines completed", "Both OCR branches finished without a failure or deadline.")
        step("ocr.both_text", "Rejoin", "Both engines supplied usable text", "At least one usable mapped observation from each OCR engine, not necessarily agreeing text.")
        step("extract.kb", "Extraction", "Extraction knowledge base opened", "The local spelling/geography extraction knowledge base initialized successfully.")
        step("extract.form", "Extraction", "Raw form extraction completed", "The internal raw form anchor completed; not a second scored contender.")
        step("extract.observations", "Extraction", "Evidence observations available", "The evidence graph contains at least one original OCR observation.")
        step("extract.hypotheses", "Extraction", "Field hypotheses generated", "At least one field interpretation was generated from observations.")
        step("extract.assignment", "Extraction", "Guarded field assignment completed", "The selected guarded extraction produced a successful summary, including no_fields.")
        step("asset.manifest", "LWIN setup", "Manifest/provenance validated", "Packaged schema and owner-attested source gate passed.", true)
        step("asset.producer", "LWIN setup", "Producer DB installed + validated", "Producer bytes/hash, open, counts and native FTS check passed.", true)
        step("asset.wine", "LWIN setup", "Wine DB installed + validated", "Wine bytes/hash, open, counts and native FTS check passed.", true)
        step("asset.support", "LWIN setup", "Supporting lexicons opened", "All configured supporting layers opened; no extra OCR votes.", true)
        for (kind in listOf("producer", "wine")) {
            val title = if (kind == "producer") "Producer" else "Linked wine"
            step("$kind.invoked", title, "Resolver invoked", "The resolver was actually called.", true)
            step("$kind.execution", title, "Resolver completed within budget", "Returned normally, including abstention; exceptions and budget exhaustion block this row.", true)
            step("$kind.primary", title, "Verified primary index available", "The resolver received a verified primary index.", true)
            step("$kind.spans", title, "Span-count bound passed", "The preserved-original/alternative span list is within its bound.", true)
            if (kind == "wine") {
                step("wine.producer_observed", title, "Independently observed producer", "At least one producer has two-engine/disjoint evidence, including exact validation of an existing name.", true)
                step("wine.producer_compatible", title, "Producer compatible with existing value", "At least one independently observed producer survives existing-value protection.", true)
                step("wine.producer_bound", title, "Producer candidate bound passed", "No more than eight independently supported producer identities remain.", true)
            }
            for ((engine, name) in listOf("mlkit" to "ML Kit", "paddle" to "Paddle Medium")) {
                val branch = "$title · $name"
                step("$kind.$engine.eligible", branch, "Eligible observed span", "At least one span from this engine passes the field's eligibility rules.", true)
                step("$kind.$engine.lookup", branch, "Database lookup completed", "At least one eligible lookup returned normally, even if no candidates were found.", true)
                step("$kind.$engine.retrieved", branch, "Reference candidates returned", "At least one lookup returned candidates; reference records are not OCR votes.", true)
                if (kind == "wine") step("wine.$engine.disjoint", branch, "Separate producer/wine observations", "At least one linked candidate uses observations disjoint from producer evidence and a different name key.", true)
                step("$kind.$engine.distance", branch, "Candidate passed edit distance", "At least one candidate passes the unchanged bounded distance rule.", true)
            }
            step("$kind.independent", title, "Two-engine + disjoint agreement", "At least one identity/name group has independent support from both engines.", true)
            step("$kind.unambiguous", title, "One viable identity/name group", "Exactly one independently supported group remains.", true)
            step("$kind.separated", title, "Separated from competing candidates", "No observed rival is too close under the existing separation rule.", true)
            if (kind == "wine") step("wine.producer_consistent", title, "Consistent with selected producer", "The linked wine does not contradict an independently selected producer.", true)
            step("$kind.existing", title, "Existing-value protection passed", "The observed suggestion can retain or replace the current automatic field under existing safeguards.", true)
            step("$kind.selected", title, "Name confirmed / suggested", "A supported name was retained or selected; this does not necessarily change a value.", true)
            step("$kind.changed", title, "Field value changed", "The resolver changed the displayed field, rather than confirming an existing value.", true)
        }
        step("final.output", "Final", "Selected pipeline output completed", "The current selected pipeline produced a successful result; not a saved comparison.")
        for (field in listOf("producer", "wine_name", "country", "region", "subregion", "vintage")) {
            step("final.$field", "Final fields", "$field populated", "The final exported/displayed field is nonblank. This is coverage, not correctness.")
        }
        step("score.complete", "Scoring only", "All six fields correct", "Frozen-reference evaluation only, including correctly expected blanks; unresolved forms are N/A.")
        step("score.at_most_one", "Scoring only", "At most one field error", "Frozen-reference evaluation only; failed eligible forms count as multiple errors.")
    }

    /** OR within a label: multiple lines, joins, database rows and variants never increase a count. */
    class Recorder(private val prefix: String = "") {
        private val gates = linkedMapOf<String, Gate>()
        private val rejected = mutableMapOf<String, LinkedHashSet<String>>()
        fun visit(id: String, passed: Boolean, reason: String = "") {
            val key = prefix + id
            if (gates[key]?.state != State.PASSED) {
                if (passed) rejected.remove(key)
                else if (reason.isNotBlank()) rejected.getOrPut(key) { linkedSetOf() }.add(reason)
                gates[key] = Gate(if (passed) State.PASSED else State.BLOCKED, if (passed) "" else rejected[key].orEmpty().joinToString("; ").take(240))
            }
        }
        fun snapshot(): Map<String, Gate> = gates.toMap()
        fun fields(): Map<String, String> = buildMap {
            put("funnel_version", VERSION.toString())
            for ((id, gate) in gates) {
                put("funnel.$id.status", gate.state.name)
                if (gate.reason.isNotBlank()) put("funnel.$id.reason", gate.reason)
            }
        }
    }

    fun fromFields(fields: Map<String, String>): Map<String, Gate> = buildMap {
        for ((key, value) in fields) {
            if (!key.startsWith("funnel.") || !key.endsWith(".status")) continue
            val id = key.removePrefix("funnel.").removeSuffix(".status")
            val state = State.entries.firstOrNull { it.name == value } ?: continue
            put(id, Gate(state, fields["funnel.$id.reason"].orEmpty()))
        }
    }

    fun aggregate(labels: List<Label>): List<Count> {
        val latest = labels.groupBy { it.image }.values.map { attempts -> attempts.maxBy { it.attempt } }
        return steps.map { step ->
            val outcomes = latest.map { it.gates[step.id] ?: Gate(State.NOT_RECORDED, "Stage telemetry absent") }
            val counts = outcomes.groupingBy { it.state }.eachCount()
            val reasons = outcomes.filter { it.state != State.PASSED && it.state != State.NOT_APPLICABLE && it.reason.isNotBlank() }
                .groupingBy { it.reason }.eachCount().toSortedMap()
            Count(step, latest.size, counts[State.PASSED] ?: 0, counts[State.BLOCKED] ?: 0,
                counts[State.NOT_REACHED] ?: 0, counts[State.NOT_RECORDED] ?: 0, counts[State.NOT_APPLICABLE] ?: 0, reasons)
        }
    }
}
