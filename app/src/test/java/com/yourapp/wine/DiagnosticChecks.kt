package com.yourapp.wine

import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.isBusy
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.capture.copyLabelBytes
import com.yourapp.wine.capture.LabelPhotoTooLargeException
import com.yourapp.wine.diagnostics.*
import com.yourapp.wine.fill.ConfidenceContext
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.ocr.OcrInputKind
import com.yourapp.wine.ocr.RawToken
import com.yourapp.wine.ocr.OcrEngine
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** Synthetic report/stream contracts only, not pixel OCR or a real system picker. Shared by host and Robolectric. */
object DiagnosticChecks {
    private val environment = ScanEnvironment(appVersion = "test", buildCommit = "abc123def456", buildBranch = "test-branch",
        workingTree = "clean", packagedAbis = "arm64-v8a", androidSdk = 36, model = "test emulator",
        hardware = "ranchu", deviceAbis = listOf("x86_64"))

    private fun report(): ScanReport {
        val readings = listOf(LogicChecks.token("EXAMPLE WINERY", .45f), LogicChecks.token("CALIFORNIA", .84f, y = .6f))
        return ScanReport.red("").copy(tokens = readings, origW = 840, origH = 1120, rectW = 640, rectH = 960,
            fill = FillEngine(FixtureKnowledgeBase()).fill(readings, 2026, ConfidenceContext(labelFound = false)),
            diagnostics = ScanDiagnostics(source = ScanInputSource.PHOTO_LIBRARY, workW = 840, workH = 1120,
                inputBytes = 24000, importMs = 12, nativeReady = true, nativeVersion = "4.12.0",
                quality = ScanQualityStats("GREEN", 123.4, 110.5), quadSource = "full_frame_fallback",
                totalReadings = 2, mappingReceived = 2, engines = listOf(ScanEngineStatus("mlkit", true),
                    ScanEngineStatus("paddle", false, "Optional assets missing"))))
    }

    fun reportExplainsPartialEvidenceAndBuild() {
        val text = ScanDiagnosticFormatter.format(report(), environment, 42)
        check(text.startsWith("WINE-SCAN-DIAGNOSTICS v1"))
        for (value in listOf("input=PHOTO_LIBRARY", "outcome=no_supported_identity", "commit=abc123def456",
                "device_abis=x86_64 packaged_abis=arm64-v8a", "native_ready=true", "opencv=4.12.0",
                "quality=GREEN", "laplacian_variance=123.400", "mapped_readings=2 retained_clusters=1",
                "PRODUCER state=RED value=\"\"", "score=0.450", "text=\"EXAMPLE WINERY\"", "CALIFORNIA")) {
            check(value in text) { "Missing diagnostic: $value" }
        }
    }

    fun traceHasBoundedSamplesAndNoExceptionMessages() {
        var now = 0L
        val trace = ScanTraceRecorder { now }
        now = 12; trace.enter(ScanStage.NATIVE_LOAD)
        now = 20; trace.enter(ScanStage.OCR)
        val raw = RawToken("line", .8f, emptyList(), "mlkit", "probe")
        repeat(100) { trace.reading(raw, OcrInputKind.WORKING_LOCALIZATION) }
        repeat(200) { trace.reading(raw.copy(variantId = "V0"), OcrInputKind.RECTIFIED_LABEL, it % 2 == 0) }
        trace.failed(IllegalStateException("SECRET content://private/photo /data/user/private.jpg"))
        val frozen = trace.finish(ScanDiagnostics())
        check(frozen.reads.size == 110 && frozen.totalReadings == 300)
        check(frozen.mappingReceived == 200 && frozen.mappingRejected == 100)
        check(frozen.stages.first().ms == 12L && frozen.stages[1].ms == 8L)
        check(frozen.failures.single().type == "java.lang.IllegalStateException")
        trace.reading(raw, OcrInputKind.RECTIFIED_LABEL, true)
        check(frozen.totalReadings == 300)
        val text = ScanDiagnosticFormatter.format(ScanReport.red("Failure").copy(diagnostics = frozen), environment, 1)
        check("SECRET" !in text && "content://private" !in text && "/data/user" !in text)
        check("java.lang.IllegalStateException" in text)
        val engine = object : OcrEngine {
            override val id = "mlkit"
            override val available = true
            override suspend fun recognize(bitmap: android.graphics.Bitmap, variantId: String, deadline: ScanDeadline) = emptyList<RawToken>()
        }
        val detail = engine.failureDetail(IllegalStateException("SECRET /private/photo", java.io.IOException("SECRET")))
        check("IllegalStateException" in detail && "IOException" in detail && "SECRET" !in detail && "/private" !in detail)
    }

