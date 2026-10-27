package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.merge.*
import com.yourapp.wine.extract.*

/** Label-local and closed role vocabulary alternatives, never a bottle-name dictionary. */
internal object EvidenceLexicon {
    fun expand(graph:EvidenceGraph,checkBudget:()->Unit):EvidenceGraph {
        val out=graph.phrases.toMutableList()
        fun add(p:Phrase,text:String,reason:String) {
            if(out.size<EvidenceGraphBuilder.MAX_PHRASES && text!=p.text)
                out+=p.copy(id=out.size,text=text,construction=p.construction+"; "+reason)
        }
        val marker=Regex("(?iu)^([\\p{L}]{8,})\\s+(d|de|du|des)\\b")
        for(p in graph.phrases) {
            checkBudget()
            val m=marker.find(p.text)
            if(m!=null && TextNorm.editDistance(TextNorm.normalize(m.groupValues[1]),"vignerons",1)!=null) {
                val connector=if(m.groupValues[2].lowercase()=="d") "de" else m.groupValues[2]
                add(p,"Vignerons $connector"+p.text.substring(m.range.last+1),"bounded producer-role vocabulary alternative")
            }
            // Closed suffix vocabulary only after an exact appellation core; strip spaces, not letters.
            val n=TextNorm.normalize(p.text)
            if(n.startsWith("cotes du rhone ")) {
                val suffixWords=n.removePrefix("cotes du rhone ").split(' ').filter { it.isNotBlank() }
                val suffix=suffixWords.joinToString("")
                val repeatedInitial=suffixWords.size==2 && suffixWords[0].length==1 && suffixWords[1].startsWith(suffixWords[0])
                if(suffix.length in 5..9 && suffix.startsWith("v") && (TextNorm.editDistance(suffix,"villages",3)!=null || (repeatedInitial && TextNorm.editDistance(suffix.drop(1),"villages",3)!=null)))
                    add(p,"Cotes du Rhone Villages","bounded exact-core appellation suffix alternative")
            }
        }
        fun tail(text:String)=Regex("[\\p{L}]{5,}$").find(text)
        val roles=ProducerLineSelector()
        val makers=out.filter { roles.hasProducerCue(it.text) }
        // First propose producer-tail alternatives from standalone title readings at another location.
        for(p in makers) {
            checkBudget()
            val word=tail(p.text) ?: continue
            val titleReadings=graph.observations.filter { o -> o.id !in p.observations &&
                o.record.score>=.4f && o.record.textRaw.matches(Regex("(?iu)l['’][\\p{L}]{5,}")) &&
                kotlin.math.abs(o.record.bboxRect.centerY()-p.box.centerY())>maxOf(o.record.bboxRect.height(),p.box.height()) &&
                tail(o.record.textRaw)!=null }
            val corroboratedLocation=titleReadings.filter { a -> titleReadings.any { b ->
                a.record.engineId!=b.record.engineId && BoxMath.iou(a.record.bboxRect,b.record.bboxRect)>=.25f
            } }
            if(corroboratedLocation.isEmpty()) continue
            val candidates=corroboratedLocation.filter { TextNorm.editDistance(TextNorm.normalize(tail(it.record.textRaw)!!.value),TextNorm.normalize(word.value),1)!=null }
            if(candidates.any { TextNorm.normalize(tail(it.record.textRaw)!!.value)==TextNorm.normalize(word.value) }) continue
            val distinct=candidates.distinctBy { TextNorm.normalize(tail(it.record.textRaw)!!.value) }
            if(distinct.size!=1) continue
            val support=distinct.single()
            var start=word.range.first
            if(start>0 && p.text[start-1]=='\'') { start--; if(start>0 && p.text[start-1].equals('l',true)) start-- }
            add(p,p.text.substring(0,start)+support.record.textRaw,"label-local one-edit tail alternative; supporting observation=${support.id} (not an extra OCR vote)")
        }
        // Then title hypotheses may borrow only from a supported producer role, not arbitrary same-label words.
        val supportedMakers=out.filter { roles.hasProducerCue(it.text) && it.observations.any { id ->
            graph.observations.any { it.id==id && it.record.score>=.75f } } }
        for(p in out.toList().filter { it.construction.contains("connector") && !roles.hasProducerCue(it.text) }) {
            checkBudget()
            val word=tail(p.text) ?: continue
            val candidates=supportedMakers.filter { maker ->
                kotlin.math.abs(maker.box.centerY()-p.box.centerY())>maxOf(maker.box.height(),p.box.height()) &&
                    tail(maker.text)?.let { TextNorm.editDistance(TextNorm.normalize(it.value),TextNorm.normalize(word.value),1)!=null }==true }
            if(candidates.any { TextNorm.normalize(tail(it.text)!!.value)==TextNorm.normalize(word.value) }) continue
            val distinct=candidates.distinctBy { TextNorm.normalize(tail(it.text)!!.value) }
            if(distinct.size==1) {
                val maker=distinct.single()
                add(p,p.text.replaceRange(word.range,tail(maker.text)!!.value),"label-local one-edit tail alternative; supporting observations=${maker.observations} (not extra OCR votes)")
            }
        }
        return graph.copy(phrases=out,truncated=graph.truncated || out.size>=EvidenceGraphBuilder.MAX_PHRASES)
    }
}
