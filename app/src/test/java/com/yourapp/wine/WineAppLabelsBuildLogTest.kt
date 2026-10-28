package com.yourapp.wine

import com.yourapp.wine.phonecorpus.PhoneCorpusRunner
import com.yourapp.wine.phonecorpus.WineAppLabelsBuildLog
import com.yourapp.wine.textlab.TextLabCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

class WineAppLabelsBuildLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun composesBestScoresAcrossDistinctBuildsAndLogsSourceBuildNumbers() {
        val logFile = temp.newFile("benchmark-results-log.csv").apply { delete() }
        val log = WineAppLabelsBuildLog(logFile, ZoneOffset.UTC)

        // Initially empty -> placeholder row with em-dashes.
        assertNull(log.bestBuildSummary())
        val emptyRow = log.bestBuildRow()
        assertEquals(WineAppLabelsBuildLog.BEST_BUILD_ROW_TITLE, emptyRow.first())
        assertEquals(14, emptyRow.size)
        assertTrue(emptyRow.drop(1).all { it == "—" })

        val buildA = WineAppLabelsBuildLog.BuildInfo("build-a:clean", "#55 (aaaaaaaaaaaa)", "55", "aaaaaaaaaaaa", "main")
        val buildB = WineAppLabelsBuildLog.BuildInfo("build-b:clean", "#56 (bbbbbbbbbbbb)", "56", "bbbbbbbbbbbb", "feature/lab")
        val buildC = WineAppLabelsBuildLog.BuildInfo("build-c:clean", "#57 (cccccccccccc)", "57", "cccccccccccc", "feature/lab")

