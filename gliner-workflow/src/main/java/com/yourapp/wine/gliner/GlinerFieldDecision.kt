package com.yourapp.wine.gliner

/** A lane hint is evidence, not an independent OCR vote or an authoritative assignment. */
data class GlinerFieldHint(val lane:GlinerLane,val text:String,val confidence:Float)
data class GlinerFieldDecision(val fields:Map<String,String>,val changes:List<String>)

/** One real, per-engine OCR line in normalized image coordinates. */
data class GlinerEvidenceLine(
    val engine:String, val text:String,
    val left:Float, val top:Float, val right:Float, val bottom:Float,
)

/**
 * Conservative GLiNER-specific decision layer. Reference/scoring data never enters this API.
 * The base result wins unless an explicit, auditable rule finds independent OCR evidence.
 */
object GlinerFieldDecisionPolicy {
    const val VERSION="gliner-field-policy-v2"

    fun apply(fields:Map<String,String>,hints:List<GlinerFieldHint>,rawLines:List<String>):GlinerFieldDecision {
        val producer=fields["producer"].orEmpty()
        val originalWine=fields["wine_name"].orEmpty()
        val producerKey=key(producer)
        val wineKey=key(originalWine)
        val collision=producerKey.isNotBlank() && wineKey==producerKey
        val missing=wineKey.isBlank()
        val geography=wineKey.isNotBlank() && listOf("region","subregion").any {
            key(fields[it].orEmpty()).let { location -> location.isNotBlank() && location==wineKey }
        }
        val rawKeys=rawLines.map(::key).filter(String::isNotBlank).toSet()
        val candidates=hints.filter { hint ->
            val k=key(hint.text)
            hint.lane==GlinerLane.WINE_NAME && hint.confidence.isFinite() &&
                hint.confidence >= (if(missing) 0.65f else 0.50f) && hint.confidence<=1f &&
                k.length>=3 && k.any(Char::isLetter) && k in rawKeys && k!=producerKey
        }.groupBy { key(it.text) }
        if(candidates.size!=1) return GlinerFieldDecision(fields,emptyList())
        val hint=candidates.values.single().maxByOrNull { it.confidence } ?: return GlinerFieldDecision(fields,emptyList())
        val hintKey=key(hint.text)
        // Remove a producer-like prefix only when the model separately identifies the exact OCR
        // suffix. Never trim an arbitrary longer title just because its end resembles a hint.
        val prefix=if(wineKey.endsWith(hintKey)) wineKey.dropLast(hintKey.length) else ""
        val producerPrefix=prefix.length>=6 && producerKey.length>=prefix.length &&
            editDistanceAtMostOne(prefix,producerKey.take(prefix.length))
        val reason=when {
            collision -> "identity collision"
            missing -> "blank"
            geography -> "base wine equals geography"
            producerPrefix -> "producer-like wine prefix"
            else -> return GlinerFieldDecision(fields,emptyList())
        }
        if(hintKey==wineKey) return GlinerFieldDecision(fields,emptyList())
        val updated=fields.toMutableMap().apply { put("wine_name",hint.text.trim()) }
        return GlinerFieldDecision(updated,listOf("wine_name: ${originalWine.ifBlank { "(blank)" }} -> ${hint.text.trim()} (GLiNER ${"%.3f".format(java.util.Locale.ROOT,hint.confidence)}; $reason; exact OCR line)"))
    }

