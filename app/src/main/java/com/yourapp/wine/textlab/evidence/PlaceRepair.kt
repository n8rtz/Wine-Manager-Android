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
    data class Config(val enabled:Boolean=false,val rankPenalty:Double=8.0) {
        companion object {
            val off=Config()
            val experimental=Config(enabled=true)
        }
    }

    /** One engine's reading of the phrase; [family] groups the variants of the same engine. */
    data class Reading(val family:String,val text:String)

    data class Resolution(val place:Place,val observed:String,val matchedForm:String,val editDistance:Int,
        /** Engine families whose own reading was within budget of the repaired form; telemetry only, never a gate. */
        val engines:List<String>) {
        val reason get()="bounded appellation repair: '$observed' -> '$matchedForm'; edits=$editDistance; separate origin anchor; "+
            "engines=${engines.joinToString("+")}; confirm"
    }

    private class Form(val place:Place,val text:String,val key:String)

    private var indexed:List<Place>?=null
    private var byLength:Map<Int,List<Form>>=emptyMap()

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
                    if(key.length !in 5..64) continue
                    forms+=Form(place,text,key)
                }
            }
            byLength=forms.groupBy { it.key.length };indexed=places
        }
        return byLength
    }

    /**
     * Returns the single catalog place this unreadable phrase most plausibly names, or null when no
     * reading is close enough, the readings disagree at the best distance, or the anchor contradicts it.
     *
     * The phrase itself and every engine's own reading of it are searched together. Engines differ in
     * strength (ML Kit is weaker than Paddle on script faces), and the ensemble exists to widen what
     * reaches the filter, so one engine is enough to propose a form; a second engine only adds
     * corroboration to the trace. Precision comes from the separate anchor, the single anchor country,
     * the hierarchy compatibility test and the unambiguous-winner rule, not from an engine quorum.
     */
    fun resolve(observed:String,readings:List<Reading>,anchors:List<Place>,config:Config):Resolution? {
        if(!config.enabled) return null
        if(anchors.isEmpty()) return null
        // Competing anchor countries make the repair a guess, exactly as in the production spelling rule.
        if(anchors.map { it.countryCode }.distinct().size!=1) return null
        val index=index()
        // Wide net: distinct spellings of the same phrase, from the merged phrase and from each engine.
        val attempts=(listOf(observed)+readings.map { it.text }).map { text -> text to BoundedSpelling.key(text) }
            .filter { (_,key) -> key.length in 5..64 && key.split(' ').size<=6 }.distinctBy { it.second }
        var best:Int?=null
        val winners=mutableListOf<Form>()
        // A bounded edit can only span this window; BoundedSpelling.distance enforces the rest.
        for((text,query) in attempts) for(length in (query.length-2)..(query.length+2)) for(form in index[length].orEmpty()) {
            if(form.key==query) continue // Exact forms belong to the trie, not to a repair.
            val distance=BoundedSpelling.distance(text,form.text) ?: continue
            val previous=best
            if(previous==null || distance<previous) { best=distance;winners.clear();winners+=form }
            else if(distance==previous) winners+=form
        }
        // Competing places at the winning distance abstain; never pick one by distance or by list order.
        val candidates=winners.map { it.place }.distinctBy { it.id }
        if(candidates.size!=1) return null
        val place=candidates.single()
        val canonical=BoundedSpelling.key(place.name)
        val form=winners.firstOrNull { it.key==canonical } ?: winners.first()
        val view=hierarchy.snapshot()
        if(anchors.none { view.compatible(place,it) }) return null
        val engines=readings.filter { reading -> BoundedSpelling.distance(reading.text,form.text)!=null }
            .map { it.family }.distinct().sorted()
        return Resolution(place,observed,form.text,best ?: 0,engines.ifEmpty { listOf("phrase") })
    }
}
