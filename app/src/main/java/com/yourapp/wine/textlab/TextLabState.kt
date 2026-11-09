package com.yourapp.wine.textlab

/**
 * Default-off Lab engine switches. The OCR Text Lab screen that used to own this state is retired;
 * the instrumented engine tests still assert these defaults stay false.
 */
data class TextLabState(
    val compareEvidence: Boolean = false,
    val anchorHybrid: Boolean = false,
    val repairProducer: Boolean = false,
    val repairPlaces: Boolean = false,
    val resolverSingleReading: Boolean = false,
    val compareGazetteer: Boolean = false,
    val residualCandidates: Boolean = false,
    val detectLines: Boolean = true
)