        // Build A: highest Producer (94/31) and Vintage (121/3), lower Total correct (605) & Complete (41/123).
        log.recordSummary(
            runId = "run-a-full",
            buildInfo = buildA,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "125", "123", "41/123", "45", "37", "0", "550", "88", "55", "55", "16", "2", "0"),
            timestampMs = 1_700_000_000_000L,
            fieldScores = mapOf(
                "producer" to WineAppLabelsBuildLog.FieldScore(94, 31),
                "wine_name" to WineAppLabelsBuildLog.FieldScore(68, 56),
                "country" to WineAppLabelsBuildLog.FieldScore(121, 4),
                "region" to WineAppLabelsBuildLog.FieldScore(114, 11),
                "subregion" to WineAppLabelsBuildLog.FieldScore(107, 18),
                "vintage" to WineAppLabelsBuildLog.FieldScore(121, 3),
            ),
        )

        // Build B: highest Complete (46/123), highest Total correct (566+59=625), highest Region (118/7) & Subregion (115/10),
        // but regressed on Producer (90/35) vs Build A.
        log.recordSummary(
            runId = "run-b-full",
            buildInfo = buildB,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "125", "123", "46/123", "46", "31", "0", "566", "74", "49", "59", "14", "2", "0"),
            timestampMs = 1_700_010_000_000L,
            fieldScores = mapOf(
                "producer" to WineAppLabelsBuildLog.FieldScore(90, 35),
                "wine_name" to WineAppLabelsBuildLog.FieldScore(73, 51),
                "country" to WineAppLabelsBuildLog.FieldScore(123, 2),
                "region" to WineAppLabelsBuildLog.FieldScore(118, 7),
                "subregion" to WineAppLabelsBuildLog.FieldScore(115, 10),
                "vintage" to WineAppLabelsBuildLog.FieldScore(120, 4),
            ),
        )

        // Build C: partial in-progress pass (40/125). Its partial error counts (wrong=15, twoPlusErrors=8)
        // must NOT overwrite full-corpus minimums from Build B.
        log.recordSummary(
            runId = "run-c-partial",
            buildInfo = buildC,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "40", "40", "16/40", "16", "8", "0", "180", "15", "12", "18", "3", "0", "0"),
            timestampMs = 1_700_020_000_000L,
            fieldScores = mapOf(
                "producer" to WineAppLabelsBuildLog.FieldScore(30, 10),
                "wine_name" to WineAppLabelsBuildLog.FieldScore(24, 16),
                "country" to WineAppLabelsBuildLog.FieldScore(39, 1),
                "region" to WineAppLabelsBuildLog.FieldScore(37, 3),
                "subregion" to WineAppLabelsBuildLog.FieldScore(35, 5),
                "vintage" to WineAppLabelsBuildLog.FieldScore(38, 2),
            ),
        )

        // readEntries() returns only the 3 real run rows, never double-counting the synthetic best_build row.
        val entries = log.readEntries()
        assertEquals(3, entries.size)

        val best = log.bestBuildSummary()
        assertNotNull(best)
        assertEquals(
            listOf("Lab v5 Best build", "125", "123", "46/123", "46", "31", "0", "566", "74", "49", "59", "14", "2", "0"),
            best!!.toTableRow(),
        )
        // Producer & Vintage come from Build A; Complete, Total correct, Wine name, Country, Region, Subregion come from Build B.
        assertEquals(625, best.totalCorrect.value)
        assertEquals("#56 (bbbbbbbbbbbb)", best.totalCorrect.buildNumber)
        assertEquals("#56 (bbbbbbbbbbbb)", best.complete.buildNumber)
        assertEquals(WineAppLabelsBuildLog.FieldScore(94, 31), best.fieldScores["producer"])
        assertEquals("#55 (aaaaaaaaaaaa)", best.fieldBests["producer"]?.buildNumber)
        assertEquals(WineAppLabelsBuildLog.FieldScore(73, 51), best.fieldScores["wine_name"])
        assertEquals("#56 (bbbbbbbbbbbb)", best.fieldBests["wine_name"]?.buildNumber)
        assertEquals(WineAppLabelsBuildLog.FieldScore(118, 7), best.fieldScores["region"])
        assertEquals("#56 (bbbbbbbbbbbb)", best.fieldBests["region"]?.buildNumber)
        assertEquals(WineAppLabelsBuildLog.FieldScore(121, 3), best.fieldScores["vintage"])
        assertEquals("#55 (aaaaaaaaaaaa)", best.fieldBests["vintage"]?.buildNumber)

        // Verify the persisted CSV logs the composite best_build row along with per-parameter build numbers,
        // and still excludes raw OCR / image payloads.
        val parsedCsvRows = mutableListOf<Map<String, String>>()
        var headers: List<String>? = null
        logFile.bufferedReader(Charsets.UTF_8).use { reader ->
            TextLabCsv.read(reader) { record ->
                if (headers == null) headers = record
                else parsedCsvRows += headers.orEmpty().mapIndexed { idx, col -> col to record.getOrElse(idx) { "" } }.toMap()
            }
        }
        assertEquals(4, parsedCsvRows.size)
        val bestCsvRow = parsedCsvRows.single { it["row_type"] == WineAppLabelsBuildLog.BEST_BUILD_ROW_TYPE }
        assertEquals("Lab v5 Best build", bestCsvRow["model_version"])
        assertEquals("46", bestCsvRow["complete"])
        assertEquals("625", bestCsvRow["total_correct"])
        assertEquals("94", bestCsvRow["producer_correct"])
        assertEquals("31", bestCsvRow["producer_incorrect"])
        assertEquals("#56 (bbbbbbbbbbbb)", bestCsvRow["best_complete_build"])
        assertEquals("#56 (bbbbbbbbbbbb)", bestCsvRow["best_total_correct_build"])
        assertEquals("#55 (aaaaaaaaaaaa)", bestCsvRow["best_producer_build"])
        assertEquals("#56 (bbbbbbbbbbbb)", bestCsvRow["best_wine_name_build"])
        assertEquals("#55 (aaaaaaaaaaaa)", bestCsvRow["best_vintage_build"])

        val rawCsv = logFile.readText(Charsets.UTF_8)
        for (forbidden in listOf("raw_text", "tokens", "trace", "image_sha256")) {
            assertFalse(rawCsv.contains(forbidden))
        }
    }

    @Test
    fun summarizesAndPersistsPerFieldCorrectIncorrectScores() {
        val logFile = temp.newFile("benchmark-field-scores-log.csv").apply { delete() }
        val log = WineAppLabelsBuildLog(logFile, ZoneOffset.UTC)

        val csvRows = listOf(
            mapOf("row_type" to "summary", "Pipeline" to "Lab v4"),
            mapOf("row_type" to "field", "field" to "producer", "reference_status" to "expected_value", "outcome" to "correct_value"),
            mapOf("row_type" to "field", "field" to "producer", "reference_status" to "expected_value", "outcome" to "wrong_value"),
            mapOf("row_type" to "field", "field" to "wine_name", "reference_status" to "unresolved", "outcome" to "unscored_blank"),
            mapOf("row_type" to "field", "field" to "wine_name", "reference_status" to "expected_value", "outcome" to "missing_value"),
            mapOf("row_type" to "field", "field" to "country", "reference_status" to "expected_value", "outcome" to "correct_value"),
            mapOf("row_type" to "field", "field" to "country", "reference_status" to "expected_blank", "outcome" to "correct_blank"),
            mapOf("row_type" to "field", "field" to "region", "reference_status" to "expected_value", "outcome" to "correct_value"),
            mapOf("row_type" to "field", "field" to "region", "reference_status" to "expected_blank", "outcome" to "unsupported_fill"),
            mapOf("row_type" to "field", "field" to "subregion", "reference_status" to "expected_blank", "outcome" to "correct_blank"),
            mapOf("row_type" to "field", "field" to "subregion", "reference_status" to "expected_value", "outcome" to "execution_failed"),
            mapOf("row_type" to "field", "field" to "vintage", "reference_status" to "expected_value", "outcome" to "correct_value"),
            mapOf("row_type" to "field", "field" to "vintage", "reference_status" to "unresolved", "outcome" to "unscored_filled"),
        )
        val fieldScores = WineAppLabelsBuildLog.summarizeFieldRows(csvRows)
        assertEquals(WineAppLabelsBuildLog.FieldScore(1, 1), fieldScores["producer"])
        // Unresolved reference rows are excluded from both correct and incorrect counts.
        assertEquals(WineAppLabelsBuildLog.FieldScore(0, 1), fieldScores["wine_name"])
        assertEquals(WineAppLabelsBuildLog.FieldScore(2, 0), fieldScores["country"])
        assertEquals(WineAppLabelsBuildLog.FieldScore(1, 1), fieldScores["region"])
        assertEquals(WineAppLabelsBuildLog.FieldScore(1, 1), fieldScores["subregion"])
        assertEquals(WineAppLabelsBuildLog.FieldScore(1, 0), fieldScores["vintage"])

        val buildInfo = WineAppLabelsBuildLog.BuildInfo(
            buildKey = "prev:clean",
            buildNumber = "#57 (prev)",
            versionCode = "57",
            buildCommit = "prev",
            branch = "main",
        )
        log.recordSummary(
            runId = "run-prev",
            buildInfo = buildInfo,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "2", "1", "0/1", "1", "0", "0", "4", "1", "1", "2", "1", "2", "0"),
            timestampMs = 1_700_100_000_000L,
            fieldScores = fieldScores,
        )

        val restoredBest = log.bestBuildSummary()
        assertNotNull(restoredBest)
        assertEquals("1/1", restoredBest!!.fieldScores["producer"]?.label())
        assertEquals("0/1", restoredBest.fieldScores["wine_name"]?.label())
        assertEquals("2/0", restoredBest.fieldScores["country"]?.label())
        assertEquals("1/1", restoredBest.fieldScores["region"]?.label())
        assertEquals("1/1", restoredBest.fieldScores["subregion"]?.label())
        assertEquals("1/0", restoredBest.fieldScores["vintage"]?.label())
        assertEquals("#57 (prev)", restoredBest.fieldBests["producer"]?.buildNumber)
    }

    @Test
    fun isolatesAndLogsPostOcrModuleCombinationsPerRun() {
        val logFile = temp.newFile("benchmark-modules-log.csv").apply { delete() }
        val log = WineAppLabelsBuildLog(logFile, ZoneOffset.UTC)

        val baselineMeta = org.json.JSONObject()
            .put("build", "dddddddddddd")
            .put("version_code", "61")
            .put("branch", "arena/test")
            .put("lab_modules_key", "best-v5")
        val subregionMeta = org.json.JSONObject()
            .put("build", "dddddddddddd")
            .put("version_code", "61")
            .put("branch", "arena/test")
            .put("lab_modules_key", "subregion-fuzzy")

        val baselineInfo = WineAppLabelsBuildLog.buildInfoFromSavedRun(baselineMeta, emptyList())
        val subregionInfo = WineAppLabelsBuildLog.buildInfoFromSavedRun(subregionMeta, emptyList())
        assertEquals("best-v5", baselineInfo.labModules)
        assertEquals("subregion-fuzzy", subregionInfo.labModules)
        assertTrue(baselineInfo.buildNumber.endsWith(" [best-v5]"))
        assertTrue(subregionInfo.buildNumber.endsWith(" [subregion-fuzzy]"))
        assertFalse(baselineInfo.buildKey == subregionInfo.buildKey)

        log.recordSummary(
            runId = "run-best-v5",
            buildInfo = baselineInfo,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v5", "124", "124", "45/124", "47", "32", "0", "562", "72", "50", "59", "14", "0", "0"),
            timestampMs = 1_700_200_000_000L,
        )
        log.recordSummary(
            runId = "run-subregion-fuzzy",
            buildInfo = subregionInfo,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v5", "124", "124", "48/124", "45", "31", "0", "565", "72", "47", "59", "14", "0", "0"),
            timestampMs = 1_700_210_000_000L,
        )

        val entries = log.readEntries()
        assertEquals(2, entries.size)
        assertEquals("best-v5", entries[0].labModules)
        assertEquals("subregion-fuzzy", entries[1].labModules)
        val best = log.bestBuildSummary()
        assertNotNull(best)
        assertEquals(48 to 124, best!!.complete.value)
        assertTrue(best.complete.buildNumber.endsWith(" [subregion-fuzzy]"))
    }

    @Test
    fun bestBuildHighWaterMarkNeverMixesTwoCorpora() {
        val logFile = temp.newFile("corpus-log.csv").apply { delete() }
        val log = WineAppLabelsBuildLog(logFile, ZoneOffset.UTC)
        val oldBuild = WineAppLabelsBuildLog.BuildInfo("old:clean", "#55 (aaaaaaaaaaaa)", "55", "aaaaaaaaaaaa", "main")
        val newBuild = WineAppLabelsBuildLog.BuildInfo("new:clean", "#70 (bbbbbbbbbbbb)", "70", "bbbbbbbbbbbb", "arena/lab")

        // The 125-label corpus (123 eligible) holds the higher headline numbers.
        log.recordSummary(
            runId = "run-125",
            buildInfo = oldBuild,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v5", "125", "123", "52/123", "54", "21", "0", "588", "70", "40", "60", "3", "2", "0"),
            timestampMs = 1_700_000_000_000L,
            corpusKey = "corpus-old",
        )
        // Two runs on the 124-label corpus (122 eligible) - the newest one is only 40 photos in.
        log.recordSummary(
            runId = "run-124-a",
            buildInfo = newBuild,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v5", "124", "122", "48/122", "58", "16", "0", "586", "68", "26", "59", "3", "2", "0"),
            timestampMs = 1_700_100_000_000L,
            corpusKey = "corpus-new",
        )
        log.recordSummary(
            runId = "run-124-b-partial",
            buildInfo = newBuild,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v5", "40", "40", "16/40", "16", "8", "0", "180", "15", "12", "18", "3", "0", "0"),
            timestampMs = 1_700_200_000_000L,
            corpusKey = "corpus-new",
        )

        val best = log.bestBuildSummary()
        assertNotNull(best)
        // Every value comes from the current corpus, never from the 125-label run above it.
        assertEquals(124, best!!.attempted.value)
        assertEquals(122, best.eligible.value)
        assertEquals(48 to 122, best.complete.value)
        assertEquals(586, best.correctValues.value)
        assertEquals(58, best.oneError.value)
        assertEquals("corpus-new", best.corpusKey)
        assertEquals("run-124-a", best.complete.runId)
        // The partial 40-photo row shares the corpus but must still not claim the error minimums.
        assertEquals(16, best.twoPlusErrors.value)
        assertEquals("run-124-a", best.twoPlusErrors.runId)
        // The persisted composite row carries the corpus it was measured on.
        val persisted = logFile.readLines().first { BEST_BUILD_MARKER in it }
        assertTrue(persisted.contains("corpus-new"))
    }

    private companion object {
        const val BEST_BUILD_MARKER = "best_build"
    }
}
