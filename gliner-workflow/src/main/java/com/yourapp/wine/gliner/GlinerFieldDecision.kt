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
    const val VERSION="gliner-field-policy-v5"

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
        styleTerms:Set<String> = emptySet(),
        knownPlaceKeys:Set<String> = emptySet(),
    ):GlinerFieldDecision {
        val lines=consensus(readings)
        val grapes=grapeTerms.map(::key).filter { it.length>=4 }.toSet()
        val allStyles=styleTerms.map(::key).filter { it.length>=4 }.toSet()
        val styles=allStyles.filter { it.length>=8 }.toSet()
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

        // Complete a producer that visibly ends in a connecting particle. The existing value
        // must itself be an exact Paddle line; the following line must be independently read
        // at the same position by the other engine. Never use a grape, style, place or legal
        // text as the missing name, and abstain if more than one completion is plausible.
        val partialProducer=updated["producer"].orEmpty().trim()
        var producerContinuation:GlinerEvidenceLine?=null
        if(Regex("(?i)^.+\\s+(de|del|du|des|di|da|della)$").matches(partialProducer)) {
            val partialKey=key(partialProducer)
            val blocked=listOf("wine_name","country","region","subregion")
                .map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }.toSet()
            val primary=readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE }
            val completions=mutableListOf<Pair<String,GlinerEvidenceLine>>()
            primary.filter { key(it.text)==partialKey }.forEach { first ->
                primary.forEach secondLoop@ { second ->
                    val candidate=key(second.text)
                    if(candidate.length<5 || candidate.any(Char::isDigit) || candidate in blocked || candidate in knownPlaceKeys ||
                        candidate in grapes || candidate in allStyles || legal(second.text) || !fragmentConsensus(second,readings)) return@secondLoop
                    val overlap=(minOf(first.bottom,second.bottom)-maxOf(first.top,second.top)).coerceAtLeast(0f)
                    val shortest=minOf(first.bottom-first.top,second.bottom-second.top)
                    if(second.top<=first.top || second.top-first.bottom>0.06f || shortest<=0f ||
                        overlap>0.40f*shortest || horizontalOverlap(first,second)<0.5f) return@secondLoop
                    completions += "$partialProducer ${second.text.trim()}" to second
                }
            }
            unique(completions.map { it.first })?.let { completed ->
                producerContinuation=completions.first { key(it.first)==key(completed) }.second
                replace("producer",completed,"two-engine continuation of partial producer")
            }
        }

        // A legal or vineyard-role fragment can be selected instead of a prominent two-line
        // estate title. One complete title word must be corroborated by both engines in place;
        // the preceding article/word is retained exactly as read, not inferred.
        val currentProducer=key(updated["producer"].orEmpty())
        val locations=listOf("region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
        val legalProducer=currentProducer.startsWith("vinde") && locations.any { it in currentProducer }
        val roleProducer=currentProducer.length>=4 && readings.any { role ->
            valid(role) && role.engine==GlinerEngines.MLKIT && key(role.text).startsWith("vignobl") &&
                key(role.text).endsWith(currentProducer) && readings.any { part ->
                    valid(part) && part.engine==GlinerEngines.PADDLE && key(part.text).endsWith(currentProducer) && iou(role,part)>=0.15f
                } && readings.any { part ->
                    valid(part) && part.engine==GlinerEngines.PADDLE && key(part.text).startsWith("vignobl") && iou(role,part)>=0.15f
                }
        }
        if(legalProducer || roleProducer) {
            val candidates=mutableListOf<String>()
            val primary=readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE }
            primary.forEach { first ->
                if(!Regex("(?i)^(la|le|les)\\s+[\\p{L}]{4,}$").matches(first.text.trim())) return@forEach
                primary.forEach secondLoop@ { second ->
                    val secondKey=key(second.text)
                    if(secondKey.length<6 || secondKey.any(Char::isDigit) || legal(second.text) || secondKey in locations) return@secondLoop
                    val horizontal=(minOf(first.right,second.right)-maxOf(first.left,second.left)).coerceAtLeast(0f)
                    val width=minOf(first.right-first.left,second.right-second.left)
                    if(width<=0f || horizontal/width<0.4f || first.top>=second.top || first.bottom>second.bottom ||
                        second.top-first.bottom !in -0.05f..0.05f) return@secondLoop
                    if(readings.none { other -> valid(other) && other.engine!=first.engine && iou(second,other)>=0.30f &&
                            editDistanceAtMostTwo(secondKey,key(other.text)) }) return@secondLoop
                    candidates += "${first.text.trim()} ${second.text.trim()}"
                }
            }
            unique(candidates)?.let { replace("producer",it,"corroborated estate title over legal or vineyard role") }
        }

        // Address/number leakage can displace an estate printed as two adjacent lines. Require
        // both complete lines independently, with the second explicitly linking to the first.
        if(key(updated["producer"].orEmpty()).any(Char::isDigit)) {
            val candidates=mutableListOf<String>()
            lines.zipWithNext().forEach { (first,second) ->
                val firstText=first.text.trim()
                val secondText=second.text.trim()
                if(Regex("(?i)^[ld]['’][\\p{L}]+$").matches(firstText) &&
                    Regex("(?i)^de\\s+[\\p{L}][\\p{L}'’ -]+$").matches(secondText) &&
                    !legal(firstText) && !legal(secondText) && adjacent(first,second,allowOverlap=false))
                    candidates += "$firstText $secondText"
            }
            unique(candidates)?.let { replace("producer",it,"two-engine linked estate over address text") }
        }

        // A grape-and-place string is not an estate. Recover a three-part producer only when
        // both engines independently read every part, including the spatial linking particle.
        val erroneousProducer=key(updated["producer"].orEmpty())
        val grapeWine=key(updated["wine_name"].orEmpty())
        if(grapeWine in grapes && erroneousProducer.startsWith(grapeWine) && erroneousProducer!=grapeWine) {
            val excluded=listOf("country","region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
            val candidates=mutableListOf<String>()
            val names=lines.filter { key(it.text).length>=4 && key(it.text).none(Char::isDigit) &&
                key(it.text) !in excluded && key(it.text) !in grapes && !legal(it.text) }
            val particles=lines.filter { key(it.text)=="de" }
            names.forEach { first -> names.forEach lastLoop@ { last ->
                if(first==last || key(first.text)==key(last.text)) return@lastLoop
                particles.forEach { particle ->
                    val centerFirst=first.top+first.bottom
                    val centerParticle=particle.top+particle.bottom
                    val centerLast=last.top+last.bottom
                    if(centerFirst<centerParticle && centerParticle<centerLast &&
                        coveredBy(particle,first)>=0.8f && coveredBy(particle,last)>=0.8f)
                        candidates += "${first.text.trim()} ${particle.text.trim()} ${last.text.trim()}"
                }
            } }
            unique(candidates)?.let { replace("producer",it,"two-engine linked title over grape and location") }
        }

        // Overlapping OCR boxes can sort a small linking particle after the estate instead of
        // between its two words. Reorder only the three already-present, independently read words.
        val producerWords=updated["producer"].orEmpty().trim().split(Regex("\\s+"))
        if(producerWords.size==3 && key(producerWords[2])=="de" && producerWords.take(2).all { key(it).length>=4 }) {
            val candidates=mutableListOf<String>()
            lines.filter { key(it.text)==key(producerWords[0]) }.forEach { first ->
                lines.filter { key(it.text)==key(producerWords[1]) }.forEach { last ->
                    lines.filter { key(it.text)=="de" }.forEach { particle ->
                        val overlap=(minOf(particle.right,last.right)-maxOf(particle.left,last.left)).coerceAtLeast(0f)*
                            (minOf(particle.bottom,last.bottom)-maxOf(particle.top,last.top)).coerceAtLeast(0f)
                        val area=(particle.right-particle.left)*(particle.bottom-particle.top)
                        if(area>0f && overlap/area>=0.8f && first.top<particle.top &&
                            (particle.top+particle.bottom)<(last.top+last.bottom) && adjacent(first,last))
                            candidates += "${first.text.trim()} ${particle.text.trim()} ${last.text.trim()}"
                    }
                }
            }
            unique(candidates)?.let { replace("producer",it,"two-engine spatial particle ordering") }
        }

        // A separately printed winery/company line can correct a slightly damaged producer
        // already selected from the main label. This is same-engine, multi-location evidence,
        // not an independent OCR vote; require a winery role and a legal company suffix.
        val selectedEstate=key(updated["producer"].orEmpty())
        if(selectedEstate.length>=6) {
            val candidates=mutableListOf<String>()
            val titleLines=readings.filter { valid(it) && key(it.text)==selectedEstate }
            readings.filter(::valid).forEach { company ->
                val match=Regex("(?i)^([\\p{L}]+)\\s+([^,;]{6,})[,;]\\s*(.+)$").matchEntire(company.text.trim()) ?: return@forEach
                if(!editDistanceAtMostOne(key(match.groupValues[1]),"bodegas")) return@forEach
                val legalSuffix=key(match.groupValues[3])
                if(legalSuffix !in setOf("a","sa","sl","sarl","sas") ||
                    (legalSuffix=="a" && !Regex("(?i)^&[.\\s]*a$").matches(match.groupValues[3].trim()))) return@forEach
                val estate=match.groupValues[2].trim()
                if(!editDistanceAtMostTwo(key(estate),selectedEstate)) return@forEach
                if(titleLines.none { it.engine==company.engine && it.top+it.bottom<company.top+company.bottom }) return@forEach
                candidates += estate
            }
            unique(candidates)?.let { replace("producer",it,"printed winery company name near selected producer") }
        }

        // Classification words are not estate identities. If both OCR engines independently
        // read an estate prefix and the same place together, recover that observed estate name
        // instead of a classification-only producer. The place must also occur exactly in OCR.
        val producerBeforeEstate=key(updated["producer"].orEmpty())
        val placeText=updated["subregion"].orEmpty().trim()
        val placeKey=key(placeText)
        val classificationOnly=producerBeforeEstate=="premier" || producerBeforeEstate.contains("cruclass") ||
            producerBeforeEstate.contains("grandcrucclass") ||
            (placeKey.isNotBlank() && producerBeforeEstate=="chateaugrand"+placeKey)
        if(classificationOnly && placeKey.length>=6 && readings.any { valid(it) && key(it.text).contains(placeKey) } &&
            readings.any { valid(it) && key(it.text).contains("chateau") }) {
            val candidates=mutableListOf<String>()
            val primary=readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE }
            primary.forEach { prefix ->
                if(!editDistanceAtMostTwo(key(prefix.text),"chateau")) return@forEach
                primary.forEach placeLoop@ { place ->
                    if(!editDistanceAtMostTwo(key(place.text),placeKey)) return@placeLoop
                    val vertical=(minOf(prefix.bottom,place.bottom)-maxOf(prefix.top,place.top)).coerceAtLeast(0f)
                    val horizontal=(minOf(prefix.right,place.right)-maxOf(prefix.left,place.left)).coerceAtLeast(0f)
                    val height=minOf(prefix.bottom-prefix.top,place.bottom-place.top)
                    val width=minOf(prefix.right-prefix.left,place.right-place.left)
                    val sameLine=height>0f && vertical/height>=0.6f && place.left>=prefix.left &&
                        place.right>prefix.right && place.left-prefix.right<=0.12f
                    val stacked=width>0f && horizontal/width>=0.5f && place.top>=prefix.top &&
                        place.top-prefix.bottom in 0f..0.02f
                    if(!sameLine && !stacked) return@placeLoop
                    val joined=GlinerEvidenceLine(prefix.engine,"",minOf(prefix.left,place.left),minOf(prefix.top,place.top),
                        maxOf(prefix.right,place.right),maxOf(prefix.bottom,place.bottom))
                    val expected="chateau"+placeKey
                    val corroborated=readings.any { other -> valid(other) && other.engine!=prefix.engine &&
                        iou(joined,other)>=0.30f && editDistanceAtMost(expected,key(other.text),3) }
                    if(corroborated) candidates += "CHATEAU $placeText"
                }
            }
            unique(candidates)?.let { replace("producer",it,"two-engine estate and location evidence") }
        }

        // A model-confirmed title can be conflated with a grape list and a shorter producer.
        // Separate the fields only when the title is actually printed, both engines read the
        // grape list and producer suffix, and an adjacent "par la maison" role cue supports the
        // suffix as the producer. Do not infer a producer merely from a shared word.
        val collisionProducer=key(updated["producer"].orEmpty())
        val collisionWine=key(updated["wine_name"].orEmpty())
        val titleConfirmed=hints.any { it.lane==GlinerLane.PRODUCER && it.confidence.isFinite() &&
            it.confidence>=0.5f && key(it.text)==collisionProducer }
        if(titleConfirmed && collisionProducer.length>=6) {
            val candidates=mutableListOf<Pair<String,String>>()
            val titleLines=readings.filter { valid(it) && key(it.text)==collisionProducer }
            val grapeLists=lines.filter { grapeLine ->
                val parts=grapeLine.text.split(Regex("[-–—/]")).map { key(it) }
                parts.size>=2 && parts.all { it in grapes } && collisionWine==collisionProducer+key(grapeLine.text)
            }
            val makers=lines.filter { maker ->
                val makerKey=key(maker.text)
                makerKey.length>=5 && makerKey!=collisionProducer && collisionProducer.endsWith(makerKey) &&
                    maker.text.trim().split(Regex("\\s+")).size>=2
            }
            titleLines.forEach { title -> grapeLists.forEach { grape -> makers.forEach { maker ->
                val role=readings.any { r ->
                    val k=key(r.text)
                    valid(r) && k.contains("parla") && k.contains("mais") &&
                        r.top+r.bottom>grape.top+grape.bottom && r.top+r.bottom<maker.top+maker.bottom &&
                        horizontalOverlap(r,maker)>=0.5f && adjacent(r,maker)
                }
                if(title.top+title.bottom<grape.top+grape.bottom && grape.top+grape.bottom<maker.top+maker.bottom && role)
                    candidates += maker.text.trim() to title.text.trim()
            } } }
            val distinct=candidates.distinctBy { key(it.first) to key(it.second) }
            if(distinct.size==1) {
                val (maker,title)=distinct.single()
                replace("producer",maker,"two-engine producer with adjacent maison role")
                replace("wine_name",title,"model-confirmed title separated from grape list")
            }
        }

        val hinted=apply(updated,hints,readings.map { it.text })
        if(hinted.changes.isNotEmpty()) {
            updated.putAll(hinted.fields)
            changes += hinted.changes
        } else {
            val wine=key(updated["wine_name"].orEmpty())
            val producer=key(updated["producer"].orEmpty())
            val grapeLines=lines.filter { key(it.text) in grapes }.groupBy { key(it.text) }
            // A blank name can be a blend printed as two separate lines. Each complete
            // varietal must have independent OCR agreement, and they must form one unique,
            // adjacent pair; do not replace an existing title with an assumed blend.
            if(wine.isBlank()) {
                val blends=lines.zipWithNext().mapNotNull { (first,second) ->
                    val a=key(first.text); val b=key(second.text)
                    if(a in grapes && b in grapes && a!=b && adjacent(first,second,allowOverlap=false))
                        "${first.text.trim()} ${second.text.trim()}" else null
                }
                unique(blends)?.let { replace("wine_name",it,"two-engine adjacent grape blend") }
            }
            if(grapeLines.size==1) {
                val (grape,group)=grapeLines.entries.single()
                if(wine.isBlank() || (producer.isNotBlank() && wine==producer+grape)) {
                    replace("wine_name",group.first().text,"two-engine exact grape line")
                }
            }
            // If the entire base name ends in a grape, allow exactly one independently confirmed
            // preceding line. Exclude producers, places, years and common legal/growing claims.
            val current=key(updated["wine_name"].orEmpty())
            var exactAdjacentPrefixes:List<String> = emptyList()
            if(current.isNotBlank() && grapes.any { current.endsWith(it) }) {
                val prefixes=mutableListOf<String>()
                lines.forEachIndexed { index,first ->
                    val fk=key(first.text)
                    val producerKey=key(updated["producer"].orEmpty())
                    val blocked=listOf("country","region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
                    if(fk.length<3 || fk.any(Char::isDigit) || fk in blocked || fk in knownPlaceKeys || legal(first.text) ||
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
                if(current in grapes || current in styles) exactAdjacentPrefixes=prefixes
                else unique(prefixes)?.let { replace("wine_name",it,"two-engine adjacent title completion") }
            }
            // OCR engines may differ by a character or split a line into fragments. A complete
            // Paddle title prefix and the already-selected exact varietal/style are safe to join
            // only when both lines have spatially corroborating readings from another engine.
            val selected=key(updated["wine_name"].orEmpty())
            if(selected in grapes || selected in styles) {
                val blocked=listOf("country","region","subregion").map { key(updated[it].orEmpty()) }
                    .filter { it.isNotBlank() }.toSet()
                val selectedProducer=key(updated["producer"].orEmpty())
                val primary=readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE }
                val candidates=exactAdjacentPrefixes.toMutableList()
                primary.filter { key(it.text)==selected && nearLineConsensus(it,readings) }.forEach { suffix ->
                    primary.forEach prefixLoop@ { prefix ->
                        val value=prefix.text.trim(); val k=key(value)
                        if(k.length<6 || k.any(Char::isDigit) || value.split(Regex("\\s+")).size<2 ||
                            k in blocked || k in knownPlaceKeys || k in grapes || k in styles || legal(value) ||
                            (selectedProducer.isNotBlank() && (k in selectedProducer || selectedProducer in k)) ||
                            !nearLineConsensus(prefix,readings)) return@prefixLoop
                        val shorter=minOf(prefix.bottom-prefix.top,suffix.bottom-suffix.top)
                        val overlap=(minOf(prefix.bottom,suffix.bottom)-maxOf(prefix.top,suffix.top)).coerceAtLeast(0f)
                        val centerGap=(suffix.top+suffix.bottom-prefix.top-prefix.bottom)/2f
                        if(shorter<=0f || centerGap<0.35f*shorter || overlap>0.85f*shorter ||
                            !adjacent(prefix,suffix) || horizontalOverlap(prefix,suffix)<0.5f) return@prefixLoop
                        candidates += "$value ${suffix.text.trim()}"
                    }
                }
                unique(candidates)?.let { replace("wine_name",it,"corroborated adjacent title and varietal") }
            }
        }
        // OCR sometimes concatenates an estate-name line with the following appellation line.
        // Strip that prefix only if both engines corroborate the selected estate and the suffix
        // is a near match to the independently selected subregion plus an observed designation.
        val contaminatedWine=key(updated["wine_name"].orEmpty())
        val estateStem=key(updated["producer"].orEmpty()).removePrefix("chateau")
        val appellationKey=key(updated["subregion"].orEmpty())
        if(estateStem.length>=6 && appellationKey.length>=6 && contaminatedWine.isNotBlank()) {
            val candidates=mutableListOf<String>()
            readings.filter(::valid).forEach { appellation ->
                val match=Regex("(?i)^(.+?)\\s+(grand\\s+cru)$").matchEntire(appellation.text.trim()) ?: return@forEach
                val seenPlace=key(match.groupValues[1])
                val appellationReading=key(appellation.text)
                if(!contaminatedWine.endsWith(appellationReading) || !editDistanceAtMostTwo(seenPlace,appellationKey)) return@forEach
                val prefix=contaminatedWine.dropLast(appellationReading.length)
                if(!editDistanceAtMostTwo(prefix,estateStem)) return@forEach
                val supported=readings.any { first -> valid(first) && first.engine==appellation.engine && key(first.text)==prefix &&
                    first.top+first.bottom<appellation.top+appellation.bottom && horizontalOverlap(first,appellation)>=0.5f &&
                    readings.any { second -> valid(second) && second.engine!=first.engine && key(second.text)==estateStem &&
                        iou(first,second)>=0.3f }
                }
                if(supported) candidates += "${updated["subregion"].orEmpty().trim()} ${match.groupValues[2]}"
            }
            unique(candidates)?.let { replace("wine_name",it,"estate line separated from qualified appellation") }
        }

        // Correct a corrupted *existing* standalone classification, never promote a
        // classification over an unrelated wine title. Both engines must read GRAND CRU
        // exactly and independently support the rank and class on the same line.
        val currentClassification=key(updated["wine_name"].orEmpty())
        fun premierClassification(text:String):Boolean {
            val words=text.trim().split(Regex("\\s+")).map(::key)
            return words.size==4 && words[1]=="grand" && words[2]=="cru" &&
                editDistanceAtMost(words[0],"premier",3,5) && editDistanceAtMost(words[3],"classe",1,5)
        }
        val classificationLines=readings.filter { valid(it) && key(it.text)==currentClassification && premierClassification(it.text) }
        val classificationSupported=classificationLines.any { first -> readings.any { second ->
            valid(second) && second.engine!=first.engine && iou(first,second)>=0.3f && premierClassification(second.text)
        } }
        if(classificationSupported) replace("wine_name","Premier Grand Cru Classé","two-engine standalone classification spelling")

        // After completing an incomplete producer, an independently read standalone style
        // printed between that continuation and the selected geographic fallback can be the
        // label's wine name. The bounded ordering prevents an unrelated back-label style from
        // displacing an existing non-geographic title. Suggestions remain subject to review.
        val continued=producerContinuation
        val styleFallback=key(updated["wine_name"].orEmpty())
        val stylePlaces=listOf("region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
        if(continued!=null && styleFallback.isNotBlank() && styleFallback in stylePlaces) {
            val standaloneStyles=allStyles.filter { it.length>=6 }.toSet()
            val places=readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE && key(it.text)==styleFallback }
            val candidates=lines.filter { line ->
                val k=key(line.text)
                k in standaloneStyles && line.top+line.bottom>continued.top+continued.bottom &&
                    horizontalOverlap(line,continued)>=0.5f && places.any { place ->
                        line.top+line.bottom<place.top+place.bottom && horizontalOverlap(line,place)>=0.5f
                    }
            }.map { it.text.trim() }
            unique(candidates)?.let { replace("wine_name",it,"two-engine style between completed producer and place") }
        }

        // A blank wine may be an adjacent wine-style term and sweetness descriptor. The complete
        // style must be in the shared vocabulary, and independently supported even if the other
        // OCR engine split it into multiple overlapping text fragments.
        if(updated["wine_name"].isNullOrBlank() && styles.isNotEmpty()) {
            val descriptors=setOf("brut","extrabrut","brutnature","demisec","extradry")
            val candidates=mutableListOf<String>()
            readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE && key(it.text) in styles }.forEach { style ->
                if(!fragmentConsensus(style,readings)) return@forEach
                lines.filter { key(it.text) in descriptors }.forEach { descriptor ->
                    val ordered=if((descriptor.top+descriptor.bottom)<(style.top+style.bottom)) descriptor to style else style to descriptor
                    val overlap=(minOf(style.bottom,descriptor.bottom)-maxOf(style.top,descriptor.top)).coerceAtLeast(0f)
                    val shorter=minOf(style.bottom-style.top,descriptor.bottom-descriptor.top)
                    if(shorter>0f && overlap<=0.40f*shorter && adjacent(ordered.first,ordered.second))
                        candidates += "${ordered.first.text.trim()} ${ordered.second.text.trim()}"
                }
            }
            unique(candidates)?.let { replace("wine_name",it,"two-engine wine style and descriptor") }
        }

        // A harvest/year line may contain a printed title after the year. Require a harvest
        // marker within one character, an exact selected year, and a geographic wine fallback;
        // this is single-engine observed text, so ambiguity leads to abstention.
        val harvestFallback=key(updated["wine_name"].orEmpty())
        val harvestPlaces=listOf("region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
        val year=updated["vintage"].orEmpty().trim()
        if(harvestFallback.isNotBlank() && harvestFallback in harvestPlaces && year.matches(Regex("\\d{4}"))) {
            val candidates=mutableListOf<String>()
            readings.filter(::valid).forEach { line ->
                val match=Regex("(?i)^([\\p{L}]+)\\s+(\\d{4})\\s+([\\p{L}][\\p{L}'’ -]{2,})[\"”]?$")
                    .matchEntire(line.text.trim()) ?: return@forEach
                if(!editDistanceAtMostOne(key(match.groupValues[1]),"cosecha") || match.groupValues[2]!=year) return@forEach
                val title=match.groupValues[3].trim()
                if(key(title) in harvestPlaces || legal(title) || key(title)==key(updated["producer"].orEmpty())) return@forEach
                candidates += title
            }
            unique(candidates)?.let { replace("wine_name",it,"printed title on harvest and year line") }
        }

        // Preserve an explicitly printed qualified appellation when the base wine is only the
        // broader region. The second engine must corroborate the qualifier and the end of the
        // selected subregion at the same location; it need not read the entire first word.
        val broader=key(updated["wine_name"].orEmpty())
        val regionKey=key(updated["region"].orEmpty())
        val subregionKey=key(updated["subregion"].orEmpty())
        if(broader.isNotBlank() && broader==regionKey && subregionKey.length>=4 && subregionKey!=broader) {
            val candidates=mutableListOf<String>()
            val qualifiers=setOf("superieur","superieure","grandcru","premiercru")
            readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE }.forEach { title ->
                val titleKey=key(title.text)
                val qualifier=qualifiers.singleOrNull { titleKey==subregionKey+it } ?: return@forEach
                val supported=readings.any { other ->
                    val otherKey=key(other.text)
                    val partial=otherKey.dropLast(qualifier.length)
                    valid(other) && other.engine!=title.engine && iou(title,other)>=0.3f &&
                        partial.length>=2 && subregionKey.endsWith(partial) &&
                        editDistanceAtMostOne(otherKey.takeLast(qualifier.length),qualifier)
                }
                if(supported) candidates += title.text.trim()
            }
            unique(candidates)?.let { replace("wine_name",it,"corroborated qualified subregion") }
        }

        // Common cuvée descriptors may be read imperfectly by one engine. Only accept a word
        // printed exactly by the other engine, positioned between a corroborated selected estate
        // and the printed geographic fallback. The vocabulary is generic, not label references.
        val fallback=key(updated["wine_name"].orEmpty())
        val placeKeys=listOf("region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
        if(fallback.isNotBlank() && fallback in placeKeys) {
            val estateKey=key(updated["producer"].orEmpty())
            val estateLines=lines.filter { key(it.text).length>=6 && key(it.text) in estateKey }
            val placeLines=readings.filter { valid(it) && key(it.text)==fallback }
            val candidates=mutableListOf<String>()
            val descriptors=setOf("tradition","reserve","selection","prestige","classic","classique")
            readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE && key(it.text) in descriptors }.forEach { title ->
                val titleKey=key(title.text)
                val supported=readings.any { other -> valid(other) && other.engine!=title.engine &&
                    iou(title,other)>=0.3f && editDistanceAtMost(titleKey,key(other.text),3) }
                val between=estateLines.any { estate -> placeLines.any { place ->
                    val center=title.top+title.bottom
                    center>estate.top+estate.bottom && center<place.top+place.bottom &&
                        horizontalOverlap(title,estate)>=0.4f && horizontalOverlap(title,place)>=0.4f
                } }
                if(supported && between) candidates += title.text.trim()
            }
            unique(candidates)?.let { replace("wine_name",it,"corroborated descriptor between estate and place") }
        }

        // A geographically named fallback can mask a cuvee made from a distinctive first line
        // followed by "de <estate>". Both lines must be read at the same places by both engines,
        // and the second must be anchored in the independently selected producer.
        val wineKey=key(updated["wine_name"].orEmpty())
        val producerKey=key(updated["producer"].orEmpty())
        val geographicFallback=wineKey.isNotBlank() && listOf("region","subregion").any {
            key(updated[it].orEmpty()).let { place -> place.isNotBlank() && place==wineKey }
        }
        if(geographicFallback && producerKey.isNotBlank()) {
            val candidates=mutableListOf<String>()
            lines.zipWithNext().forEach { (first,second) ->
                val firstKey=key(first.text)
                val rawSecond=second.text.trim()
                val match=Regex("(?i)^de\\s+(.+)$").matchEntire(rawSecond) ?: return@forEach
                val estate=key(match.groupValues[1])
                if(firstKey.length<4 || firstKey.any(Char::isDigit) || legal(first.text) ||
                    firstKey in producerKey || firstKey in wineKey || estate.length<6 ||
                    estate !in producerKey || !adjacent(first,second,allowOverlap=false)) return@forEach
                candidates += "${first.text.trim()} ${second.text.trim()}"
            }
            unique(candidates)?.let { replace("wine_name",it,"two-engine title linked to selected producer") }
        }

        // A quoted title, or a large title directly above a model-confirmed producer, may be more
        // informative than a geographic fallback. Both engines must read essentially the same
        // text at the same place; never create support from duplicated readings of one engine.
        val currentWineKey=key(updated["wine_name"].orEmpty())
        val places=listOf("country","region","subregion").map { key(updated[it].orEmpty()) }.filter { it.isNotBlank() }
        val isGeoFallback=currentWineKey.length>=4 && places.any { it==currentWineKey || it.startsWith(currentWineKey) }
        if(isGeoFallback) {
            val currentProducerKey=key(updated["producer"].orEmpty())
            val producerConfirmed=hints.any { it.lane==GlinerLane.PRODUCER && it.confidence.isFinite() &&
                it.confidence>=0.5f && key(it.text)==currentProducerKey && currentProducerKey.isNotBlank() }
            val anchors=if(producerConfirmed) lines.filter { key(it.text)==currentProducerKey } else emptyList()
            val candidates=mutableListOf<String>()
            readings.filter { valid(it) && it.engine==GlinerEngines.PADDLE }.forEach { reading ->
                val text=reading.text.trim()
                val title=text.trim('《','》','«','»','“','”','"').trim()
                val titleKey=key(title)
                if(titleKey.length<6 || titleKey.any(Char::isDigit) || legal(title) || titleKey in places ||
                    titleKey==currentWineKey || (currentProducerKey.isNotBlank() &&
                        (titleKey in currentProducerKey || currentProducerKey in titleKey))) return@forEach
                val corroborated=readings.any { other -> valid(other) && other.engine!=reading.engine &&
                    iou(reading,other)>=0.30f && editDistanceAtMostTwo(titleKey,key(other.text)) }
                if(!corroborated) return@forEach
                val quoted=(text.startsWith('《') && text.endsWith('》')) ||
                    (text.startsWith('«') && text.endsWith('»')) ||
                    (text.startsWith('“') && text.endsWith('”')) ||
                    (text.length>=2 && text.startsWith('"') && text.endsWith('"'))
                val aboveProducer=anchors.any { anchor ->
                    val overlap=(minOf(reading.right,anchor.right)-maxOf(reading.left,anchor.left)).coerceAtLeast(0f)
                    val width=minOf(reading.right-reading.left,anchor.right-anchor.left)
                    val distance=anchor.top-reading.bottom
                    width>0f && overlap/width>=0.5f && (reading.top+reading.bottom)<(anchor.top+anchor.bottom) &&
                        distance>=-0.5f*minOf(reading.bottom-reading.top,anchor.bottom-anchor.top) &&
                        distance<=0.20f && (reading.bottom-reading.top)>=1.5f*(anchor.bottom-anchor.top)
                }
                if(quoted || aboveProducer) candidates += title
            }
            unique(candidates)?.let { replace("wine_name",it,"two-engine title evidence over geographic fallback") }
        }

        // If a single-word subregion is not read anywhere, but is one edit away from a producer
        // word independently observed by both engines at the same location, it may be a fuzzy
        // leakage from the producer name. Do not clear geography that is actually present in OCR.
        val subregion=updated["subregion"].orEmpty()
        val subKey=key(subregion)
        if(subregion.trim().split(Regex("\\s+")).size==1 && subKey.length>=5 &&
            readings.none { key(it.text).contains(subKey) }) {
            val producerWords=Regex("[\\p{L}]+(?:['’][\\p{L}]+)?").findAll(updated["producer"].orEmpty())
                .map { key(it.value) }.filter { it.length>=5 && it!=subKey && editDistanceAtMostOne(it,subKey) }.toSet()
            val corroborated=producerWords.any { word ->
                val candidates=readings.filter { valid(it) && key(it.text).contains(word) }
                candidates.any { a -> candidates.any { b -> a.engine!=b.engine && iou(a,b)>=0.25f } }
            }
            if(corroborated) replace("subregion","","unsupported fuzzy producer-word match")
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

    private fun editDistanceAtMostTwo(a:String,b:String):Boolean = editDistanceAtMost(a,b,2)

    private fun editDistanceAtMost(a:String,b:String,limit:Int,minLength:Int=6):Boolean {
        if(a.length<minLength || b.length<minLength || a.length>100 || b.length>100 || kotlin.math.abs(a.length-b.length)>limit) return false
        var previous=IntArray(b.length+1) { it }
        for(i in a.indices) {
            val current=IntArray(b.length+1)
            current[0]=i+1
            for(j in b.indices) {
                current[j+1]=minOf(previous[j+1]+1,current[j]+1,previous[j]+if(a[i]==b[j]) 0 else 1)
            }
            previous=current
        }
        return previous[b.length]<=limit
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

    private fun nearLineConsensus(primary:GlinerEvidenceLine,readings:List<GlinerEvidenceLine>):Boolean =
        readings.any { other -> valid(other) && other.engine!=primary.engine && iou(primary,other)>=0.3f &&
            editDistanceAtMostTwo(key(primary.text),key(other.text)) } || fragmentConsensus(primary,readings)

    private fun fragmentConsensus(primary:GlinerEvidenceLine,readings:List<GlinerEvidenceLine>):Boolean {
        val target=key(primary.text)
        return readings.asSequence().filter { valid(it) && it.engine!=primary.engine }.groupBy { it.engine }.values.any { group ->
            val pieces=group.filter { other ->
                val intersection=(minOf(primary.right,other.right)-maxOf(primary.left,other.left)).coerceAtLeast(0f)*
                    (minOf(primary.bottom,other.bottom)-maxOf(primary.top,other.top)).coerceAtLeast(0f)
                val area=(other.right-other.left)*(other.bottom-other.top)
                area>0f && intersection/area>=0.5f
            }.sortedBy { it.left }
            if(pieces.isEmpty() || pieces.size>8) false else {
                val combined=pieces.joinToString("") { key(it.text) }
                combined==target || editDistanceAtMostTwo(combined,target)
            }
        }
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

    private fun coveredBy(inner:GlinerEvidenceLine,outer:GlinerEvidenceLine):Float {
        val overlap=(minOf(inner.right,outer.right)-maxOf(inner.left,outer.left)).coerceAtLeast(0f)*
            (minOf(inner.bottom,outer.bottom)-maxOf(inner.top,outer.top)).coerceAtLeast(0f)
        val area=(inner.right-inner.left)*(inner.bottom-inner.top)
        return if(area>0f) overlap/area else 0f
    }

    private fun horizontalOverlap(a:GlinerEvidenceLine,b:GlinerEvidenceLine):Float {
        val overlap=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)
        val width=minOf(a.right-a.left,b.right-b.left)
        return if(width>0f) overlap/width else 0f
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