    fun reportsAreBoundedAndLocaleIndependent() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val readings = (0 until 200).map { LogicChecks.token("line-$it-" + "x".repeat(300), .84f) }
            val text = ScanDiagnosticFormatter.format(report().copy(tokens = readings), environment, 1)
            check(text.length <= ScanDiagnosticFormatter.MAX_CHARS)
            check("Report truncated" in text)
            check("score=0.840" in text)
            check("score=0,840" !in text)
            val injected = ScanDiagnosticFormatter.format(report().copy(tokens = listOf(LogicChecks.token("label\nINJECTED\u0000row"))), environment, 2)
            check("\nINJECTED" !in injected && '\u0000' !in injected)
        } finally { Locale.setDefault(oldLocale) }
    }

    fun inputStatesAndFailuresRetainProvenance() {
        check(LabelScanState.ImportingPhoto(7, 100).isBusy)
        check(LabelScanState.Processing(7, "private-not-exported", 100, ScanInputSource.PHOTO_LIBRARY).isBusy)
        check(LabelScanState.FinishingCancelledScan(7).isBusy)
        check(!LabelScanState.Idle.isBusy)
        val camera = LabelScanState.CapturingPhoto(8, 100).rejected("Capture failed", 150, failureType = "CameraError").result
        check(camera.diagnostics.source == ScanInputSource.CAMERA && camera.diagnostics.lastStage == ScanStage.CAPTURE)
        val imported = ScanReport.red("Import failed", 8000, true, ScanInputSource.PHOTO_LIBRARY, ScanStage.IMPORT)
        check(imported.diagnostics.deadlineStage == ScanStage.IMPORT)
        val text = ScanDiagnosticFormatter.format(imported, environment, 7)
        check("input=PHOTO_LIBRARY" in text && "deadline_cancelled=true" in text)
    }

    fun photoBytesAreStreamedBoundedAndCancellable() {
        val data = ByteArray(100) { it.toByte() }
        val output = ByteArrayOutputStream()
        check(copyLabelBytes(ByteArrayInputStream(data), output) == 100L)
        check(output.toByteArray().contentEquals(data))
        val limited = ByteArrayOutputStream()
        try {
            copyLabelBytes(ByteArrayInputStream(data), limited, maxBytes = 32)
            error("Must reject oversized photo")
        } catch (_: LabelPhotoTooLargeException) { check(limited.size() <= 32) }
        var checks = 0
        try {
            copyLabelBytes(ByteArrayInputStream(ByteArray(100000)), ByteArrayOutputStream()) {
                if (++checks == 2) throw CancellationException("Test cancellation")
            }
            error("Must cancel streaming")
        } catch (_: CancellationException) { check(checks == 2) }
        var largestRequest = 0
        var remaining = 250000
        val source = object : InputStream() {
            override fun read(): Int = error("Must use chunked reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRequest = maxOf(largestRequest, length)
                if (remaining == 0) return -1
                val count = minOf(length, remaining); remaining -= count
                return count
            }
        }
        val sink = object : OutputStream() {
            override fun write(value: Int) = error("Must use chunked writes")
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        check(copyLabelBytes(source, sink) == 250000L && largestRequest <= 32768)
    }
}
