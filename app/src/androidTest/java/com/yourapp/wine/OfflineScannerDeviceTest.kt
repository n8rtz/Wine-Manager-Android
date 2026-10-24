package com.yourapp.wine

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.geom.Matrix3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Must run on an arm64 device/emulator: release/debug APKs intentionally do not contain x86 JNI. */
@RunWith(AndroidJUnit4::class)
class OfflineScannerDeviceTest {
    @Test fun realBundledMlKitReadsThreeCropsAndMapsOriginalBoxes() = runBlocking {
        withContext(Dispatchers.Default) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val scanner = WineScannerServices.get(context)
            for (name in listOf("margaux", "barolo", "napa")) {
                val file = File.createTempFile("wine-fixture-", ".png", context.cacheDir)
                try {
                    assets.open("labels/$name.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                    val expected = JSONObject(assets.open("labels/$name.json").bufferedReader().use { it.readText() }).getJSONObject("expected")
                    val report = scanner.scan(file, SystemClock.elapsedRealtime())
                    assertFalse("$name: ${report.fill.overallHint}", report.tokens.isEmpty())
                    for (field in FieldId.entries) {
                        val c = report.fill.fields.getValue(field)
                        assertEquals("$name $field: ${c.reason}", expected.getJSONObject(field.name).getString("value"), c.value)
                        assertEquals("$name $field: ${c.reason}", UiState.GREEN, c.state)
                    }
                    assertNotNull("$name should rectify the printed label quad", report.homography)
                    assertTrue(report.rectW <= 1280 && maxOf(report.rectW, report.rectH) <= 1920)
                    assertTrue(report.timings.filter { it.variantId != "probe" }.map { it.variantId }.distinct().size <= 3)
                    val h = Matrix3(report.homography!!)
                    report.tokens.forEach { token ->
                        assertEquals(4, token.polyOrig.size)
                        val rect = token.polyOrig.map(h::map)
                        assertEquals(token.bboxRect.left, (rect.minOf { it.x } / report.rectW).coerceIn(0f, 1f), .003f)
                        assertEquals(token.bboxRect.top, (rect.minOf { it.y } / report.rectH).coerceIn(0f, 1f), .003f)
                        assertEquals(token.bboxRect.right, (rect.maxOf { it.x } / report.rectW).coerceIn(0f, 1f), .003f)
                        assertEquals(token.bboxRect.bottom, (rect.maxOf { it.y } / report.rectH).coerceIn(0f, 1f), .003f)
                    }
                    assertTrue(report.tokens.all { t -> listOf(t.bboxOrig.left, t.bboxOrig.top, t.bboxOrig.right, t.bboxOrig.bottom,
                        t.bboxRect.left, t.bboxRect.top, t.bboxRect.right, t.bboxRect.bottom).all { it in 0f..1f } })
                    assertTrue(report.peakWorkingBitmaps <= 2)
                    assertEquals(0, report.workingBitmapsAfter)
                    assertTrue("$name took ${report.elapsedMs}ms", report.elapsedMs <= 8000)
                } finally { file.delete() }
            }
        }
    }

    @Test fun twentyFileScansReleaseBitmapsAndDoNotGrowHeap() = runBlocking {
        withContext(Dispatchers.Default) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val scanner = WineScannerServices.get(context)
            val file = File.createTempFile("wine-memory-", ".png", context.cacheDir)
            try {
                assets.open("labels/margaux.png").use { input -> file.outputStream().use { input.copyTo(it) } }
                repeat(2) { scanner.scan(file, SystemClock.elapsedRealtime()) } // model/native warmup is outside the growth baseline
                fun heap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() + Debug.getNativeHeapAllocatedSize()
                System.gc()
                val baseline = heap()
                var maxHeap = baseline
                repeat(20) { index ->
                    val report = scanner.scan(file, SystemClock.elapsedRealtime())
                    assertTrue(report.peakWorkingBitmaps <= 2)
                    assertEquals(0, report.workingBitmapsAfter)
                    assertFalse(report.tokens.isEmpty())
                    System.gc()
                    val measured = heap(); maxHeap = maxOf(maxHeap, measured)
                    Log.i("WineScannerMemory", "capture=${index + 1} heap=$measured baseline=$baseline peakBitmaps=${report.peakWorkingBitmaps} alive=${report.workingBitmapsAfter} ms=${report.elapsedMs}")
                }
                System.gc()
                val growth = heap() - baseline
                Log.i("WineScannerMemory", "20 captures growth=$growth maxHeap=$maxHeap")
                assertTrue("Managed/native heap grew by $growth bytes", growth < 12L * 1024 * 1024)
            } finally { file.delete() }
        }
    }
}
