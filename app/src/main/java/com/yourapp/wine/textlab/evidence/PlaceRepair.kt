package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.BoundedSpelling
import com.yourapp.wine.kb.*

/**
 * Lab-only, off by default. Bounded-distance appellation repair for a phrase the exact trie cannot read.
 *
 * Policy is the production geography-spelling rule ([BoundedSpelling]): 5-64 characters, at most six
 * words, one edit below ten characters and two above, at most a 20% length change, and an unambiguous
 * winner. On top of that this path requires a separate explicit country/region anchor elsewhere in the
 * same label. The engines are a wide net, not a quorum: every engine's own reading of the phrase is
 * searched and the closest catalog form wins outright, so a form only Paddle read is repaired even when
 * ML Kit garbled it, and vice versa. Two readings that point at different places at the same distance
 * abstain rather than picking one. A repair only ever *proposes* geography: it never overrides an exact
 * match, never chooses between anchor countries, and never changes raw OCR, a score or a saved field.
 */
internal class PlaceRepair(private val kb:WineKnowledgeBase,private val hierarchy:PlaceHierarchy) {
    data class Config(
        val enabled:Boolean=false,
        val rankPenalty:Double=8.0,
        val allowUnanchored:Boolean=true,
        val unanchoredMinLetters:Int=10,
        val unanchoredPenalty:Double=12.0,
    ) {
        companion object {
            val off=Config()
            val anchoredOnly=Config(enabled=true,allowUnanchored=false)
            val experimental=Config(enabled=true)
        }
    }

    /** One engine's reading of the phrase; [family] groups the variants of the same engine. */
    data class Reading(val family:String,val text:String)

    data class Resolution(
        val place:Place,
        val observed:String,
        val matchedForm:String,
        val editDistance:Int,
        /** Engine families whose supplied reading variant was within budget of the repaired form; telemetry only, never a gate. */
        val engines:List<String>,
        val penalty:Double=8.0,
        val anchorKind:String="separate origin anchor",
    ) {
        val reason get()="bounded appellation repair: '$observed' -> '$matchedForm'; edits=$editDistance; $anchorKind; "+
            "engines=${engines.joinToString("+")}; confirm"
    }

    private class Form(val place:Place,val text:String,val key:String,val words:Int)

    private var indexed: List<Place>? = null
    private var byLength: Map<Int, List<Form>> = emptyMap()

    /**
     * Length-bucketed index over catalog names and their bundled spelling forms. A bounded edit distance
     * cannot accept a form whose length differs by more than the edit budget, so the buckets keep one
     * repair attempt far below a scan of every place, and the index refreshes with the KB snapshot.
     */
    @Synchronized private fun index():Map<Int,List<Form>> {
        val places=kb.places()
        if(places!==indexed) {
            val forms=mutableListOf<Form>()
            for(place in places) {
                // A country word is never repaired into a place claim; the trie handles exact countries.
                if(place.type=="country") continue
                for(text in (listOf(place.name)+BundledOrigins.spellingForms(place)).distinct()) {
                    val key=BoundedSpelling.key(text)
                    val words=BoundedSpelling.wordCount(key)
                    if(key.length !in 5..64 || words>7 || key.any(Char::isDigit)) continue
                    forms+=Form(place,text,key,words)
                }
            }
            byLength=forms.groupBy { it.key.length };indexed=places
        }
        return byLength
    }

    fun resolve(observed:String,readings:List<Reading>,anchors:List<Place>,config:Config):Resolution? =
        resolve(observed,readings,anchors,config,emptySet())

