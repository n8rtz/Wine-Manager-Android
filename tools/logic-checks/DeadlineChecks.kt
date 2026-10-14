import android.graphics.Bitmap
import android.graphics.PointF
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.fill.ConfidenceContext
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.geom.CoordMapper
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.ocr.*
import kotlinx.coroutines.*

fun deadlineChecks() = runBlocking {
    var cancelled = false
    val fast = object : OcrEngine {
        override val id = "mlkit"
        override val available = true
        override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline) = listOf(
            RawToken("2018", .9f, listOf(PointF(0f, 0f), PointF(2f, 0f), PointF(2f, 2f), PointF(0f, 2f)), id, variantId))
    }
    val slow = object : OcrEngine {
        override val id = "paddle"
        override val available = true
        override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken> {
            try { delay(10_000); return emptyList() } finally { cancelled = true }
        }
    }
    val now = { System.nanoTime() / 1_000_000 }
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    var released = false
    var published = 0
    val batch = OcrEnsemble(listOf(fast, slow), {}).run(listOf(OcrVariant("V0", { bitmap }, { released = true })),
        ScanDeadline(now() + 150, now), onTokens = { published += it.size })
    check(batch.tokens.size == 1 && batch.deadlineCancelled && released && cancelled && published == 1)
    val mapped = batch.tokens.mapNotNull(CoordMapper(Matrix3.scale(1.0, 1.0), 2, 2, 2, 2)::toOriginal)
    val partial = FillEngine(FixtureKnowledgeBase()).fill(mapped, 2026, ConfidenceContext(deadlineReached = true))
    check(partial.fields.getValue(FieldId.VINTAGE).state == UiState.YELLOW)
    bitmap.recycle()
    println("PASS: sleeping-engine deadline / partial YELLOW / variant cleanup")

    val expired = OcrEnsemble(listOf(fast), {}).run(listOf(OcrVariant("V0", { error("Must not allocate") }, {})), ScanDeadline(1) { 2 })
    check(expired.tokens.isEmpty() && expired.deadlineCancelled)
    println("PASS: expired deadline starts no OCR or bitmap allocation")

    val gate = NativeEngineGate()
    val lease = gate.acquire()
    check(withTimeoutOrNull(20) { gate.acquire() } == null)
    lease.close(); lease.close()
    val next = withTimeout(100) { gate.acquire() }
    next.close()
    println("PASS: native semaphore / cancellable wait / idempotent release")
}

fun bitmapOwnershipChecks() {
    val workspace = BitmapWorkspace()
    val first = workspace.create(2, 2)
    val second = workspace.create(2, 2)
    check(runCatching { workspace.create(2, 2) }.isFailure)
    check(workspace.peakCount == 2 && workspace.aliveCount == 2)
    workspace.close()
    check(workspace.aliveCount == 0 && first.isRecycled && second.isRecycled)
    println("PASS: two-bitmap ownership / finally cleanup (value-object check, not native heap measurement)")
}
