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
 * missing, correct blanks, unsupported, unresolved, deadline/partial) — never raw OCR text,
 * traces, tokens, or images — so the score table can populate the "Lab v4 Last build" row from
 * the immediately preceding build.
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
    ) {
        fun toTableRow(title: String = LAST_BUILD_ROW_TITLE): List<String> = listOf(
            title,
            attempted.toString(),
            eligible.toString(),
            "$complete/$eligible",
            oneError.toString(),
            twoPlusErrors.toString(),
            failed.toString(),
            correctValues.toString(),
            wrong.toString(),
            missing.toString(),
            correctBlanks.toString(),
            unsupported.toString(),
            unresolved.toString(),
            deadlinePartial.toString(),
        )

        fun toCsvMap(): Map<String, String> = linkedMapOf(
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
            "unsupported" to unsupported.toString(),
            "unresolved" to unresolved.toString(),
            "deadline_partial" to deadlinePartial.toString(),
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

    /**
     * Records or updates the scalar summary row for [runId]. No raw text, trace rows, or photos
     * are accepted or persisted.
     */
    @Synchronized
    fun recordSummary(
        runId: String,
        buildInfo: BuildInfo,
        mode: PhoneCorpusRunner.Mode,
        summaryRow: List<String>,
        timestampMs: Long = System.currentTimeMillis(),
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
        )

        val existing = readEntries().toMutableList()
        val idx = existing.indexOfFirst { it.runId == runId && it.modelVersion == mode.title }
        if (idx >= 0) {
            if (existing[idx].copy(date = newEntry.date, time = newEntry.time, timestampMs = newEntry.timestampMs) == newEntry) {
                return
            }
            existing[idx] = newEntry
        } else {
            existing.add(newEntry)
        }
        existing.sortBy { it.timestampMs }
        writeEntries(existing)
    }

    /**
     * Returns the Entry for the immediately preceding distinct build (one build old) for [mode],
     * or null when no prior build has been logged yet.
     */
    @Synchronized
    fun previousBuildEntry(
        currentBuildKey: String = currentBuildKey(),
        mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
    ): Entry? {
        val candidates = readEntries().filter {
            it.modelVersion == mode.title && it.buildKey.isNotBlank() && it.buildKey != currentBuildKey && it.attempted > 0
        }
        if (candidates.isEmpty()) return null
        val previousBuildKey = candidates.maxByOrNull { it.timestampMs }?.buildKey ?: return null
        return candidates
            .filter { it.buildKey == previousBuildKey }
            .maxWithOrNull(compareBy<Entry> { it.attempted }.thenBy { it.timestampMs })
    }

    fun previousBuildRow(
        currentBuildKey: String = currentBuildKey(),
        mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
        columnCount: Int = EXPECTED_TABLE_COLUMNS,
    ): List<String> =
        previousBuildEntry(currentBuildKey, mode)?.toTableRow(LAST_BUILD_ROW_TITLE)
            ?: emptyLastBuildRow(columnCount)

    private fun writeEntries(entries: List<Entry>) {
        logFile.parentFile?.mkdirs()
        val temp = File(logFile.parentFile, logFile.name + ".tmp")
        temp.bufferedWriter(Charsets.UTF_8).use { writer ->
            TextLabCsv.write(writer, entries.asSequence().map { it.toCsvMap() })
        }
        try {
            Files.move(temp.toPath(), logFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_NAME = "benchmark-results-log.csv"
        const val LAST_BUILD_ROW_TITLE = "Lab v4 Last build"
        const val EXPECTED_TABLE_COLUMNS = 14

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

        fun emptyLastBuildRow(columnCount: Int = EXPECTED_TABLE_COLUMNS): List<String> =
            listOf(LAST_BUILD_ROW_TITLE) + List((columnCount - 1).coerceAtLeast(0)) { "—" }

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
            val runId = map["run_id"].orEmpty()
            val buildCommit = map["build_commit"].orEmpty()
            val buildKey = map["build_key"].orEmpty().ifBlank { if (buildCommit.isNotBlank()) "$buildCommit:clean" else "" }
            val attempted = map["attempted"]?.toIntOrNull() ?: return null
            if (buildKey.isBlank() || attempted <= 0) return null
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
            )
        }
    }
}
