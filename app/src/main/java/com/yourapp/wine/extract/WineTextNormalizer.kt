package com.yourapp.wine.extract

import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.kb.Place
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.merge.TextNorm

/** Cached vocabulary normalization; raw OCR, measured scores and both coordinate spaces remain unchanged. */
class WineTextNormalizer(private val kb: WineKnowledgeBase) {
    private var indexedPlaces: List<Place>? = null
    private var geography = WineTermSpacing(emptyList())

    /** No coordinates: repair exact known word boundaries/aliases only; never guess missing letters or a wine name. */
    @Synchronized fun repair(text: String): WineTextRepair {
        val places = kb.places()
        if (places !== indexedPlaces) {
            geography = WineTermSpacing(places.flatMap { place ->
                (listOf(place.name) + BundledOrigins.spellingForms(place)).map { it to place.name }
            })
            indexedPlaces = places
        }
        val wine = WineTermSpacing.wineWords.repair(text)
        val result = geography.repair(wine.text).text
        return WineTextRepair(result, TextNorm.normalize(result) != TextNorm.normalize(text))
    }

    /** No coordinates: equivalent known spacing must not masquerade as independent OCR disagreement. */
    fun comparison(text: String): String = TextNorm.normalize(repair(text).text)
}
