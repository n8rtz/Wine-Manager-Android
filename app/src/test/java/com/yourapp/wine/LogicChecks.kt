package com.yourapp.wine

import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.extract.ExtractionResult
import com.yourapp.wine.extract.VintageExtractor
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.*
import com.yourapp.wine.ocr.*

/** Shared assertions run by Robolectric and the explicitly limited host logic harness. */
object LogicChecks {
    fun token(text: String, score: Float = .9f, engine: String = "mlkit", variant: String = "V0", x: Float = .1f,
              y: Float = .1f, w: Float = .6f, h: Float = .06f) = TokenRecord(text, TextNorm.normalize(text), score,
        RectF(x, y, x + w, y + h), RectF(x, y, x + w, y + h), listOf(PointF(x, y)), engine, variant)

    fun normalization() {
        check(TextNorm.normalize("  ChâTeau   MARGAUX ") == "chateau margaux")
        check(TextNorm.normalize("Cha\u0302teau") == "chateau")
        check(TextNorm.editDistance("chateau", "chateau") == 0)
        check(TextNorm.editDistance("margauy", "margaux") == 1)
        check(TextNorm.editDistance("abc", "xyz") == null)
        check(TextNorm.jaroWinkler("martha", "marhta") > .95)
    }

    fun merger() {
        val merged = TokenMerger().merge(listOf(token("Chateau Margaux", .8f),
            token("Château Margaux", .8f, "paddle"), token("Chateau Margaux", .9f, "mlkit", "V1")))
        check(merged.size == 1)
        check(kotlin.math.abs(merged.single().vote - .98f) < .0001f)
        val accent = TokenMerger().merge(listOf(token("Chateau", .8f), token("Château", .8f, "paddle"))).single()
        check(accent.canonical == "Château")
        check(TokenMerger().merge(listOf(token("Specialist", .76f, "paddle"))).size == 1)
        check(TokenMerger().merge(listOf(token("Specialist", .74f, "paddle"))).isEmpty())
        check(TokenMerger().merge(listOf(token("Weak", .59f), token("Weak", .59f, variant = "V1"))).isEmpty())
        check(TokenMerger().merge(listOf(token("Margaux"), token("Different"))).size == 2)
        check(TokenMerger().merge(listOf(token("Margaux", y = .1f), token("Margaux", y = .8f))).size == 2)
        // A-B and B-C spatial links must union transitively, even without an A-C link.
        check(TokenMerger().merge(listOf(token("Vietti", x = .10f, w = .02f),
            token("Vietti", x = .15f, w = .02f), token("Vietti", x = .20f, w = .02f))).size == 1)
    }

    fun lineGrouping() {
        val lines = LineBuilder().build(TokenMerger().merge(listOf(token("Margaux", x = .5f, w = .2f),
            token("Château", x = .1f, w = .2f), token("2018", y = .5f, w = .2f))))
        check(lines.size == 2)
        check(lines.first().text == "Château Margaux")
    }

    fun vintage() {
        val lines = LineBuilder().build(TokenMerger().merge(listOf(token("est. 1998", y = .1f),
            token("2018", y = .3f, score = .8f), token("2099", y = .5f), token("1899", y = .7f))))
        val found = VintageExtractor().extract(lines, 2026)
        check(found.first().value == "2018")
        check(found.none { it.value in setOf("2099", "1899", "1998") })
        val firstVintage = FillEngine(FixtureKnowledgeBase()).fill(listOf(token("FIRST VINTAGE 1996", y = .1f),
            token("ESTABLISHED 1844", y = .3f), token("2019", y = .6f, score = .8f)), 2026)
        check(firstVintage.fields.getValue(FieldId.VINTAGE).value == "2019")
        check(firstVintage.fields.getValue(FieldId.VINTAGE).state == UiState.GREEN)
        val historicalOnly = FillEngine(FixtureKnowledgeBase()).fill(listOf(token("Since 1998")), 2026)
        check(historicalOnly.fields.getValue(FieldId.VINTAGE).state == UiState.YELLOW)
    }