    /**
     * Optional bounded repairs on two independently read, spatially overlapping OCR lines. Grape
     * terms are injected by the app from its ordinary bundled vocabulary, not from label targets.
     */
    fun applyWithEvidence(
        fields:Map<String,String>,hints:List<GlinerFieldHint>,readings:List<GlinerEvidenceLine>,grapeTerms:Set<String>,
    ):GlinerFieldDecision {
        val lines=consensus(readings)
        val grapes=grapeTerms.map(::key).filter { it.length>=4 }.toSet()
        val updated=fields.toMutableMap()
        val changes=mutableListOf<String>()
        fun replace(field:String,value:String,reason:String) {
            val old=updated[field].orEmpty()
            if(key(old)==key(value)) return
            updated[field]=value.trim()
            changes += "$field: ${old.ifBlank { "(blank)" }} -> ${value.trim()} ($reason)"
        }

        // Recover a blank or clearly legal-text-contaminated producer only. Consensus is over
        // actual overlapping engine readings, never variants or the fused GLiNER document.
        val baseProducer=key(updated["producer"].orEmpty())
        if(baseProducer.isBlank() || baseProducer.contains("bouteille") || baseProducer.contains("proprietaire")) {
            val candidates=mutableListOf<String>()
            val starts=setOf("chateau","chateaudu","chateaudela","domaine","domainedu","domainede","domainedela")
            lines.forEachIndexed { index,line ->
                // Strip a legal-company prefix only when the remainder itself is an estate name.
                val company=Regex("(?i)^s[.\\s]*n[.\\s]*c[.\\s]+(ch[aâ]teau\\s+.+)$").matchEntire(line.text.trim())
                company?.groupValues?.get(1)?.takeIf { !legal(it) }?.let(candidates::add)
                if(key(line.text) !in starts || index+1>=lines.size) return@forEachIndexed
                val next=lines[index+1]
                val nk=key(next.text)
                val places=listOf("wine_name","country","region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
                if(!adjacent(line,next) || nk.length<3 || nk.any(Char::isDigit) || nk in places || legal(next.text)) return@forEachIndexed
                candidates += "${line.text.trim()} ${next.text.trim()}"
            }
            unique(candidates)?.let { replace("producer",it,"two-engine estate evidence") }
        }

        val hinted=apply(updated,hints,readings.map { it.text })
        if(hinted.changes.isNotEmpty()) {
            updated.putAll(hinted.fields)
            changes += hinted.changes
        } else {
            val wine=key(updated["wine_name"].orEmpty())
            val producer=key(updated["producer"].orEmpty())
            val grapeLines=lines.filter { key(it.text) in grapes }.groupBy { key(it.text) }
            if(grapeLines.size==1) {
                val (grape,group)=grapeLines.entries.single()
                if(wine.isBlank() || (producer.isNotBlank() && wine==producer+grape)) {
                    replace("wine_name",group.first().text,"two-engine exact grape line")
                }
            }
            // If the entire base name ends in a grape, allow exactly one independently confirmed
            // preceding line. Exclude producers, places, years and common legal/growing claims.
            val current=key(updated["wine_name"].orEmpty())
            if(current.isNotBlank() && grapes.any { current.endsWith(it) }) {
                val prefixes=mutableListOf<String>()
                lines.forEachIndexed { index,first ->
                    val fk=key(first.text)
                    val producerKey=key(updated["producer"].orEmpty())
                    val blocked=listOf("country","region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
                    if(fk.length<3 || fk.any(Char::isDigit) || fk in blocked || legal(first.text) ||
                        (producerKey.isNotBlank() && (fk in producerKey || producerKey in fk))) return@forEachIndexed
                    val suffix=StringBuilder()
                    for(end in index+1 until minOf(lines.size,index+4)) {
                        if(!adjacent(lines[end-1],lines[end],allowOverlap=false)) break
                        suffix.append(key(lines[end].text))
                        if(suffix.toString()==current) {
                            prefixes += (index..end).joinToString(" ") { lines[it].text.trim() }
                        }
                        if(suffix.length>current.length) break
                    }
                }
                unique(prefixes)?.let { replace("wine_name",it,"two-engine adjacent title completion") }
            }
        }
        return GlinerFieldDecision(updated,changes)
    }

    private fun key(value:String)=GlinerDocument.normalize(value.trim()).takeIf { it.any(Char::isLetterOrDigit) }.orEmpty()

    private fun unique(values:List<String>):String? {
        val groups=values.groupBy(::key).filterKeys { it.isNotBlank() }
        return if(groups.size==1) groups.values.single().first() else null
    }

    private fun legal(value:String):Boolean {
        val k=key(value)
        return listOf("appellation","controlee","bouteille","proprietaire","produit", "product", "france", "estate", "grown", "vineyard")
            .any { it in k }
    }

    private fun editDistanceAtMostOne(a:String,b:String):Boolean {
        if(kotlin.math.abs(a.length-b.length)>1) return false
        var i=0; var j=0; var differences=0
        while(i<a.length && j<b.length) {
            if(a[i]==b[j]) { i++;j++;continue }
            if(++differences>1) return false
            when { a.length>b.length -> i++;b.length>a.length -> j++;else -> { i++;j++ } }
        }
        return differences+(a.length-i)+(b.length-j)<=1
    }

    private fun valid(line:GlinerEvidenceLine):Boolean =
        line.engine.isNotBlank() && line.text.isNotBlank() &&
            listOf(line.left,line.top,line.right,line.bottom).all { it.isFinite() && it in 0f..1f } &&
            line.right>line.left && line.bottom>line.top

    private fun iou(a:GlinerEvidenceLine,b:GlinerEvidenceLine):Float {
        val intersection=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)*
            (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
        val union=(a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-intersection
        return if(union>0f) intersection/union else 0f
    }

    private fun consensus(readings:List<GlinerEvidenceLine>):List<GlinerEvidenceLine> {
        // Normalize once per line, not once per pair, because this runs in a single-scan path.
        val usable=readings.filter(::valid).map { it to key(it.text) }.filter { it.second.length>=2 }
        val result=mutableListOf<Pair<GlinerEvidenceLine,String>>()
        // Prefer Paddle's text for display when both actual readings agree after normalization.
        usable.sortedBy { if(it.first.engine==GlinerEngines.PADDLE) 0 else 1 }.forEach { (line,k) ->
            if(usable.none { (other,otherKey) -> other.engine!=line.engine && otherKey==k && iou(line,other)>=0.25f }) return@forEach
            if(result.none { (other,otherKey) -> otherKey==k && iou(other,line)>=0.25f }) result += line to k
        }
        return result.map { it.first }.sortedWith(compareBy<GlinerEvidenceLine>({ (it.top+it.bottom)/2f },{ it.left }))
    }

    private fun adjacent(a:GlinerEvidenceLine,b:GlinerEvidenceLine,allowOverlap:Boolean=true):Boolean {
        val gap=b.top-a.bottom
        val height=maxOf(a.bottom-a.top,b.bottom-b.top)
        val overlap=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)
        val width=minOf(a.right-a.left,b.right-b.left)
        val verticalOverlap=(minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
        val shorterHeight=minOf(a.bottom-a.top,b.bottom-b.top)
        return gap<=maxOf(0.075f,2f*height) && width>0f && overlap/width>=0.35f &&
            (allowOverlap || verticalOverlap<=0.35f*shorterHeight)
    }
}