    /**
     * Returns the single catalog place this unreadable phrase most plausibly names, or null when no
     * reading is close enough, the readings disagree at the best distance, or context contradicts it.
     *
     * Three progressively stricter gates apply:
     * 1. Explicit country/region [anchors] on another line (must agree on one country and be hierarchy-compatible).
     * 2. Label-wide [countryHints] from legal classifications (`DOCG`, `AVA`, framed `AOC`) or an unambiguous KB producer.
     * 3. Unanchored distinctive appellation repair ([Config.allowUnanchored]): at least ten letters, a
     *    globally unique non-`context_required` name separated from every rival place worldwide by at least
     *    `best + 2` edits.
     */
    fun resolve(
        observed:String,
        readings:List<Reading>,
        anchors:List<Place>,
        config:Config,
        countryHints:Set<String>,
    ):Resolution? {
        if(!config.enabled) return null
        if(anchors.isNotEmpty() && anchors.map { it.countryCode }.distinct().size!=1) return null
        if(anchors.isEmpty() && countryHints.isEmpty() && !config.allowUnanchored) return null
        val index=index()
        val view=hierarchy.snapshot()
        // Wide net: distinct spellings of the same phrase, from the merged phrase and from each engine.
        val attempts=(listOf(observed)+readings.map { it.text }).map { text -> text to BoundedSpelling.key(text) }
            .filter { (_,key) -> key.length in 5..64 && !key.any(Char::isDigit) && BoundedSpelling.wordCount(key)<=6 }.distinctBy { it.second }
        if(attempts.isEmpty()) return null
        val restrictCountries:Set<String> = when {
            anchors.isNotEmpty() -> emptySet() // Search worldwide so cross-country ties still abstain cleanly.
            countryHints.isNotEmpty() -> countryHints
            else -> emptySet()
        }
        var best:Int?=null
        val winners=mutableListOf<Form>()
        val bestByPlace=mutableMapOf<Long,Int>()
        // A bounded edit can only span this window; BoundedSpelling.distanceNormalized enforces the rest.
        for((_,query) in attempts) {
            val queryWords=BoundedSpelling.wordCount(query)
            for(length in (query.length-2)..(query.length+2)) for(form in index[length].orEmpty()) {
                if(restrictCountries.isNotEmpty() && form.place.countryCode !in restrictCountries) continue
                if(form.key==query) continue // Exact forms belong to the trie, not to a repair.
                val distance=BoundedSpelling.distanceNormalized(query,form.key,queryWords,form.words) ?: continue
                val previousPlaceBest=bestByPlace[form.place.id]
                if(previousPlaceBest==null || distance<previousPlaceBest) bestByPlace[form.place.id]=distance
                val previous=best
                if(previous==null || distance<previous) { best=distance;winners.clear();winners+=form }
                else if(distance==previous) winners+=form
            }
        }
        // Competing places at the winning distance abstain; never pick one by distance or by list order.
        val candidates=winners.map { it.place }.distinctBy { it.id }
        if(candidates.size!=1) return null
        val place=candidates.single()
        val bestDist=best ?: return null
        val (penalty,anchorKind)=when {
            anchors.isNotEmpty() -> {
                if(anchors.none { view.compatible(place,it) }) return null
                config.rankPenalty to "separate origin anchor"
            }
            countryHints.isNotEmpty() -> {
                if(place.countryCode !in countryHints) return null
                if(ReferenceWineCatalog.origin(place)?.contextRequired==true && countryHints.size>1) return null
                (config.rankPenalty+2.0) to "label country cue (${countryHints.sorted().joinToString("/")})"
            }
            else -> {
                val canonicalLetters=place.name.count(Char::isLetter)
                val observedLetters=observed.count(Char::isLetter)
                if(minOf(observedLetters,canonicalLetters)<config.unanchoredMinLetters) return null
                if(ReferenceWineCatalog.origin(place)?.contextRequired==true) return null
                if(view.byName[place.nameNorm].orEmpty().map { it.countryCode }.distinct().size>1) return null
                val maxUnanchoredEdits=if(minOf(observedLetters,canonicalLetters)>=12) 2 else 1
                if(bestDist>maxUnanchoredEdits) return null
                if(bestByPlace.any { (otherId,dist) -> otherId!=place.id && dist<=bestDist+1 }) return null
                config.unanchoredPenalty to "unanchored distinctive appellation"
            }
        }
        val canonical=BoundedSpelling.key(place.name)
        val form=winners.firstOrNull { it.key==canonical } ?: winners.first()
        val engines=readings.filter { reading -> BoundedSpelling.distanceNormalized(BoundedSpelling.key(reading.text),form.key,bWords=form.words)!=null }
            .map { it.family }.distinct().sorted()
        return Resolution(place,observed,form.text,bestDist,engines.ifEmpty { listOf("phrase") },penalty,anchorKind)
    }
}