    fun hierarchy() {
        val kb = FixtureKnowledgeBase()
        val candidates = mapOf(
            FieldId.COUNTRY to listOf(FieldCandidate(FieldId.COUNTRY, "Italy", UiState.GREEN, "test", emptyList())),
            FieldId.REGION to listOf(FieldCandidate(FieldId.REGION, "Bordeaux", UiState.GREEN, "test", emptyList())),
            FieldId.SUBREGION to listOf(FieldCandidate(FieldId.SUBREGION, "Pauillac", UiState.GREEN, "test", emptyList())))
        val validated = Validator(kb).validate(ExtractionResult(candidates, emptyList()), 2026)
        check(validated.getValue(FieldId.COUNTRY).value == "Italy")
        check(listOf(FieldId.COUNTRY, FieldId.REGION, FieldId.SUBREGION).all { validated.getValue(it).state == UiState.YELLOW })
        val result = FillEngine(kb).fill(listOf(token("Château Latour", y = .1f), token("Pauillac", y = .3f),
            token("Italy", y = .5f), token("2018", y = .7f)), 2026)
        check(result.fields.getValue(FieldId.COUNTRY).value == "Italy")
        check(result.fields.getValue(FieldId.REGION).state == UiState.YELLOW)
    }

    fun nestedGeographyUsesSpecificPlaceWithoutFalseConflict() {
        val fill = FillEngine(FixtureKnowledgeBase())
        val nested = fill.fill(listOf(token("Robert Mondavi", y = .05f),
            token("Reserve Cabernet Sauvignon", y = .2f), token("Napa Valley", y = .35f),
            token("Oakville", y = .5f), token("California", y = .65f), token("2019", y = .8f)), 2026)
        check(nested.fields.getValue(FieldId.SUBREGION).value == "Oakville")
        check(nested.fields.values.all { it.state == UiState.GREEN }) { nested.fields }
        val siblings = fill.fill(listOf(token("Robert Mondavi", y = .05f), token("Oakville", y = .3f),
            token("Sonoma County", y = .5f), token("2019", y = .8f)), 2026)
        check(listOf(FieldId.COUNTRY, FieldId.REGION, FieldId.SUBREGION).all {
            siblings.fields.getValue(it).state == UiState.YELLOW
        })
        val wrongLevel = mapOf(FieldId.COUNTRY to listOf(
            FieldCandidate(FieldId.COUNTRY, "California", UiState.GREEN, "test", emptyList())))
        check(Validator(FixtureKnowledgeBase()).validate(ExtractionResult(wrongLevel, emptyList()), 2026)
            .getValue(FieldId.COUNTRY).state == UiState.YELLOW)
    }

