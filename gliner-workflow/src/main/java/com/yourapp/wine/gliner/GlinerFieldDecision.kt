package com.yourapp.wine.gliner

/** A lane hint is evidence, not an independent OCR vote or an authoritative assignment. */
data class GlinerFieldHint(val lane:GlinerLane,val text:String,val confidence:Float)
data class GlinerFieldDecision(val fields:Map<String,String>,val changes:List<String>)

/**
 * GLiNER-specific decision layer. Preserve the rules result by default. A wine-name lane can
 * repair an identity collision (producer and wine are identical) only if the lane supplies one
 * unambiguous whole OCR line that differs from the producer. A blank needs stronger confidence.
 * No reference targets, invented engine votes, or forced GREEN states enter this decision.
 */
object GlinerFieldDecisionPolicy {
    fun apply(fields:Map<String,String>,hints:List<GlinerFieldHint>,rawLines:List<String>):GlinerFieldDecision {
        val producer=fields["producer"].orEmpty()
        val originalWine=fields["wine_name"].orEmpty()
        val producerKey=GlinerDocument.normalize(producer)
        val wineKey=GlinerDocument.normalize(originalWine)
        val collision=producerKey.isNotBlank() && wineKey==producerKey
        val missing=wineKey.isBlank()
        if(!collision && !missing) return GlinerFieldDecision(fields,emptyList())
        val rawKeys=rawLines.map(GlinerDocument::normalize).filter(String::isNotBlank).toSet()
        val minimum=if(missing) 0.65f else 0.50f
        val candidates=hints.filter { hint ->
            val key=GlinerDocument.normalize(hint.text)
            hint.lane==GlinerLane.WINE_NAME && hint.confidence.isFinite() && hint.confidence>=minimum &&
                hint.confidence<=1f && key.length>=3 && key.any(Char::isLetter) &&
                key in rawKeys && key!=producerKey
        }.groupBy { GlinerDocument.normalize(it.text) }
        if(candidates.size!=1) return GlinerFieldDecision(fields,emptyList())
        val hint=candidates.values.single().maxByOrNull { it.confidence } ?: return GlinerFieldDecision(fields,emptyList())
        val updated=fields.toMutableMap().apply { put("wine_name",hint.text.trim()) }
        return GlinerFieldDecision(updated,listOf("wine_name: ${originalWine.ifBlank { "(blank)" }} -> ${hint.text.trim()} (GLiNER ${"%.3f".format(java.util.Locale.ROOT,hint.confidence)}; ${if(collision) "identity collision" else "blank"}; exact OCR line)"))
    }
}
