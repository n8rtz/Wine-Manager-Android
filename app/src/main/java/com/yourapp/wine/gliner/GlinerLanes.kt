package com.yourapp.wine.gliner

/**
 * The three contextual lanes GLiNER-Small is asked for.
 *
 * The lanes are deliberately coarse. GLiNER groups scrambled label fragments and answers
 * "which words belong to one of these three concepts"; it is never asked for country, region,
 * subregion or vintage. Those four parameters stay with the existing Lab v7 post-processing,
 * which already owns the gazetteer hierarchy, the year normalizer and the producer catalog.
 */
internal enum class GlinerLane(val key:String,val title:String,val prompt:String,val shortName:String) {
    PRODUCER("producer","Producer lane","wine producer or winery name","producer"),
    WINE_NAME("wine_name","Wine name lane","wine name or cuvee","wine name"),
    APPELLATION("appellation","Appellation lane","wine appellation, region or country","appellation");

    companion object {
        val ordered:List<GlinerLane> = listOf(PRODUCER,WINE_NAME,APPELLATION)
        fun fromPrompt(prompt:String):GlinerLane? = ordered.firstOrNull { it.prompt==prompt }
    }
}

/**
 * Prompt set and decoding defaults for one run. Immutable so a screen toggle can never mutate a
 * run in flight; the exact strings and numbers are written into every exported row.
 */
internal data class GlinerLaneConfig(
    val prompts:List<String> = GlinerLane.ordered.map { it.prompt },
    val threshold:Float = 0.5f,
    val maxWidth:Int = 12,
    val maxWords:Int = 320,
    val version:String = "gliner-lanes-v1",
) {
    init {
        require(prompts.size==GlinerLane.ordered.size) { "One prompt per lane is required" }
        require(prompts.toSet().size==prompts.size) { "Lane prompts must be distinct" }
        require(threshold in 0.05f..0.95f) { "Confidence threshold must be inside 0.05..0.95" }
        require(maxWidth in 1..32) { "Span width must be inside 1..32" }
        require(maxWords in 32..1024) { "Word budget must be inside 32..1024" }
    }
    fun promptFor(lane:GlinerLane):String = prompts[GlinerLane.ordered.indexOf(lane)]
    fun signatureKey():String = "gliner-v1;t=${"%.2f".format(java.util.Locale.ROOT,threshold)};w=$maxWidth;n=$maxWords"
    fun summaryLabel():String = "GLiNER-Small INT4 · threshold ${"%.2f".format(java.util.Locale.ROOT,threshold)} · width $maxWidth · prompts ${version}"
}

/** One labelled fragment produced by GLiNER, resolved back to document words and OCR geometry. */
internal data class GlinerEntity(
    val lane:GlinerLane,
    val wordStart:Int,
    val wordEnd:Int,
    val text:String,
    val score:Float,
    val box:android.graphics.RectF,
    val lineIndices:List<Int>,
    val prompt:String,
) {
    val wordCount:Int get() = wordEnd-wordStart+1
}

/**
 * Grouped lane result: every accepted fragment of one lane, in reading order. Fragments are kept
 * separate (not pasted into one string) because each one becomes exactly one post-processing
 * observation with its own geometry.
 */
internal data class GlinerLaneGroup(
    val lane:GlinerLane,
    val prompt:String,
    val entities:List<GlinerEntity>,
    val rejectedCount:Int,
) {
    val text:String get() = entities.joinToString(" | ") { it.text }
    val isEmpty:Boolean get() = entities.isEmpty()
}

/** Everything stage 2 knows after one label. */
internal data class GlinerLabelResult(
    val document:GlinerDocument,
    val lanes:List<GlinerLaneGroup>,
    val residualLines:List<GlinerResidualLine>,
    val entities:List<GlinerEntity>,
    val modelSignature:String,
    val int4:Boolean,
    val inputSignature:String,
    val outputShape:String,
    val layout:String,
    val wordCount:Int,
    val spanCount:Int,
    val elapsedMs:Long,
    val config:GlinerLaneConfig,
    /** Best span per lane before thresholding, so a run that labels nothing still shows its scores. */
    val probes:List<String> = emptyList(),
    /** Ids of the encoded prompt words: `1, 2569, 4052, 20773, 1504, 1504, 2084, …` for this bundle. */
    val promptIds:List<Int> = emptyList(),
    /** Tokens the tokenizer could not resolve; anything above zero means the ids are wrong. */
    val unknownTokens:Int = 0,
) {
    fun lane(lane:GlinerLane):GlinerLaneGroup = lanes.first { it.lane==lane }
    val groupedText:String get() = document.text
}

/** Words that no lane claimed. They still flow into the existing post-processing as observations. */
internal data class GlinerResidualLine(
    val lineIndex:Int,
    val text:String,
    val box:android.graphics.RectF,
    val wordCount:Int,
)
