package com.yourapp.wine.phonecorpus

import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.textlab.TextLabCsv
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lightweight, durable scalar results log (`benchmark-results-log.csv`) separate from the scored
 * and raw diagnostic CSV exports.
 *
 * Stores ONLY run metadata (date, time, build #, branch, model version) and scalar scoreboard
 * counts (attempted, eligible, complete, 1 error, 2+ errors, failed, correct values, wrong,
 * missing, correct blanks, total correct, unsupported, unresolved, deadline/partial, and per-field
 * correct/incorrect) — never raw OCR text, traces, tokens, or images — plus a composite
 * `best_build` summary row recording the highest score achieved for every parameter alongside the
 * exact build number each best value came from.
 */
internal class WineAppLabelsBuildLog(
    private val logFile: File,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    data class BuildInfo(
        val buildKey: String,
        val buildNumber: String,
        val versionCode: String,
        val buildCommit: String,
        val branch: String,
    )

    data class FieldScore(
        val correct: Int,
        val incorrect: Int,
    ) {
        val total: Int get() = correct + incorrect
        fun label(): String = "$correct/$incorrect"
    }

    data class MetricBest<T>(
        val value: T,
        val buildNumber: String,
        val buildCommit: String,
        val buildKey: String,
        val branch: String,
        val runId: String,
        val timestampMs: Long,
    )

    data class BestBuildSummary(
        val attempted: MetricBest<Int>,
        val eligible: MetricBest<Int>,
        val complete: MetricBest<Pair<Int, Int>>,
        val oneError: MetricBest<Int>,
        val twoPlusErrors: MetricBest<Int>,
        val failed: MetricBest<Int>,
        val correctValues: MetricBest<Int>,
        val wrong: MetricBest<Int>,
        val missing: MetricBest<Int>,
        val correctBlanks: MetricBest<Int>,
        val totalCorrect: MetricBest<Int>,
        val unsupported: MetricBest<Int>,
        val unresolved: MetricBest<Int>,
        val deadlinePartial: MetricBest<Int>,
        val fieldBests: Map<String, MetricBest<FieldScore>>,
    ) {
        val fieldScores: Map<String, FieldScore>
            get() = fieldBests.mapValues { it.value.value }

        fun toTableRow(title: String = BEST_BUILD_ROW_TITLE): List<String> = listOf(
            title,
            attempted.value.toString(),
            eligible.value.toString(),
            "${complete.value.first}/${complete.value.second}",
            oneError.value.toString(),
            twoPlusErrors.value.toString(),
            failed.value.toString(),
            correctValues.value.toString(),
            wrong.value.toString(),
            missing.value.toString(),
            correctBlanks.value.toString(),
            unsupported.value.toString(),
            unresolved.value.toString(),
            deadlinePartial.value.toString(),
        )

        fun provenanceLine(): String {
            val parts = mutableListOf(
                "Fully correct ${complete.value.first}/${complete.value.second} [${complete.buildNumber}]",
                "Total correct ${totalCorrect.value} [${totalCorrect.buildNumber}]",
                "Correct values ${correctValues.value} [${correctValues.buildNumber}]",
            )
            for (field in PhoneCorpusMath.fields) {
                val best = fieldBests[field] ?: continue
                parts += "$field ${best.value.label()} [${best.buildNumber}]"
            }
            return "Lab v4 Best build sources: " + parts.joinToString(" · ")
        }

        fun toCsvMap(zoneId: ZoneId, mode: PhoneCorpusRunner.Mode): Map<String, String> {
            val latestTs = (listOf(
                attempted.timestampMs,
                complete.timestampMs,
                totalCorrect.timestampMs,
                correctValues.timestampMs,
                twoPlusErrors.timestampMs,
                wrong.timestampMs,
                missing.timestampMs,
            ) + fieldBests.values.map { it.timestampMs }).maxOrNull() ?: 0L
            val zoned = Instant.ofEpochMilli(latestTs).atZone(zoneId)
            val sourceBuilds = (listOf(
                complete.buildNumber,
                totalCorrect.buildNumber,
                correctValues.buildNumber,
                correctBlanks.buildNumber,
                oneError.buildNumber,
                twoPlusErrors.buildNumber,
                failed.buildNumber,
                wrong.buildNumber,
                missing.buildNumber,
                unsupported.buildNumber,
                deadlinePartial.buildNumber,
            ) + PhoneCorpusMath.fields.mapNotNull { fieldBests[it]?.buildNumber })
                .filter(String::isNotBlank)
                .distinct()
            val summaryBuildLabel = when (sourceBuilds.size) {
                0 -> ""
                1 -> sourceBuilds.single()
                else -> "composite: ${sourceBuilds.joinToString("; ")}"
            }
            val sourceCommits = (listOf(
                complete.buildCommit,
                totalCorrect.buildCommit,
                correctValues.buildCommit,
            ) + PhoneCorpusMath.fields.mapNotNull { fieldBests[it]?.buildCommit })
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("; ")
            val sourceBranches = (listOf(
                complete.branch,
                totalCorrect.branch,
            ) + PhoneCorpusMath.fields.mapNotNull { fieldBests[it]?.branch })
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("; ")

            return linkedMapOf(
                "row_type" to BEST_BUILD_ROW_TYPE,
                "date" to DATE_FMT.format(zoned),
                "time" to TIME_FMT.format(zoned),
                "timestamp_ms" to latestTs.toString(),
                "build_number" to summaryBuildLabel,
                "version_code" to "",
                "build_commit" to sourceCommits,
                "build_key" to BEST_BUILD_ROW_TYPE,
                "branch" to sourceBranches,
                "model_version" to BEST_BUILD_ROW_TITLE,
                "pipeline_id" to mode.pipelineId,
                "run_id" to BEST_BUILD_ROW_TYPE,
                "attempted" to attempted.value.toString(),
                "eligible" to eligible.value.toString(),
                "complete" to complete.value.first.toString(),
                "one_error" to oneError.value.toString(),
                "two_plus_errors" to twoPlusErrors.value.toString(),
                "failed" to failed.value.toString(),
                "correct_values" to correctValues.value.toString(),
                "wrong" to wrong.value.toString(),
                "missing" to missing.value.toString(),
                "correct_blanks" to correctBlanks.value.toString(),
                "total_correct" to totalCorrect.value.toString(),
                "unsupported" to unsupported.value.toString(),
                "unresolved" to unresolved.value.toString(),
                "deadline_partial" to deadlinePartial.value.toString(),
                "producer_correct" to (fieldBests["producer"]?.value?.correct?.toString() ?: ""),
                "producer_incorrect" to (fieldBests["producer"]?.value?.incorrect?.toString() ?: ""),
                "wine_name_correct" to (fieldBests["wine_name"]?.value?.correct?.toString() ?: ""),
                "wine_name_incorrect" to (fieldBests["wine_name"]?.value?.incorrect?.toString() ?: ""),
                "country_correct" to (fieldBests["country"]?.value?.correct?.toString() ?: ""),
                "country_incorrect" to (fieldBests["country"]?.value?.incorrect?.toString() ?: ""),
                "region_correct" to (fieldBests["region"]?.value?.correct?.toString() ?: ""),
                "region_incorrect" to (fieldBests["region"]?.value?.incorrect?.toString() ?: ""),
                "subregion_correct" to (fieldBests["subregion"]?.value?.correct?.toString() ?: ""),
                "subregion_incorrect" to (fieldBests["subregion"]?.value?.incorrect?.toString() ?: ""),
                "vintage_correct" to (fieldBests["vintage"]?.value?.correct?.toString() ?: ""),
                "vintage_incorrect" to (fieldBests["vintage"]?.value?.incorrect?.toString() ?: ""),
                "best_complete_build" to complete.buildNumber,
                "best_total_correct_build" to totalCorrect.buildNumber,
                "best_correct_values_build" to correctValues.buildNumber,
                "best_correct_blanks_build" to correctBlanks.buildNumber,
                "best_one_error_build" to oneError.buildNumber,
                "best_two_plus_errors_build" to twoPlusErrors.buildNumber,
                "best_failed_build" to failed.buildNumber,
                "best_wrong_build" to wrong.buildNumber,
                "best_missing_build" to missing.buildNumber,
                "best_unsupported_build" to unsupported.buildNumber,
                "best_deadline_partial_build" to deadlinePartial.buildNumber,
                "best_producer_build" to (fieldBests["producer"]?.buildNumber ?: ""),
                "best_wine_name_build" to (fieldBests["wine_name"]?.buildNumber ?: ""),
                "best_country_build" to (fieldBests["country"]?.buildNumber ?: ""),
                "best_region_build" to (fieldBests["region"]?.buildNumber ?: ""),
                "best_subregion_build" to (fieldBests["subregion"]?.buildNumber ?: ""),
                "best_vintage_build" to (fieldBests["vintage"]?.buildNumber ?: ""),
            )
        }
    }

    data class Entry(
        val date: String,
        val time: String,
        val timestampMs: Long,
        val buildNumber: String,
        val versionCode: String,
        val buildCommit: String,
        val buildKey: String,
        val branch: String,
        val modelVersion: String,
        val pipelineId: String,
        val runId: String,
        val attempted: Int,
        val eligible: Int,
        val complete: Int,
        val oneError: Int,
        val twoPlusErrors: Int,
        val failed: Int,
        val correctValues: Int,
        val wrong: Int,
        val missing: Int,
        val correctBlanks: Int,
        val unsupported: Int,
        val unresolved: Int,
        val deadlinePartial: Int,
        val fieldScores: Map<String, FieldScore> = emptyMap(),
    ) {
        val totalCorrect: Int
            get() = correctValues + correctBlanks

        val hasFieldScores: Boolean
            get() = PhoneCorpusMath.fields.all { it in fieldScores }

        fun <T> toMetricBest(extract: (Entry) -> T): MetricBest<T> = MetricBest(
            value = extract(this),
            buildNumber = buildNumber,
            buildCommit = buildCommit,
            buildKey = buildKey,
            branch = branch,
            runId = runId,
            timestampMs = timestampMs,
        )

        fun toCsvMap(): Map<String, String> = linkedMapOf(
            "row_type" to RUN_ROW_TYPE,
            "date" to date,
            "time" to time,
            "timestamp_ms" to timestampMs.toString(),
            "build_number" to buildNumber,
            "version_code" to versionCode,
            "build_commit" to buildCommit,
            "build_key" to buildKey,
            "branch" to branch,
            "model_version" to modelVersion,
            "pipeline_id" to pipelineId,
            "run_id" to runId,
            "attempted" to attempted.toString(),
            "eligible" to eligible.toString(),
            "complete" to complete.toString(),
            "one_error" to oneError.toString(),
            "two_plus_errors" to twoPlusErrors.toString(),
            "failed" to failed.toString(),
            "correct_values" to correctValues.toString(),
            "wrong" to wrong.toString(),
            "missing" to missing.toString(),
            "correct_blanks" to correctBlanks.toString(),
            "total_correct" to totalCorrect.toString(),
            "unsupported" to unsupported.toString(),
            "unresolved" to unresolved.toString(),
            "deadline_partial" to deadlinePartial.toString(),
            "producer_correct" to (fieldScores["producer"]?.correct?.toString() ?: ""),
            "producer_incorrect" to (fieldScores["producer"]?.incorrect?.toString() ?: ""),
            "wine_name_correct" to (fieldScores["wine_name"]?.correct?.toString() ?: ""),
            "wine_name_incorrect" to (fieldScores["wine_name"]?.incorrect?.toString() ?: ""),
            "country_correct" to (fieldScores["country"]?.correct?.toString() ?: ""),
            "country_incorrect" to (fieldScores["country"]?.incorrect?.toString() ?: ""),
            "region_correct" to (fieldScores["region"]?.correct?.toString() ?: ""),
            "region_incorrect" to (fieldScores["region"]?.incorrect?.toString() ?: ""),
            "subregion_correct" to (fieldScores["subregion"]?.correct?.toString() ?: ""),
            "subregion_incorrect" to (fieldScores["subregion"]?.incorrect?.toString() ?: ""),
            "vintage_correct" to (fieldScores["vintage"]?.correct?.toString() ?: ""),
            "vintage_incorrect" to (fieldScores["vintage"]?.incorrect?.toString() ?: ""),
        )
    }

    @Synchronized
    fun readEntries(): List<Entry> {
        if (!logFile.isFile) return emptyList()
        val parsed = ArrayList<Entry>()
        runCatching {
            var headers: List<String>? = null
            logFile.bufferedReader(Charsets.UTF_8).use { reader ->
                TextLabCsv.read(reader) { record ->
                    if (headers == null) {
                        headers = record
                    } else {
                        val cols = headers.orEmpty()
                        val map = HashMap<String, String>(cols.size)
                        for (i in cols.indices) {
                            map[cols[i]] = record.getOrElse(i) { "" }
                        }
                        fromCsvMap(map)?.let(parsed::add)
                    }
                }
            }
        }
        return parsed
    }

    private fun hasPersistedBestBuildRow(): Boolean {
        if (!logFile.isFile) return false
        var found = false
        runCatching {
            var headers: List<String>? = null
            logFile.bufferedReader(Charsets.UTF_8).use { reader ->
                TextLabCsv.read(reader) { record ->
                    if (headers == null) {
                        headers = record
                    } else {
                        val idx = headers.orEmpty().indexOf("row_type")
                        if (idx >= 0 && record.getOrNull(idx) == BEST_BUILD_ROW_TYPE) {
                            found = true
                        }
                    }
                }
            }
        }
        return found
    }

    /**
     * Records or updates the scalar summary row for [runId] and refreshes the composite
     * `best_build` high-water-mark row with source build numbers. No raw text, trace rows, or
     * photos are accepted or persisted.
     */
    @Synchronized
    fun recordSummary(
        runId: String,
        buildInfo: BuildInfo,
        mode: PhoneCorpusRunner.Mode,
        summaryRow: List<String>,
        timestampMs: Long = System.currentTimeMillis(),
        fieldScores: Map<String, FieldScore> = emptyMap(),
    ) {
        if (runId.isBlank() || summaryRow.size < EXPECTED_TABLE_COLUMNS) return
        val attempted = summaryRow[1].toIntOrNull() ?: return
        if (attempted <= 0) return
        val eligible = summaryRow[2].toIntOrNull() ?: 0
        val complete = summaryRow[3].substringBefore('/').toIntOrNull() ?: 0
        val zoned = Instant.ofEpochMilli(timestampMs).atZone(zoneId)
        val newEntry = Entry(
            date = DATE_FMT.format(zoned),
            time = TIME_FMT.format(zoned),
            timestampMs = timestampMs,
            buildNumber = buildInfo.buildNumber,
            versionCode = buildInfo.versionCode,
            buildCommit = buildInfo.buildCommit,
            buildKey = buildInfo.buildKey,
            branch = buildInfo.branch,
            modelVersion = mode.title,
            pipelineId = mode.pipelineId,
            runId = runId,
            attempted = attempted,
            eligible = eligible,
            complete = complete,
            oneError = summaryRow[4].toIntOrNull() ?: 0,
            twoPlusErrors = summaryRow[5].toIntOrNull() ?: 0,
            failed = summaryRow[6].toIntOrNull() ?: 0,
            correctValues = summaryRow[7].toIntOrNull() ?: 0,
            wrong = summaryRow[8].toIntOrNull() ?: 0,
            missing = summaryRow[9].toIntOrNull() ?: 0,
            correctBlanks = summaryRow[10].toIntOrNull() ?: 0,
            unsupported = summaryRow[11].toIntOrNull() ?: 0,
            unresolved = summaryRow[12].toIntOrNull() ?: 0,
            deadlinePartial = summaryRow[13].toIntOrNull() ?: 0,
            fieldScores = fieldScores,
        )

        val existing = readEntries().toMutableList()
        val idx = existing.indexOfFirst { it.runId == runId && it.modelVersion == mode.title }
        if (idx >= 0) {
            val merged = if (newEntry.fieldScores.isEmpty() && existing[idx].fieldScores.isNotEmpty()) {
                newEntry.copy(fieldScores = existing[idx].fieldScores)
            } else {
                newEntry
            }
            if (existing[idx].copy(date = merged.date, time = merged.time, timestampMs = merged.timestampMs) == merged &&
                hasPersistedBestBuildRow()
            ) {
                return
            }
            existing[idx] = merged
        } else {
            existing.add(newEntry)
        }
        existing.sortBy { it.timestampMs }
        writeEntries(existing, mode)
    }

    /**
     * Ensures an existing log file on disk includes the composite `best_build` summary row and
     * per-parameter build provenance columns even before a new run is started.
     */
    @Synchronized
    fun syncBestBuildSummary(mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB) {
        val entries = readEntries()
        if (entries.isEmpty() || hasPersistedBestBuildRow()) return
        writeEntries(entries.sortedBy { it.timestampMs }, mode)
    }

    /**
     * Computes the composite "Best build" across all logged runs for [mode]: each parameter
     * independently selects the highest score ever achieved along with the build it came from.
     * Error/failure minimums are evaluated over the fullest attempted run cohort so a partial
     * in-progress run (e.g. 5/125 photos) never claims a false low error count.
     */
    @Synchronized
    fun bestBuildSummary(
        mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
    ): BestBuildSummary? = computeBestBuild(readEntries(), mode)

    fun bestBuildRow(
        mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
        columnCount: Int = EXPECTED_TABLE_COLUMNS,
    ): List<String> =
        bestBuildSummary(mode)?.toTableRow(BEST_BUILD_ROW_TITLE)
            ?: emptyBestBuildRow(columnCount)

    private fun computeBestBuild(
        allEntries: List<Entry>,
        mode: PhoneCorpusRunner.Mode,
    ): BestBuildSummary? {
        val candidates = allEntries.filter {
            it.modelVersion == mode.title && it.buildKey.isNotBlank() && it.attempted > 0
        }
        if (candidates.isEmpty()) return null
        val maxAttempted = candidates.maxOf { it.attempted }
        val fullPool = candidates.filter { it.attempted == maxAttempted }

        val bestAttempted = candidates.maxWith(
            compareBy<Entry> { it.attempted }.thenBy { it.complete }.thenBy { it.timestampMs },
        )
        val bestEligible = candidates.maxWith(
            compareBy<Entry> { it.eligible }.thenBy { it.complete }.thenBy { it.timestampMs },
        )
        val bestComplete = candidates.maxWith(
            compareBy<Entry> { it.complete }
                .thenBy { it.eligible }
                .thenBy { it.totalCorrect }
                .thenBy { it.timestampMs },
        )
        val bestOneError = fullPool.maxWith(
            compareBy<Entry> { it.complete + it.oneError }
                .thenBy { it.complete }
                .thenBy { -it.oneError }
                .thenBy { it.timestampMs },
        )
        val bestTwoPlus = fullPool.minWith(
            compareBy<Entry> { it.twoPlusErrors }
                .thenByDescending { it.complete }
                .thenByDescending { it.timestampMs },
        )
        val bestFailed = fullPool.minWith(
            compareBy<Entry> { it.failed }
                .thenByDescending { it.complete }
                .thenByDescending { it.timestampMs },
        )
        val bestCorrectValues = candidates.maxWith(
            compareBy<Entry> { it.correctValues }
                .thenBy { it.attempted }
                .thenBy { -(it.wrong + it.missing) }
                .thenBy { it.timestampMs },
        )
        val bestWrong = fullPool.minWith(
            compareBy<Entry> { it.wrong }
                .thenByDescending { it.correctValues }
                .thenByDescending { it.timestampMs },
        )
        val bestMissing = fullPool.minWith(
            compareBy<Entry> { it.missing }
                .thenByDescending { it.correctValues }
                .thenByDescending { it.timestampMs },
        )
        val bestCorrectBlanks = candidates.maxWith(
            compareBy<Entry> { it.correctBlanks }
                .thenBy { it.correctValues }
                .thenBy { it.timestampMs },
        )
        val bestTotalCorrect = candidates.maxWith(
            compareBy<Entry> { it.totalCorrect }
                .thenBy { it.complete }
                .thenBy { it.attempted }
                .thenBy { it.timestampMs },
        )
        val bestUnsupported = fullPool.minWith(
            compareBy<Entry> { it.unsupported }
                .thenByDescending { it.correctValues }
                .thenByDescending { it.timestampMs },
        )
        val bestUnresolved = fullPool.maxWith(
            compareBy<Entry> { it.attempted }.thenBy { it.timestampMs },
        )
        val bestDeadline = fullPool.minWith(
            compareBy<Entry> { it.deadlinePartial }
                .thenByDescending { it.complete }
                .thenByDescending { it.timestampMs },
        )

        val fieldBests = buildMap {
            for (field in PhoneCorpusMath.fields) {
                val withField = candidates.filter { entry ->
                    val score = entry.fieldScores[field]
                    score != null && score.total > 0
                }
                val winner = withField.maxWithOrNull(
                    compareBy<Entry> { it.fieldScores.getValue(field).correct }
                        .thenBy { it.fieldScores.getValue(field).total }
                        .thenBy { -it.fieldScores.getValue(field).incorrect }
                        .thenBy { it.timestampMs },
                ) ?: continue
                put(field, winner.toMetricBest { it.fieldScores.getValue(field) })
            }
        }

        return BestBuildSummary(
            attempted = bestAttempted.toMetricBest { it.attempted },
            eligible = bestEligible.toMetricBest { it.eligible },
            complete = bestComplete.toMetricBest { it.complete to it.eligible },
            oneError = bestOneError.toMetricBest { it.oneError },
            twoPlusErrors = bestTwoPlus.toMetricBest { it.twoPlusErrors },
            failed = bestFailed.toMetricBest { it.failed },
            correctValues = bestCorrectValues.toMetricBest { it.correctValues },
            wrong = bestWrong.toMetricBest { it.wrong },
            missing = bestMissing.toMetricBest { it.missing },
            correctBlanks = bestCorrectBlanks.toMetricBest { it.correctBlanks },
            totalCorrect = bestTotalCorrect.toMetricBest { it.totalCorrect },
            unsupported = bestUnsupported.toMetricBest { it.unsupported },
            unresolved = bestUnresolved.toMetricBest { it.unresolved },
            deadlinePartial = bestDeadline.toMetricBest { it.deadlinePartial },
            fieldBests = fieldBests,
        )
    }

    private fun writeEntries(
        entries: List<Entry>,
        mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
    ) {
        logFile.parentFile?.mkdirs()
        val bestRow = computeBestBuild(entries, mode)?.toCsvMap(zoneId, mode)
        val rows = ArrayList<Map<String, String>>(entries.size + (if (bestRow != null) 1 else 0))
        entries.mapTo(rows) { it.toCsvMap() }
        if (bestRow != null) rows.add(bestRow)

        val temp = File(logFile.parentFile, logFile.name + ".tmp")
        temp.bufferedWriter(Charsets.UTF_8).use { writer ->
            TextLabCsv.write(writer, rows.asSequence())
        }
        try {
            Files.move(temp.toPath(), logFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_NAME = "benchmark-results-log.csv"
        const val BEST_BUILD_ROW_TITLE = "Lab v4 Best build"
        const val RUN_ROW_TYPE = "run"
        const val BEST_BUILD_ROW_TYPE = "best_build"
        const val EXPECTED_TABLE_COLUMNS = 14

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

        fun emptyBestBuildRow(columnCount: Int = EXPECTED_TABLE_COLUMNS): List<String> =
            listOf(BEST_BUILD_ROW_TITLE) + List((columnCount - 1).coerceAtLeast(0)) { "—" }

        /**
         * Summarizes per-field correct / incorrect counts from scored CSV rows (`row_type == "field"`),
         * excluding `unresolved` reference fields so counts match `score.field.*` and `isFieldFailure`.
         */
        fun summarizeFieldRows(csvRows: List<Map<String, String>>): Map<String, FieldScore> {
            val fieldRows = csvRows.filter { it["row_type"] == "field" }
            if (fieldRows.isEmpty()) return emptyMap()
            val byField = fieldRows.groupBy { it["field"].orEmpty() }
            return PhoneCorpusMath.fields.associateWith { field ->
                val rows = byField[field].orEmpty()
                var correct = 0
                var incorrect = 0
                for (row in rows) {
                    if (row["reference_status"] == "unresolved") continue
                    if (PhoneCorpusMath.correct(row["outcome"].orEmpty())) correct++ else incorrect++
                }
                FieldScore(correct = correct, incorrect = incorrect)
            }
        }

        fun currentBuildKey(): String {
            val commit = BuildConfig.BUILD_COMMIT.ifBlank { "unknown" }
            return if (BuildConfig.BUILD_DIRTY) "$commit:dirty:${BuildConfig.BUILD_TIME_MILLIS}" else "$commit:clean"
        }

        fun currentBuildInfo(): BuildInfo {
            val commit = BuildConfig.BUILD_COMMIT.ifBlank { "unknown" }
            val versionCode = BuildConfig.VERSION_CODE.toString()
            val suffix = if (BuildConfig.BUILD_DIRTY) "-dirty" else ""
            return BuildInfo(
                buildKey = currentBuildKey(),
                buildNumber = "#$versionCode ($commit$suffix)",
                versionCode = versionCode,
                buildCommit = commit,
                branch = BuildConfig.BUILD_BRANCH.ifBlank { "unknown" },
            )
        }

        fun buildInfoFromSavedRun(metadata: JSONObject?, predictions: List<JSONObject>): BuildInfo {
            val currentCommit = BuildConfig.BUILD_COMMIT.ifBlank { "unknown" }
            val runCommit = metadata?.optString("build").orEmpty()
            val hasCurrentPrediction = predictions.any { it.optString("build") == currentCommit }
            if (runCommit == currentCommit || hasCurrentPrediction) {
                val explicitKey = metadata?.optString("build_key").orEmpty()
                return if (explicitKey.isNotBlank() && runCommit == currentCommit && !hasCurrentPrediction) {
                    currentBuildInfo().copy(buildKey = explicitKey)
                } else {
                    currentBuildInfo()
                }
            }
            val commit = runCommit.ifBlank {
                predictions.firstNotNullOfOrNull { it.optString("build").takeIf(String::isNotBlank) } ?: "unknown"
            }
            val dirty = metadata?.optString("working_tree") == "dirty"
            val explicitKey = metadata?.optString("build_key").orEmpty()
                .ifBlank { if (dirty) "$commit:dirty:${metadata?.optLong("created_ms") ?: 0L}" else "$commit:clean" }
            val versionCode = metadata?.optString("version_code").orEmpty()
            val suffix = if (dirty) "-dirty" else ""
            val buildNumber = if (versionCode.isNotBlank()) "#$versionCode ($commit$suffix)" else commit + suffix
            val branch = metadata?.optString("branch").orEmpty().ifBlank { "unknown" }
            return BuildInfo(
                buildKey = explicitKey,
                buildNumber = buildNumber,
                versionCode = versionCode,
                buildCommit = commit,
                branch = branch,
            )
        }

        private fun fromCsvMap(map: Map<String, String>): Entry? {
            if (map["row_type"] == BEST_BUILD_ROW_TYPE) return null
            val runId = map["run_id"].orEmpty()
            val buildCommit = map["build_commit"].orEmpty()
            val buildKey = map["build_key"].orEmpty().ifBlank { if (buildCommit.isNotBlank()) "$buildCommit:clean" else "" }
            val attempted = map["attempted"]?.toIntOrNull() ?: return null
            if (buildKey.isBlank() || buildKey == BEST_BUILD_ROW_TYPE || attempted <= 0) return null
            return Entry(
                date = map["date"].orEmpty(),
                time = map["time"].orEmpty(),
                timestampMs = map["timestamp_ms"]?.toLongOrNull() ?: 0L,
                buildNumber = map["build_number"].orEmpty().ifBlank { buildCommit },
                versionCode = map["version_code"].orEmpty(),
                buildCommit = buildCommit,
                buildKey = buildKey,
                branch = map["branch"].orEmpty().ifBlank { "unknown" },
                modelVersion = map["model_version"].orEmpty().ifBlank { PhoneCorpusRunner.Mode.LAB.title },
                pipelineId = map["pipeline_id"].orEmpty().ifBlank { PhoneCorpusRunner.Mode.LAB.pipelineId },
                runId = runId.ifBlank { "$buildKey:${map["timestamp_ms"].orEmpty()}" },
                attempted = attempted,
                eligible = map["eligible"]?.toIntOrNull() ?: 0,
                complete = map["complete"]?.toIntOrNull() ?: 0,
                oneError = map["one_error"]?.toIntOrNull() ?: 0,
                twoPlusErrors = map["two_plus_errors"]?.toIntOrNull() ?: 0,
                failed = map["failed"]?.toIntOrNull() ?: 0,
                correctValues = map["correct_values"]?.toIntOrNull() ?: 0,
                wrong = map["wrong"]?.toIntOrNull() ?: 0,
                missing = map["missing"]?.toIntOrNull() ?: 0,
                correctBlanks = map["correct_blanks"]?.toIntOrNull() ?: 0,
                unsupported = map["unsupported"]?.toIntOrNull() ?: 0,
                unresolved = map["unresolved"]?.toIntOrNull() ?: 0,
                deadlinePartial = map["deadline_partial"]?.toIntOrNull() ?: 0,
                fieldScores = buildMap {
                    for (field in PhoneCorpusMath.fields) {
                        val correct = map["${field}_correct"]?.toIntOrNull()
                        val incorrect = map["${field}_incorrect"]?.toIntOrNull()
                        if (correct != null && incorrect != null) {
                            put(field, FieldScore(correct = correct, incorrect = incorrect))
                        }
                    }
                },
            )
        }
    }
}
