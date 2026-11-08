package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import org.json.JSONObject

/**
 * Frozen reference target for a single benchmark field (`producer`, `wine_name`, `country`,
 * `region`, `subregion`, `vintage`) from `assets/wine-app-labels-v1/scoring.json`.
 */
data class GraniteFieldTarget(
    val status: String,
    val accepted: Set<String>,
    val display: String = ""
)

/**
 * One packaged workbook wine label from `assets/wine-app-labels-v1` — the exact same 124-image
 * dataset that the GLiNER lane lab (`GlinerLabCorpus`) and Lab v7 are measured on.
 */
data class GraniteDatasetSample(
    val image: String,
    val reviewNumber: Int,
    val sourceFilename: String,
    val sha256: String,
    val included: Boolean,
    val targets: Map<String, GraniteFieldTarget>
) {
    val displayName: String
        get() = "#$reviewNumber · $sourceFilename"

    fun target(field: String): GraniteFieldTarget =
        targets[field] ?: GraniteFieldTarget("unresolved", emptySet(), "")
}

/**
 * Per-field comparison between Granite's extracted fields and the frozen `scoring.json` target.
 */
data class GraniteFieldScoreResult(
    val field: String,
    val label: String,
    val expected: String,
    val observed: String,
    val outcome: String
) {
    val isCorrect: Boolean
        get() = PhoneCorpusMath.correct(outcome)
}

/**
 * Row state for one packaged wine label in the 124-label `wine-app-labels-v1` dataset.
 */
data class GraniteDatasetRow(
    val index: Int,
    val sample: GraniteDatasetSample,
    val status: String = "Waiting",
    val message: String = "",
    val runResult: GraniteWorkflowRunResult? = null,
    val observedFields: Map<String, String> = emptyMap(),
    val fieldResults: List<GraniteFieldScoreResult> = emptyList(),
    val eligible: Boolean = false,
    val errorCount: Int = 0
) {
    val displayName: String
        get() = sample.displayName

    val complete: Boolean
        get() = status == "complete" && eligible && errorCount == 0
}

/**
 * Aggregate scoreboard over the packaged 124-label dataset using the exact same `PhoneCorpusMath`
 * semantics as `GlinerAggregate` in `GlinerLabViewModel.kt`.
 */
data class GraniteDatasetAggregate(
    val attempted: Int = 0,
    val eligible: Int = 0,
    val complete: Int = 0,
    val oneError: Int = 0,
    val multiError: Int = 0,
    val failed: Int = 0,
    val fieldCorrect: Map<String, Int> = emptyMap(),
    val fieldTotal: Map<String, Int> = emptyMap(),
    val stopped: Boolean = false
) {
    fun text(): String =
        "Fully correct $complete/$eligible · one error $oneError · 2+ errors $multiError · failed $failed · attempted $attempted"
}

/**
 * Read-only loader and scorer for the packaged 124-label dataset (`assets/wine-app-labels-v1`)
 * shared with `GLiNER lane lab` (`GlinerLabCorpus`).
 *
 * Targets are never passed into Dual OCR or Granite 4.2 inference; they are evaluated only after
 * the pipeline has produced its predictions.
 */
object GraniteLabCorpus {
    const val ASSET_ROOT = "wine-app-labels-v1"

    val fields: List<String> = PhoneCorpusMath.fields

    val fieldLabels: LinkedHashMap<String, String> = linkedMapOf(
        "producer" to "Producer",
        "wine_name" to "Wine name",
        "country" to "Country",
        "region" to "Region",
        "subregion" to "Subregion",
        "vintage" to "Vintage"
    )

    private fun GraniteFieldTarget.toCorpusTarget(): PhoneCorpusMath.Target =
        PhoneCorpusMath.Target(status = status, accepted = accepted, display = display)

