package com.yourapp.wine

import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.geom.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.merge.TokenMerger
import com.yourapp.wine.ocr.*
import kotlin.math.abs

/** Regression from the supplied OnePlus report: rounded V0 boxes reconstruct working AABBs, not a pixel OCR replay. */
object LocalizationChecks {
    private const val W = 1439
    private const val H = 1918

    private fun read(text: String, score: Float, left: Float, top: Float, right: Float, bottom: Float) =
        RawToken(text, score, listOf(PointF(left * W, top * H), PointF(right * W, top * H),
            PointF(right * W, bottom * H), PointF(left * W, bottom * H)), "mlkit", "probe")

    private fun deviceProbe() = listOf(
        read("ACKSTONE!", .511f, .415f, .391f, .811f, .534f),
        read("WI NER Y", .453f, .382f, .525f, .685f, .555f),
        read("CALIFORNIA", .808f, .429f, .714f, .609f, .737f),
        read("ALC 13.5% BY OL", .444f, .466f, .823f, .579f, .843f),
    )

    fun weakBoxesLocalizeWithoutBecomingAcceptedText() {
        val readings = deviceProbe()
        check(readings.count { it.score >= .6f } == 1) // Reproduces why the previous localizer returned null.
        val selected = TextHullClusterer().select(readings, W, H)
        check(selected.size == 4) { "The actual probe must not collapse to just CALIFORNIA" }
        check(selected.first().score == .511f && selected[1].score == .453f)
        val records = readings.map { raw ->
            val box = RectF(raw.polygon.minOf { it.x } / W, raw.polygon.minOf { it.y } / H,
                raw.polygon.maxOf { it.x } / W, raw.polygon.maxOf { it.y } / H)
            TokenRecord(raw.text, TextNorm.normalize(raw.text), raw.score, RectF(box), RectF(box),
                raw.polygon.map { PointF(it.x * 3072 / W, it.y * 4096 / H) }, raw.engineId, "V0")
        }
        check(TokenMerger().merge(records).single().canonical == "CALIFORNIA")
    }

    fun sparseBodyKeepsTheBrandAndLowerLabelInOneProposal() {
        val selected = TextHullClusterer().select(deviceProbe(), W, H)
        val quad = LabelGeometryPolicy.textQuad(selected, W, H, 3072, 4096)!!
        val left = quad.minOf { it.x } / 3072; val right = quad.maxOf { it.x } / 3072
        val top = quad.minOf { it.y } / 4096; val bottom = quad.maxOf { it.y } / 4096
        // The full printed brand extends left of the incomplete "ACKSTONE" probe box.
        check(left <= .32f && right >= .811f)
        // The photographed script lies in the large gap between WINERY and CALIFORNIA.
        check(top <= .64f && bottom >= .71f)
        check(LabelGeometryPolicy.areaFraction(quad, 3072, 4096) >= .12)
        check(quad.all { it.x in 0f..3072f && it.y in 0f..4096f })
    }

    fun geometryNeedsAnAnchorAndRejectsInvalidReadings() {
        val probe = deviceProbe()
        check(TextHullClusterer().select(probe.map { it.copy(score = .2f) }, W, H).isEmpty())
        check(TextHullClusterer().select(listOf(probe[2]), W, H).isEmpty())
        val invalid = listOf(probe.first().copy(score = Float.NaN), probe.first().copy(score = 0f),
            probe.first().copy(score = 1.1f), probe.first().copy(polygon = listOf(PointF(Float.NaN, 1f))))
        check(TextHullClusterer().select(listOf(probe[2]) + invalid, W, H).isEmpty())
    }

    fun separateBottleColumnsDoNotJoin() {
        fun box(text: String, score: Float, x: Float, y: Float, w: Float, h: Float) =
            RawToken(text, score, listOf(PointF(x, y), PointF(x+w, y), PointF(x+w, y+h), PointF(x, y+h)), "mlkit", "probe")
        val left = listOf(box("First brand", .85f, 50f, 100f, 70f, 100f), box("First origin", .5f, 60f, 260f, 50f, 30f))
        val right = listOf(box("Other brand", .9f, 250f, 100f, 70f, 50f), box("Other origin", .5f, 250f, 230f, 60f, 30f))
        val selected = TextHullClusterer().select(left + right, 400, 800)
        check(selected.size == 2 && selected.all { it in left })
    }

