package com.yourapp.wine

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.extract.WineTermSpacing
import com.yourapp.wine.fill.*
import com.yourapp.wine.geom.CoordMapper
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.merge.TokenMerger
import com.yourapp.wine.ocr.*
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/** Rounded device-report evidence replay only. No native ML Kit output or model accuracy is simulated. */
object ReportedWineChecks {
    private fun record(text: String, score: Float, variant: String, rect: List<Float>, orig: List<Float>): TokenRecord {
        val r = RectF(rect[0],rect[1],rect[2],rect[3]); val o = RectF(orig[0],orig[1],orig[2],orig[3])
        return TokenRecord(text, TextNorm.normalize(text), score, o, r,
            listOf(PointF(o.left*3072,o.top*4096),PointF(o.right*3072,o.top*4096),
                PointF(o.right*3072,o.bottom*4096),PointF(o.left*3072,o.bottom*4096)), "mlkit", variant)
    }
    private fun kirkland() = listOf(
        record("KIRKLAND", .854f,"V0",listOf(.305f,.130f,.665f,.213f),listOf(.345f,.315f,.566f,.346f)),
        record("CABERN ET SAUVIGNON", .635f,"V0",listOf(.128f,.355f,.864f,.447f),listOf(.237f,.399f,.688f,.433f)),
        record("ALEXANDER VALLEY", .742f,"V0",listOf(.173f,.626f,.804f,.691f),listOf(.265f,.500f,.651f,.524f)),
        record("SONOMA COUNTY", .682f,"V0",listOf(.288f,.729f,.684f,.772f),listOf(.335f,.538f,.577f,.555f)),
        record("2022", .881f,"V0",listOf(.414f,.829f,.545f,.871f),listOf(.412f,.576f,.493f,.592f)),
        record("KIRKLAND", .868f,"V1",listOf(.305f,.122f,.665f,.213f),listOf(.345f,.312f,.566f,.346f)),
        record("CABERN ET SAUVIGNON", .662f,"V1",listOf(.125f,.349f,.872f,.440f),listOf(.236f,.397f,.692f,.431f)),
        record("ALEXANDER VALLEY", .627f,"V1",listOf(.173f,.625f,.805f,.691f),listOf(.265f,.500f,.652f,.524f)),
        record("SONO MA COUNTY", .604f,"V1",listOf(.295f,.728f,.690f,.771f),listOf(.339f,.538f,.581f,.554f)),
        record("2022", .914f,"V1",listOf(.412f,.825f,.544f,.874f),listOf(.411f,.574f,.492f,.593f)),
    )

