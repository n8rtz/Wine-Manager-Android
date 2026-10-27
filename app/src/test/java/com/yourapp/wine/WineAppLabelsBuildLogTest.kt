package com.yourapp.wine

import com.yourapp.wine.phonecorpus.PhoneCorpusRunner
import com.yourapp.wine.phonecorpus.WineAppLabelsBuildLog
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
    fun logsScalarBuildSummariesAndSelectsImmediatelyPrecedingBuild() {
        val logFile = temp.newFile("benchmark-results-log.csv").apply { delete() }
        val log = WineAppLabelsBuildLog(logFile, ZoneOffset.UTC)

        // Initially empty -> placeholder row with em-dashes.
        assertNull(log.previousBuildEntry("build-c:clean"))
        val emptyRow = log.previousBuildRow("build-c:clean")
        assertEquals(WineAppLabelsBuildLog.LAST_BUILD_ROW_TITLE, emptyRow.first())
        assertEquals(14, emptyRow.size)
        assertTrue(emptyRow.drop(1).all { it == "—" })

        val buildA = WineAppLabelsBuildLog.BuildInfo("build-a:clean", "#55 (aaaaaaaaaaaa)", "55", "aaaaaaaaaaaa", "main")
        val buildB = WineAppLabelsBuildLog.BuildInfo("build-b:clean", "#56 (bbbbbbbbbbbb)", "56", "bbbbbbbbbbbb", "feature/lab")
        val buildC = WineAppLabelsBuildLog.BuildInfo("build-c:clean", "#57 (cccccccccccc)", "57", "cccccccccccc", "feature/lab")

        log.recordSummary(
            runId = "run-a-1",
            buildInfo = buildA,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "125", "123", "28/123", "44", "51", "0", "505", "92", "80", "54", "16", "2", "0"),
            timestampMs = 1_700_000_000_000L,
        )
        log.recordSummary(
            runId = "run-b-full",
            buildInfo = buildB,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "125", "123", "31/123", "45", "47", "0", "521", "86", "70", "54", "18", "2", "0"),
            timestampMs = 1_700_010_000_000L,
        )
        // Updating the same runId updates in place rather than duplicating rows; starting a new
        // live run on build C keeps build B as "one build old".
        log.recordSummary(
            runId = "run-c-live",
            buildInfo = buildC,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "40", "40", "14/40", "16", "10", "0", "178", "22", "18", "18", "4", "0", "0"),
            timestampMs = 1_700_020_000_000L,
        )
        log.recordSummary(
            runId = "run-c-live",
            buildInfo = buildC,
            mode = PhoneCorpusRunner.Mode.LAB,
            summaryRow = listOf("Lab v4", "125", "123", "36/123", "48", "39", "0", "540", "72", "65", "54", "14", "2", "0"),
            timestampMs = 1_700_025_000_000L,
        )

        val entries = log.readEntries()
        assertEquals(3, entries.size)

        // While running on build C, "Lab v4 Last build" resolves to build B (one build old), NOT build A or C.
        val prevForC = log.previousBuildEntry("build-c:clean")
        assertNotNull(prevForC)
        assertEquals("bbbbbbbbbbbb", prevForC!!.buildCommit)
        assertEquals("feature/lab", prevForC.branch)
        assertEquals(
            listOf("Lab v4 Last build", "125", "123", "31/123", "45", "47", "0", "521", "86", "70", "54", "18", "2", "0"),
            log.previousBuildRow("build-c:clean"),
        )

        // Once upgraded to a later build D, "Lab v4 Last build" advances to build C's final 36/123 score.
        assertEquals(
            listOf("Lab v4 Last build", "125", "123", "36/123", "48", "39", "0", "540", "72", "65", "54", "14", "2", "0"),
            log.previousBuildRow("build-d:clean"),
        )

        // Verify the CSV file contains only metadata + scalar numbers (no raw text/trace/image keys).
        val rawCsv = logFile.readText(Charsets.UTF_8)
        for (required in listOf("date", "time", "build_number", "branch", "model_version", "attempted", "complete", "one_error", "two_plus_errors")) {
            assertTrue(rawCsv.contains(required))
        }
        for (forbidden in listOf("raw_text", "tokens", "trace", "image_sha256")) {
            assertFalse(rawCsv.contains(forbidden))
        }
    }
}