    fun load(context: Context): List<GraniteDatasetSample> {
        val inputs = JSONObject(
            context.assets.open("$ASSET_ROOT/inputs.json").bufferedReader().use { it.readText() }
        )
        val scoring = JSONObject(
            context.assets.open("$ASSET_ROOT/scoring.json").bufferedReader().use { it.readText() }
        )
        val evaluation = JSONObject(
            context.assets.open("$ASSET_ROOT/evaluation-set.json").bufferedReader().use { it.readText() }
        )
        val includedArray = evaluation.getJSONArray("included_images")
        val included = (0 until includedArray.length())
            .map { includedArray.getJSONObject(it).getString("image") }
            .toSet()
        check(included.size == evaluation.getInt("included_count")) {
            "Invalid Lab v7 evaluation selection"
        }

        val targetsByImage = linkedMapOf<String, JSONObject>()
        val scoredArray = scoring.getJSONArray("images")
        for (index in 0 until scoredArray.length()) {
            val entry = scoredArray.getJSONObject(index)
            targetsByImage[entry.getString("image")] = entry
        }

        val imagesArray = inputs.getJSONArray("images")
        val selected = (0 until imagesArray.length())
            .map { imagesArray.getJSONObject(it) }
            .filter { it.getString("image") in included }

        return selected.mapIndexed { index, entry ->
            val image = entry.getString("image")
            val fieldsObj = targetsByImage[image]?.optJSONObject("fields") ?: JSONObject()
            val parsedTargets = linkedMapOf<String, GraniteFieldTarget>()
            fieldsObj.keys().forEach { field ->
                val targetObj = fieldsObj.optJSONObject(field) ?: return@forEach
                val acceptedArray = targetObj.optJSONArray("accepted")
                parsedTargets[field] = GraniteFieldTarget(
                    status = targetObj.optString("status", "unresolved"),
                    accepted = (0 until (acceptedArray?.length() ?: 0))
                        .mapNotNull { acceptedArray?.optString(it) }
                        .toSet(),
                    display = targetObj.optString("target", "")
                )
            }
            GraniteDatasetSample(
                image = image,
                reviewNumber = entry.optInt("review_number", index + 1),
                sourceFilename = entry.optString("source_filename", image),
                sha256 = entry.optString("sha256", ""),
                included = image in included,
                targets = parsedTargets
            )
        }.sortedBy { it.reviewNumber }
    }

