package com.yourapp.wine.fill

import android.util.Log
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.ocr.TokenRecord

/** A failed/busy imported KB must not discard completed OCR or crash the review screen. */
class ResilientFillEngine(kb: WineKnowledgeBase) {
    private val primary = FillEngine(kb)
    private val fixtureFallback by lazy { FillEngine(FixtureKnowledgeBase()) }

    /** Normalized original+rectified records in; on index failure, real small fixture rules cap all fills YELLOW. */
    fun fill(records: List<TokenRecord>, currentYear: Int, context: ConfidenceContext): FillResult = try {
        primary.fill(records, currentYear, context)
    } catch (error: Exception) {
        Log.w("WineScanner", "Local KB unavailable; retaining explicitly weak OCR/fixture evidence", error)
        fixtureFallback.fill(records, currentYear, context.copy(weakQuality = true,
            hint = "Local wine index unavailable — confirm partial readings or enter manually"))
    }

    /** No image coordinates: RED never proposes values. */
    fun red(hint: String): FillResult = primary.red(hint)
}