    fun kirklandReportDoesNotSwapProducerAndVariety() {
        val result = FillEngine(FixtureKnowledgeBase()).fill(kirkland(), 2026)
        val expected = mapOf(FieldId.PRODUCER to "Kirkland Signature", FieldId.WINE_NAME to "Cabernet Sauvignon",
            FieldId.COUNTRY to "United States", FieldId.REGION to "California", FieldId.SUBREGION to "Alexander Valley", FieldId.VINTAGE to "2022")
        expected.forEach { (field,value) -> check(result.fields.getValue(field).value == value) { result.fields } }
        check(result.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        check("alias" in result.fields.getValue(FieldId.PRODUCER).reason)
        check(result.fields.getValue(FieldId.PRODUCER).sources.flatMap { it.members }.all { "SIGNATURE" !in it.textRaw })
        check(result.fields.getValue(FieldId.WINE_NAME).state == UiState.YELLOW)
        for (field in listOf(FieldId.COUNTRY,FieldId.REGION,FieldId.SUBREGION,FieldId.VINTAGE)) {
            check(result.fields.getValue(field).state == UiState.GREEN) { result.fields }
        }
    }

    fun wordBreakRepairDoesNotGuessLettersOrUnknownNames() {
        for ((raw,expected) in listOf("CABERN ET SAUVIGNON" to "Cabernet Sauvignon", "PINOT N OIR" to "Pinot Noir",
                "SAUVI GNON BL ANC" to "Sauvignon Blanc", "CHARDON NAY" to "Chardonnay")) {
            check(WineTermSpacing.wineWords.repair(raw).text == expected)
        }
        check(WineTermSpacing.wineWords.repair("CABERXET SAUVIGNON").text == "CABERXET SAUVIGNON")
        check(WineTermSpacing.wineWords.repair("Domaine Petit Nuage").text == "Domaine Petit Nuage")
        check(BundledProducerNames.exactName("KIRK LAND") == "Kirkland Signature")
        check(BundledProducerNames.exactName("KIRKLANO") == null)
        val result = FillEngine(FixtureKnowledgeBase()).fill(listOf(LogicChecks.token("Domaine Petit Nuage",h=.05f),
            LogicChecks.token("CABERN ET SAUVIGNON",y=.4f,h=.15f)), 2026)
        check(result.fields.getValue(FieldId.PRODUCER).value == "Domaine Petit Nuage")
        check(result.fields.getValue(FieldId.WINE_NAME).value == "Cabernet Sauvignon")
    }

    fun geographicSupplementIsCachedReadOnlyAndWorksOnExistingSnapshots() {
        val base = FixtureKnowledgeBase()
        val original = base.places()
        val overlay = SupplementedWineKnowledgeBase(base)
        val places = overlay.places()
        check(places === overlay.places())
        check(base.places() === original && original.size == 27)
        val alexander = places.single { it.name == "Alexander Valley" && it.countryCode == "US" }
        check(alexander.id < 0)
        val path = PlaceHierarchy(overlay).snapshot().ancestors(alexander).map { it.name }
        check(path == listOf("Alexander Valley","Sonoma County","California","United States")) { path }
        check(places.any { it.name == "Mendoza" && it.countryCode == "AR" })
        check(places.any { it.name == "South Australia" && it.countryCode == "AU" })
    }

    fun importedHierarchyWinsAndConflictsStayReviewable() {
        val data = FixtureKnowledgeBase.snapshot
        val imported = FixtureKnowledgeBase(data.copy(places = data.places + Place(700,"Alexander Valley","appellation",19,"US")))
        val overlay = SupplementedWineKnowledgeBase(imported)
        check(overlay.places().single { it.name == "Alexander Valley" }.parentId == 19L)
        val result = FillEngine(imported).fill(kirkland(),2026)
        check(result.fields.getValue(FieldId.SUBREGION).state == UiState.YELLOW)
        val contradictory = FillEngine(FixtureKnowledgeBase()).fill(kirkland() + LogicChecks.token("France",y=.94f,h=.03f),2026)
        check(contradictory.fields.getValue(FieldId.COUNTRY).value == "France")
        check(contradictory.fields.getValue(FieldId.COUNTRY).state == UiState.YELLOW)
    }

    fun spacingIsNotDisagreementButDifferentLettersStillAre() {
        val engine = FillEngine(FixtureKnowledgeBase())
        val exact = LogicChecks.token("SONOMA COUNTY",.9f,y=.5f)
        val spaced = engine.fill(listOf(exact,exact.copy(textRaw="SONO MA COUNTY",textNorm="sono ma county",score=.75f,variantId="V1")),2026)
        check(spaced.fields.getValue(FieldId.REGION).state == UiState.GREEN) { spaced.fields }
        val different = engine.fill(listOf(exact,exact.copy(textRaw="SONORA COUNTY",textNorm="sonora county",score=.75f,variantId="V1")),2026)
        check(different.fields.getValue(FieldId.REGION).state == UiState.YELLOW)
        check(abs(TokenMerger().merge(listOf(exact,exact.copy(variantId="V1"))).single().vote - .9f) < .0001f)
    }

    fun blackstoneWeakTextDoesNotBecomeAFieldJustBecauseANameIsKnown() {
        val raw = listOf("LACKSTONE" to .480f,"WIN E R" to .589f,"Y" to .598f,
            "ACKSTONE" to .513f,"WI N E R Y" to .494f,"ACKSTONE" to .506f,"WIN E R Y" to .595f)
        val records = raw.mapIndexed { i,pair -> LogicChecks.token(pair.first,pair.second,variant="V${i%3}") } +
            LogicChecks.token("CALIFORNIA",.794f,y=.7f)
        val result = FillEngine(FixtureKnowledgeBase()).fill(records,2026)
        check(BundledProducerNames.exactName("Blackstone") == "Blackstone Winery")
        check(BundledProducerNames.exactName("LACKSTONE") == null)
        check(result.fields.getValue(FieldId.PRODUCER).state == UiState.RED && result.fields.getValue(FieldId.PRODUCER).value.isBlank())
        check(result.fields.getValue(FieldId.WINE_NAME).state == UiState.RED)
        val tall = RawToken("LACKSTONE",.480f,listOf(PointF(28f,240f),PointF(966f,240f),PointF(966f,606f),PointF(28f,606f)),"mlkit","V0")
        check(OcrRescuePolicy.needsAspectRescue(listOf(tall)))
        check(!OcrRescuePolicy.needsAspectRescue(listOf(tall.copy(score=.9f))))
        check(!OcrRescuePolicy.needsAspectRescue(listOf(tall.copy(text="ALC 13.5% BY VOL"))))
        check(!OcrRescuePolicy.needsAspectRescue(listOf(tall.copy(polygon=listOf(PointF(0f,0f),PointF(900f,0f),PointF(900f,50f),PointF(0f,50f))))))
    }

    fun aspectInverseHandlesOddDimensionsAndOriginalCoordinates() {
        check(OcrVariantGeometry.aspectHeight(1805) == 903)
        val raw = RawToken("new measured read",.73f,listOf(PointF(10f,100f),PointF(900f,100f),PointF(900f,903f),PointF(10f,903f)),"mlkit","V2")
        val label = OcrVariantGeometry.toLabel(raw,1280,903,1280,1805)
        check(abs(label.polygon[2].y-1805f) < .001f)
        check(label.score == raw.score && label.engineId == raw.engineId && label.variantId == raw.variantId)
        val mapper = CoordMapper(Matrix3.scale(1280.0/3072,1805.0/4096),3072,4096,1280,1805)
        val original = mapper.toOriginal(label)!!
        check(abs(original.bboxOrig.bottom-1f) < .0001f && original.bboxRect.bottom==1f)
        check(abs(mapper.toRectified(original.polyOrig)[0].y-label.polygon[0].y)<.002f)
    }

    fun ensemblePublishesBaseLabelCoordinatesNotVariantPixels() = runBlocking {
        val bitmap = Bitmap.createBitmap(16,24,Bitmap.Config.ARGB_8888)
        var released = false
        var published: RawToken? = null
        val engine = object : OcrEngine {
            override val id="mlkit"
            override val available=true
            override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline) = listOf(
                RawToken("sample",.8f,listOf(PointF(1f,1f),PointF(10f,1f),PointF(10f,20f),PointF(1f,20f)),id,variantId))
        }
        val now = { System.nanoTime()/1_000_000 }
        try {
            val batch = OcrEnsemble(listOf(engine),{}).run(listOf(OcrVariant("V2",{bitmap},{released=true},
                toLabel={token,w,h -> OcrVariantGeometry.toLabel(token,w,h,16,48)},
                description={_,_ -> "aspect inverse"})),ScanDeadline(now()+1000,now),onTokens={published=it.single()})
            check(batch.tokens.single().polygon[2].y == 40f && published!!.polygon[0].y == 2f)
            check(batch.tokens.single().score==.8f && batch.timings.single().detail=="aspect inverse")
            check(released)
        } finally { bitmap.recycle() }
    }

    fun printedAppellationStyleCanBeTheOnlyWineName() {
        val result = FillEngine(FixtureKnowledgeBase()).fill(listOf(LogicChecks.token("Bottega",y=.1f),
            LogicChecks.token("PROSECCO",y=.4f),LogicChecks.token("Italy",y=.7f)),2026)
        check(result.fields.getValue(FieldId.PRODUCER).value != "PROSECCO")
        check(result.fields.getValue(FieldId.WINE_NAME).value == "Prosecco") { result.fields }
        check(result.fields.getValue(FieldId.WINE_NAME).state == UiState.YELLOW)
    }
}