    fun rectifiedCropsUseThePixelBudgetWithoutExceedingIt() {
        val enlarged = LabelGeometryPolicy.rectifiedSize(833f, 1170f)
        check(enlarged.width == 1280 && enlarged.height in 1790..1810)
        for ((w, h) in listOf(833f to 1170f, 2000f to 6000f, 840f to 1120f, 3000f to 1500f)) {
            val size = LabelGeometryPolicy.rectifiedSize(w, h)
            check(size.width <= 1280 && maxOf(size.width, size.height) <= 1920)
            check(size.width <= w * 2 + 1 && size.height <= h * 2 + 1)
            check(abs(size.width.toFloat() / size.height - w/h) < .003f)
        }
        try { LabelGeometryPolicy.rectifiedSize(Float.NaN, 100f); error("Must reject invalid geometry") }
        catch (_: IllegalArgumentException) { }
    }

    fun paddedProposalsStayBoundedAndTinyFragmentsRemainFallbacks() {
        val edge = listOf(PointF(0f, 100f), PointF(600f, 100f), PointF(600f, 900f), PointF(0f, 900f))
        val quad = LabelGeometryPolicy.expandedTextQuad(edge, 1000, 1000, 3000, 3000)!!
        check(quad.all { it.x in 0f..3000f && it.y in 0f..3000f })
        val tiny = listOf(PointF(400f, 700f), PointF(500f, 700f), PointF(500f, 740f), PointF(400f, 740f))
        check(LabelGeometryPolicy.expandedTextQuad(tiny, 1000, 1000, 3000, 3000) == null)
        val exact = listOf(PointF(0f, 0f), PointF(40f, 0f), PointF(40f, 30f), PointF(0f, 30f))
        check(LabelGeometryPolicy.areaFraction(exact, 100, 100) == LabelGeometryPolicy.MIN_LABEL_AREA_FRACTION)
    }

    fun footerCropDeclinesInsteadOfLosingTheTitle() {
        // Actual original->base homography from the attached CSV (both acc9133 and e9aad50).
        // This is geometric replay, NOT a rerun of ML Kit on the attached photograph.
        val oldH = Matrix3(listOf(0.6140555876325383, 0.014192887910906109, -275.46691910871994,
            -0.014197716967851444, 0.6138407008954746, -1536.711078974689,
            8.672068791062052E-11, -3.844494468431005E-11, 1.0))
        val oldQuad = listOf(PointF(0f,0f),PointF(1280f,0f),PointF(1280f,884f),PointF(0f,884f))
            .map(oldH.inverse()::map)
        check(oldQuad.minOf { it.y } / 4000 > .60f)
        check(oldH.map(PointF(1500f,1200f)).y < 0) // Upper title is outside the old base.
        check(LabelGeometryPolicy.isPartialBand(oldQuad,4000))
        val footer = listOf(read("Founding date",.7f,.20f,.69f,.75f,.73f),
            read("Origin line",.9f,.14f,.77f,.82f,.84f),read("Producer footer",.5f,.23f,.89f,.76f,.95f))
        val selected = TextHullClusterer().select(footer,W,H)
        check(selected.size==footer.size)
        check(LabelGeometryPolicy.textQuad(selected,W,H,3000,4000)==null)
        val title = read("Upper title",.3f,.28f,.25f,.75f,.39f)
        val joined=TextHullClusterer().select(footer+title,W,H)
        check(joined.isEmpty() || title in joined)
        val separatedTitle=read("Distant upper title",.3f,.28f,.10f,.75f,.20f)
        check(TextHullClusterer().select(footer+separatedTitle,W,H).isEmpty())
        check(title.score==.3f) // Weak geometry is not promoted into accepted identity evidence.
        val fullLabel = listOf(PointF(200f,300f),PointF(2800f,300f),PointF(2800f,3900f),PointF(200f,3900f))
        check(!LabelGeometryPolicy.isPartialBand(fullLabel,4000))
        val fullWideLabel = listOf(PointF(0f,20f),PointF(1000f,20f),PointF(1000f,480f),PointF(0f,480f))
        check(!LabelGeometryPolicy.isPartialBand(fullWideLabel,500))
        // Fallback preserves the entire source in a bounded image, with an invertible mapping.
        val size=LabelGeometryPolicy.rectifiedSize(1439f,1918f)
        val fallback=Matrix3.scale(size.width/3000.0,size.height/4000.0)
        val titlePoint=PointF(1500f,1200f)
        val mapped=fallback.map(titlePoint)
        check(mapped.x in 0f..size.width.toFloat() && mapped.y in 0f..size.height.toFloat())
        val restored=fallback.inverse().map(mapped)
        check(abs(restored.x-titlePoint.x)<.002f && abs(restored.y-titlePoint.y)<.002f)
    }