    fun fillStates() {
        val fill = FillEngine(FixtureKnowledgeBase())
        val tokens = listOf(token("Château Margaux", y = .1f), token("Pavillon Rouge", y = .3f),
            token("Margaux", y = .5f), token("2018", y = .7f))
        val valid = fill.fill(tokens, 2026)
        check(valid.fields.size == 6)
        check(valid.fields.getValue(FieldId.PRODUCER).value == "Château Margaux")
        check(valid.fields.values.all { it.state == UiState.GREEN })
        val partial = fill.fill(tokens, 2026, ConfidenceContext(deadlineReached = true))
        check(partial.fields.values.none { it.state == UiState.GREEN })
        val fallback = fill.fill(tokens, 2026, ConfidenceContext(labelFound = false))
        check(fallback.fields.values.none { it.state == UiState.GREEN })
        val boutique = fill.fill(listOf(token("Domaine Petit Nuage", y = .1f), token("2020", y = .7f)), 2026)
        check(boutique.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        check(boutique.fields.getValue(FieldId.PRODUCER).value == "Domaine Petit Nuage")
        val red = fill.fill(listOf(token("13.5%"), token("75 cl", y = .3f), token("1234567890123", y = .5f)), 2026)
        check(red.fields.getValue(FieldId.PRODUCER).state == UiState.RED)
        check(red.fields.getValue(FieldId.WINE_NAME).value.isEmpty())
        check(fill.red("Hold steady").fields.values.all { it.state == UiState.RED && it.value.isEmpty() })
    }
    fun formPolicy() {
        val fill = FillEngine(FixtureKnowledgeBase()).fill(listOf(token("Château Margaux", y = .1f),
            token("Pavillon Rouge", y = .3f), token("Margaux", y = .5f), token("2018", y = .7f)), 2026)
        val empty = FieldId.entries.associateWith { "" }
        var form = com.yourapp.wine.ui.ScanFormState().apply(fill, empty)
        check(!form.hasPendingConfirmation)
        form = form.edit(FieldId.PRODUCER, "User's Château")
        form = form.apply(fill, form.fields.mapValues { it.value.value })
        check(form.fields.getValue(FieldId.PRODUCER).value == "User's Château")
        val yellow = fill.copy(fields = fill.fields.mapValues { (_, c) -> c.copy(state = UiState.YELLOW) })
        form = com.yourapp.wine.ui.ScanFormState().apply(yellow, empty)
        check(form.hasPendingConfirmation)
        FieldId.entries.forEach { form = form.confirm(it) }
        check(!form.hasPendingConfirmation)
        // Preserve an actually GREEN equivalent edit, not just a previously green field.
        var preserved = com.yourapp.wine.ui.ScanFormState().apply(fill, empty).edit(FieldId.PRODUCER, "CHATEAU MARGAUX")
        check(preserved.fields.getValue(FieldId.PRODUCER).state == UiState.GREEN)
        preserved = preserved.apply(fill, preserved.fields.mapValues { it.value.value })
        check(preserved.fields.getValue(FieldId.PRODUCER).value == "CHATEAU MARGAUX")
        var conflicting = com.yourapp.wine.ui.ScanFormState().apply(fill, empty).edit(FieldId.COUNTRY, "Italy")
        check(conflicting.fields.getValue(FieldId.REGION).state == UiState.YELLOW)
        conflicting = conflicting.apply(fill, conflicting.fields.mapValues { it.value.value })
        check(conflicting.fields.getValue(FieldId.COUNTRY).value == "Italy")
        check(conflicting.fields.getValue(FieldId.REGION).state == UiState.YELLOW)
        check(conflicting.hasPendingConfirmation)
        val red = FillEngine(FixtureKnowledgeBase()).red("Hold steady")
        check(com.yourapp.wine.ui.ScanFormState().apply(red, empty).fields.values.all { it.value.isEmpty() })
    }

    fun rejectedRetakeClearsOnlyAutomaticSuggestions() {
        val engine = FillEngine(FixtureKnowledgeBase())
        val good = engine.fill(listOf(token("Château Margaux", y = .1f), token("Pavillon Rouge", y = .3f),
            token("Margaux", y = .5f), token("2018", y = .7f)), 2026)
        val previous = com.yourapp.wine.ui.ScanFormState().apply(good, FieldId.entries.associateWith { "" })
        val red = engine.red("Hold steady")
        val rejected = previous.apply(red, previous.fields.mapValues { it.value.value })
        check(rejected.fields.values.all { it.state == UiState.RED && it.value.isBlank() })
        val edited = previous.edit(FieldId.PRODUCER, "CHATEAU MARGAUX")
            .edit(FieldId.WINE_NAME, "My manually entered cuvée")
        val protected = edited.apply(red, edited.fields.mapValues { it.value.value })
        check(protected.fields.getValue(FieldId.PRODUCER).value == "CHATEAU MARGAUX")
        check(protected.fields.getValue(FieldId.PRODUCER).state == UiState.GREEN)
        check(protected.fields.getValue(FieldId.WINE_NAME).value == "My manually entered cuvée")
        check(protected.fields.getValue(FieldId.VINTAGE).value.isEmpty())
        // An individually unreadable field must not keep a previous scan's automatically chosen vintage.
        val partial = good.copy(fields = good.fields + (FieldId.VINTAGE to red.fields.getValue(FieldId.VINTAGE)))
        val updated = previous.apply(partial, previous.fields.mapValues { it.value.value })
        check(updated.fields.getValue(FieldId.VINTAGE).state == UiState.RED)
        check(updated.fields.getValue(FieldId.VINTAGE).value.isEmpty())
        check(updated.fields.getValue(FieldId.PRODUCER).value == "Château Margaux")
    }

    fun extractionEdges() {
        val fill = FillEngine(FixtureKnowledgeBase())
        val inline = fill.fill(listOf(token("Château Margaux Pavillon Rouge 2018", y = .1f), token("Margaux", y = .5f)), 2026)
        check(inline.fields.getValue(FieldId.PRODUCER).value == "Château Margaux")
        check(inline.fields.getValue(FieldId.WINE_NAME).value == "Pavillon Rouge")
        check(inline.fields.getValue(FieldId.VINTAGE).value == "2018")
        val wrapped = fill.fill(listOf(token("Château", y = .1f), token("Margaux", y = .3f),
            token("Pavillon Rouge", y = .5f), token("2018", y = .7f)), 2026)
        check(wrapped.fields.getValue(FieldId.PRODUCER).value == "Château Margaux")
        check(wrapped.fields.getValue(FieldId.PRODUCER).state == UiState.GREEN)
        val fuzzy = fill.fill(listOf(token("Chateau Margauy", y = .1f), token("2018", y = .7f)), 2026)
        check(fuzzy.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        check(fuzzy.fields.getValue(FieldId.WINE_NAME).value.isEmpty())
        val unknown = fill.fill(listOf(token("Chateau Petit Nuage 2020", y = .1f)), 2026)
        check(unknown.fields.getValue(FieldId.PRODUCER).value == "Chateau Petit Nuage")
        check(unknown.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        val regionInName = fill.fill(listOf(token("Vietti", y = .1f), token("Barolo Castiglione", y = .3f), token("2019", y = .7f)), 2026)
        check(regionInName.fields.getValue(FieldId.WINE_NAME).value == "Castiglione")
        val barcode = fill.fill(listOf(token("Barcode 1234567890123", y = .1f), token("ABV 13.5% by vol", y = .3f)), 2026)
        check(barcode.fields.getValue(FieldId.PRODUCER).state == UiState.RED)
        val estimated = fill.fill(listOf(token("Vietti").copy(scoreIsEstimated = true)), 2026)
        check(estimated.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
    }

    fun producerCuesBeatProminentVarietalAndCuveeText() {
        val fill = FillEngine(FixtureKnowledgeBase())
        val olarra = fill.fill(listOf(token("Reciente", y = .10f, h = .13f),
            token("Gran Reserva", y = .30f, h = .05f), token("Bodegas Olarra", y = .43f, h = .05f),
            token("Rioja", y = .60f), token("2019", y = .80f)), 2026)
        check(olarra.fields.getValue(FieldId.PRODUCER).value == "Bodegas Olarra") { olarra.fields }
        check(olarra.fields.getValue(FieldId.WINE_NAME).value == "Reciente") { olarra.fields }
        check(olarra.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        val boutique = fill.fill(listOf(token("Domaine Petit Nuage", y = .1f, h = .05f),
            token("Pinot Noir", y = .4f, h = .16f)), 2026)
        check(boutique.fields.getValue(FieldId.PRODUCER).value == "Domaine Petit Nuage") { boutique.fields }
        check(boutique.fields.getValue(FieldId.WINE_NAME).value == "Pinot Noir") { boutique.fields }
    }

    fun wrappedProducerMarkersKeepTheirNearbyBrand() {
        val fill = FillEngine(FixtureKnowledgeBase())
        val blackstone = fill.fill(listOf(token("Blackstone", y = .10f, h = .09f),
            token("WINERY", y = .21f, h = .025f, x = .22f, w = .35f),
            token("Merlot", y = .48f, h = .14f), token("California", y = .70f)), 2026)
        check(blackstone.fields.getValue(FieldId.PRODUCER).value == "Blackstone WINERY") { blackstone.fields }
        check(blackstone.fields.getValue(FieldId.WINE_NAME).value == "Merlot") { blackstone.fields }
        check(blackstone.fields.getValue(FieldId.PRODUCER).sources.size == 2)
        check(blackstone.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        val split = fill.fill(listOf(token("Bodegas", y = .35f, h = .03f),
            token("Olarra", y = .40f, h = .06f), token("Reciente", y = .10f, h = .13f)), 2026)
        check(split.fields.getValue(FieldId.PRODUCER).value == "Bodegas Olarra") { split.fields }
        check(split.fields.getValue(FieldId.WINE_NAME).value == "Reciente") { split.fields }
    }

    fun ftsShortlistDoesNotRenameAnUnknownWinery() {
        val result = FillEngine(FixtureKnowledgeBase()).fill(listOf(token("Robert Fields Winery", y = .1f),
            token("Cabernet Sauvignon", y = .4f, h = .12f), token("2019", y = .8f)), 2026)
        check(result.fields.getValue(FieldId.PRODUCER).value == "Robert Fields Winery") { result.fields }
        check(result.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        check(result.fields.getValue(FieldId.WINE_NAME).value == "Cabernet Sauvignon")
    }

    fun wineDescriptionsAndBareMarkersAreNotProducers() {
        val fill = FillEngine(FixtureKnowledgeBase())
        for (description in listOf("Merlot", "Reserve Cabernet Sauvignon", "Old Vine Zinfandel", "Gran Reserva")) {
            val result = fill.fill(listOf(token(description), token("2019", y = .7f)), 2026)
            check(result.fields.getValue(FieldId.PRODUCER).state == UiState.RED) { result.fields }
            check(result.fields.getValue(FieldId.WINE_NAME).value == description) { result.fields }
        }
        for (marker in listOf("Winery", "W I N E R Y", "Winery®", "Winery.")) {
            val markers = fill.fill(listOf(token(marker), token("Estate Grown", y = .3f),
                token("2019", y = .7f)), 2026)
            check(markers.fields.getValue(FieldId.PRODUCER).state == UiState.RED) { markers.fields }
            check(markers.fields.getValue(FieldId.WINE_NAME).state == UiState.RED) { markers.fields }
        }
        val numberedName = fill.fill(listOf(token("Winery 7")), 2026)
        check(numberedName.fields.getValue(FieldId.PRODUCER).value == "Winery 7")
    }

    fun prominentBrandIsNotReplacedWithItsProductionFooter() {
        val result = FillEngine(FixtureKnowledgeBase()).fill(listOf(token("Penfolds", y = .1f, h = .12f),
            token("Koonunga Hill", y = .35f, h = .08f), token("Shiraz Cabernet", y = .5f, h = .04f),
            token("Penfolds wines South Australia", y = .65f, h = .025f), token("2019", y = .85f)), 2026)
        check(result.fields.getValue(FieldId.PRODUCER).value == "Penfolds") { result.fields }
        check(result.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        check(result.fields.getValue(FieldId.WINE_NAME).value == "Koonunga Hill") { result.fields }
    }

    fun producerMarkersDoNotBorrowDistantOrUnrelatedText() {
        val result = FillEngine(FixtureKnowledgeBase()).fill(listOf(token("Bodegas", x = .05f, y = .1f, w = .2f, h = .03f),
            token("Petit Nuage", x = .60f, y = .65f, w = .35f, h = .09f)), 2026)
        check(result.fields.getValue(FieldId.PRODUCER).value == "Petit Nuage") { result.fields }
        check(result.fields.getValue(FieldId.PRODUCER).sources.size == 1)
    }

    fun locationOnlyReadingDoesNotInventWineIdentity() {
        val location = token("CALIFORNIA", score = .84f, y = .7f)
        // A hypothetical below-threshold identity must not silently become a producer/name.
        val readings = listOf(token("EXAMPLE WINERY", score = .45f), location)
        check(TokenMerger().merge(readings).single().canonical == "CALIFORNIA")
        val result = FillEngine(FixtureKnowledgeBase()).fill(readings, 2026, ConfidenceContext(labelFound = false))
        for (field in listOf(FieldId.PRODUCER, FieldId.WINE_NAME)) {
            check(result.fields.getValue(field).state == UiState.RED)
            check(result.fields.getValue(field).value.isBlank())
        }
        check(result.fields.getValue(FieldId.COUNTRY).value == "United States")
        check(result.fields.getValue(FieldId.REGION).value == "California")
        check(result.fields.getValue(FieldId.REGION).state == UiState.YELLOW)
        val form = com.yourapp.wine.ui.ScanFormState().apply(result, FieldId.entries.associateWith { "" })
        check(form.hasPendingConfirmation)
        check(form.fields.getValue(FieldId.PRODUCER).value.isBlank())
    }

    fun indexFailurePreservesPartial() {
        val broken = object : com.yourapp.wine.kb.WineKnowledgeBase {
            override fun places() = FixtureKnowledgeBase().places()
            override fun findProducer(text: String): List<com.yourapp.wine.kb.ProducerHit> = error("Simulated busy/corrupt local index")
            override fun findCuvee(producerId: Long, text: String): com.yourapp.wine.kb.CuveeHit? = null
        }
        val result = ResilientFillEngine(broken).fill(listOf(token("Chateau Margaux", y = .1f), token("2018", y = .5f)),
            2026, ConfidenceContext())
        check(result.fields.getValue(FieldId.PRODUCER).value == "Château Margaux")
        check(result.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        check(result.fields.getValue(FieldId.VINTAGE).state == UiState.YELLOW)
    }

    fun estimatedScoresNeverPromoteWeakMeasurements() {
        val fill = FillEngine(FixtureKnowledgeBase())
        val real = token("Chateau Margaux", score = .50f)
        val estimated = real.copy(score = .65f, variantId = "V1", scoreIsEstimated = true)
        val mixed = fill.fill(listOf(real, estimated), 2026)
        check(mixed.fields.getValue(FieldId.PRODUCER).value == "Château Margaux")
        check(mixed.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        val strong = fill.fill(listOf(real.copy(score = .92f), estimated), 2026)
        check(strong.fields.getValue(FieldId.PRODUCER).state == UiState.GREEN)
        val paddle = real.copy(engineId = "paddle", score = .70f)
        check(fill.fill(listOf(paddle, estimated), 2026).fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        val weak = TokenMerger().merge(listOf(real.copy(score = .20f), paddle.copy(score = .20f)))
        check(weak.isEmpty())
        val independent = TokenMerger().merge(listOf(real.copy(score = .45f), paddle.copy(score = .45f)))
        check(independent.single().hasReliableSupport)
    }

    fun zeroAndInvalidConfidenceNeverManufactureSupport() {
        val merger = TokenMerger()
        val paddle = token("Chateau Margaux", .74f, "paddle")
        val zero = token("Chateau Margaux", 0f)
        check(merger.merge(listOf(paddle, zero)).isEmpty())
        check(merger.merge(listOf(paddle.copy(score = .76f), zero)).single().hasReliableSupport)
        val estimated = zero.copy(score = .65f, variantId = "V1", scoreIsEstimated = true)
        val partial = FillEngine(FixtureKnowledgeBase()).fill(listOf(paddle, zero, estimated), 2026)
        check(partial.fields.getValue(FieldId.PRODUCER).state == UiState.YELLOW)
        for (invalid in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            check(merger.merge(listOf(token("Chateau Margaux", invalid))).isEmpty())
            val mapper = com.yourapp.wine.geom.CoordMapper(com.yourapp.wine.geom.Matrix3.scale(1.0, 1.0), 2, 2, 2, 2)
            val raw = RawToken("2018", invalid, listOf(PointF(0f, 0f), PointF(2f, 0f), PointF(2f, 2f), PointF(0f, 2f)), "paddle", "V0")
            check(mapper.toOriginal(raw) == null)
        }
    }

    fun rejectedCapturesEnterRedReviewWithShutterTiming() {
        val capture = com.n8rtz.winetracker.scanner.LabelScanState.CapturingPhoto(42, 1000)
        for (review in listOf(capture.rejected("Camera unavailable", 1100),
                capture.rejected("Capture deadline", 9000, deadlineCancelled = true))) {
            check(review.requestId == 42L)
            check(review.result.fill.fields.size == 6)
            check(review.result.fill.fields.values.all { it.state == UiState.RED && it.value.isBlank() })
            check(review.result.tokens.isEmpty() && review.result.workingBitmapsAfter == 0)
        }
        val timedOut = capture.rejected("Capture deadline", 9000, deadlineCancelled = true)
        check(timedOut.result.elapsedMs == 8000L && timedOut.result.deadlineCancelled)
        check(capture.rejected("Camera unavailable", 1100).result.elapsedMs == 100L)
    }

}