    /**
     * Decodes a packaged dataset wine label (`assets/wine-app-labels-v1/images/${sample.image}`)
     * into an `ARGB_8888` [Bitmap].
     */
    fun decodeBitmap(context: Context, sample: GraniteDatasetSample): Bitmap {
        val decoded = context.assets.open("$ASSET_ROOT/images/${sample.image}").use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: error("Unable to decode dataset image ${sample.image}")
        return if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    /**
     * Maps Granite's 12-field [GraniteWineLabelJson] output onto the 6 standard Lab v7 / GLiNER
     * benchmark fields (`producer`, `wine_name`, `country`, `region`, `subregion`, `vintage`).
     */
    fun mapToBenchmarkFields(structured: GraniteWineLabelJson): Map<String, String> {
        val rawAppellation = structured.appellationOrRegion?.trim().orEmpty()
        val normAppellation = PhoneCorpusMath.normalize(rawAppellation)
        val matchedOrigin = if (normAppellation.isNotEmpty()) {
            BundledOrigins.terms.firstOrNull { term ->
                PhoneCorpusMath.normalize(term.name) == normAppellation ||
                    term.aliases.any { PhoneCorpusMath.normalize(it) == normAppellation }
            }
        } else {
            null
        }

        val resolvedCountry = structured.country?.trim()?.takeIf { it.isNotEmpty() }
            ?: matchedOrigin?.country.orEmpty()
        val resolvedRegion = when {
            matchedOrigin != null && matchedOrigin.region.isNotBlank() -> matchedOrigin.region
            else -> rawAppellation
        }
        val resolvedSubregion = when {
            matchedOrigin != null &&
                matchedOrigin.name.isNotBlank() &&
                PhoneCorpusMath.normalize(matchedOrigin.name) != PhoneCorpusMath.normalize(matchedOrigin.region) ->
                matchedOrigin.name
            else -> ""
        }
        val resolvedWineName = structured.wineNameOrCuvee?.trim()?.takeIf { it.isNotEmpty() }
            ?: structured.varietals.firstOrNull()?.trim().orEmpty()
        val resolvedVintage = if (structured.isNonVintage || structured.vintage == null) {
            ""
        } else {
            structured.vintage.toString()
        }

        return linkedMapOf(
            "producer" to structured.producerOrWinery?.trim().orEmpty(),
            "wine_name" to resolvedWineName,
            "country" to resolvedCountry,
            "region" to resolvedRegion,
            "subregion" to resolvedSubregion,
            "vintage" to resolvedVintage
        )
    }

    /**
     * Evaluates a completed [GraniteWorkflowRunResult] against the sample's frozen `scoring.json`
     * targets using `PhoneCorpusMath`.
     */
    fun evaluateRow(
        index: Int,
        sample: GraniteDatasetSample,
        runResult: GraniteWorkflowRunResult
    ): GraniteDatasetRow {
        val observed = mapToBenchmarkFields(runResult.structuredJson)
        val targetMap = fields.associateWith { sample.target(it).toCorpusTarget() }
        val eligible = PhoneCorpusMath.eligible(targetMap)
        val fieldResults = fields.map { field ->
            val target = sample.target(field)
            val value = observed[field].orEmpty()
            val outcome = PhoneCorpusMath.outcome(target.toCorpusTarget(), value, failed = false)
            GraniteFieldScoreResult(
                field = field,
                label = fieldLabels.getValue(field),
                expected = target.display,
                observed = value,
                outcome = outcome
            )
        }
        val errors = fieldResults.count { !it.isCorrect }
        val populatedCount = observed.values.count { it.isNotBlank() }
        return GraniteDatasetRow(
            index = index,
            sample = sample,
            status = "complete",
            message = "$populatedCount/6 benchmark fields extracted",
            runResult = runResult,
            observedFields = observed,
            fieldResults = fieldResults,
            eligible = eligible,
            errorCount = errors
        )
    }

    /**
     * Creates a failed [GraniteDatasetRow] if an unexpected error occurs on one sample.
     */
    fun failedRow(
        index: Int,
        sample: GraniteDatasetSample,
        errorMessage: String
    ): GraniteDatasetRow {
        val targetMap = fields.associateWith { sample.target(it).toCorpusTarget() }
        val eligible = PhoneCorpusMath.eligible(targetMap)
        val fieldResults = fields.map { field ->
            GraniteFieldScoreResult(
                field = field,
                label = fieldLabels.getValue(field),
                expected = sample.target(field).display,
                observed = "",
                outcome = "execution_failed"
            )
        }
        return GraniteDatasetRow(
            index = index,
            sample = sample,
            status = "error",
            message = errorMessage,
            runResult = null,
            observedFields = emptyMap(),
            fieldResults = fieldResults,
            eligible = eligible,
            errorCount = fields.size
        )
    }

    /**
     * Aggregates all scored rows in [rows] using the same rules as `aggregateGlinerRows`.
     */
    fun aggregate(rows: List<GraniteDatasetRow>, stopped: Boolean = false): GraniteDatasetAggregate {
        val scored = rows.filter { it.sample.included && (it.runResult != null || it.status == "error") }
        val correct = fields.associateWith { field ->
            scored.count { row ->
                row.sample.target(field).status != "unresolved" &&
                    row.fieldResults.firstOrNull { it.field == field }?.isCorrect == true
            }
        }
        val total = fields.associateWith { field ->
            scored.count { row -> row.sample.target(field).status != "unresolved" }
        }
        return GraniteDatasetAggregate(
            attempted = scored.size,
            eligible = scored.count { it.eligible },
            complete = scored.count { it.complete },
            oneError = scored.count { it.eligible && it.status == "complete" && it.errorCount == 1 },
            multiError = scored.count { it.eligible && it.status == "complete" && it.errorCount > 1 },
            failed = scored.count { it.status != "complete" },
            fieldCorrect = correct,
            fieldTotal = total,
            stopped = stopped
        )
    }
}