    fun rescueUsesUsableEvidenceRatherThanRawLineCount() {
        val probe = deviceProbe()
        val v1 = probe.map { it.copy(variantId = "V1", score = if (it.text == "CALIFORNIA") .780f else .547f) }
        check(OcrRescuePolicy.shouldRun(probe + v1))
        val complete = listOf("Vietti", "Castiglione", "Barolo").map { probe.first().copy(text = it, score = .9f) }
        check(!OcrRescuePolicy.shouldRun(complete + complete.map { it.copy(variantId = "V1") }))
        val missingName = listOf("BLACKSTONE", "WI NER Y", "CALIFORNIA", "2019", "13.5%").map {
            probe.first().copy(text = it, score = .9f)
        }
        check(OcrRescuePolicy.shouldRun(missingName))
        check(OcrRescuePolicy.shouldRun(complete.map { it.copy(scoreIsEstimated = true) }))
    }

    fun enlargedCropPreservesOriginalCoordinateRoundtrip() {
        val quad = LabelGeometryPolicy.textQuad(deviceProbe(), W, H, 3072, 4096)!!
        check(abs(quad[0].y - quad[1].y) < .001f) // Unequal line widths must not invent label roll.
        val theta = Math.PI / 18
        val rotated = deviceProbe().map { token -> token.copy(polygon = token.polygon.map { point ->
            val x = point.x - W/2f; val y = point.y - H/2f
            PointF((x*kotlin.math.cos(theta)-y*kotlin.math.sin(theta)+W/2f).toFloat(),
                (x*kotlin.math.sin(theta)+y*kotlin.math.cos(theta)+H/2f).toFloat())
        }) }
        val tilted = LabelGeometryPolicy.textQuad(rotated, W, H, 3072, 4096)!!
        val observed = kotlin.math.atan2(((tilted[1].y-tilted[0].y) * H/4096).toDouble(),
            ((tilted[1].x-tilted[0].x) * W/3072).toDouble())
        check(abs(observed-theta) < .001)
        val left = quad.minOf { it.x }; val top = quad.minOf { it.y }
        val width = quad.maxOf { it.x } - left; val height = quad.maxOf { it.y } - top
        val size = LabelGeometryPolicy.rectifiedSize(width * W / 3072, height * H / 4096)
        val sx = size.width / width.toDouble(); val sy = size.height / height.toDouble()
        val matrix = Matrix3(listOf(sx, 0.0, -left*sx, 0.0, sy, -top*sy, 0.0, 0.0, 1.0))
        val original = listOf(PointF(1300f, 1700f), PointF(2200f, 1700f), PointF(2200f, 2000f), PointF(1300f, 2000f))
        val raw = RawToken("unchanged evidence", .511f, original.map(matrix::map), "mlkit", "V0")
        val mapped = CoordMapper(matrix, 3072, 4096, size.width, size.height).toOriginal(raw)!!
        original.zip(mapped.polyOrig).forEach { (a,b) -> check(abs(a.x-b.x) < .002f && abs(a.y-b.y) < .002f) }
        check(mapped.score == .511f)
        check(mapped.bboxOrig.left in 0f..1f && mapped.bboxRect.right in 0f..1f)
    }
}
