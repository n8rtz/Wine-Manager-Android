package com.yourapp.wine

import android.graphics.Bitmap
import com.yourapp.wine.ocr.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DeadlineTest {
    @Test fun optionalEnginesOnlyReadRectifiedLabels() = EnsemblePolicyChecks.optionalEnginesNeverReadAnUnrectifiedScene()

    @Test fun sleepingEngineIsCancelledAndCompletedEvidenceIsKept() = runBlocking {
        var wasCancelled = false
        val fast = object : OcrEngine {
            override val id = "mlkit"
            override val available = true
            override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline) = listOf(
                RawToken("2018", .9f, listOf(android.graphics.PointF(0f, 0f), android.graphics.PointF(1f, 1f)), id, variantId))
        }
        val slow = object : OcrEngine {
            override val id = "paddle"
            override val available = true
            override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken> {
                try { delay(10_000); return emptyList() } finally { wasCancelled = true }
            }
        }
        val now = { System.nanoTime() / 1_000_000 }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var released = false
        var published = 0
        try {
            val batch = OcrEnsemble(listOf(fast, slow), {}).run(listOf(OcrVariant("V0", { bitmap }, { released = true })),
                ScanDeadline(now() + 150, now), onTokens = { published += it.size })
            assertEquals(1, batch.tokens.size)
            assertEquals(1, published)
            assertTrue(batch.deadlineCancelled)
            assertTrue(wasCancelled)
            assertTrue(released)
            assertTrue(batch.timings.last().deadlineCancelled)
        } finally { bitmap.recycle() }
    }

    @Test fun expiredDeadlineStartsNoEngine() = runBlocking {
        val engine = object : OcrEngine {
            override val id = "fake"
            override val available = true
            override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken> = error("Must not start")
        }
        val batch = OcrEnsemble(listOf(engine), {}).run(listOf(OcrVariant("V0", { error("No bitmap allocation") }, {})),
            ScanDeadline(1) { 2 })
        assertTrue(batch.deadlineCancelled)
        assertTrue(batch.tokens.isEmpty())
    }
}
