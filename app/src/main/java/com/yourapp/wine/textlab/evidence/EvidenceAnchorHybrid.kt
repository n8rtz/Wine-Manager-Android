package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import java.util.Calendar

/** Experimental architecture, not fieldwise reference selection. Named components are fixed for every image. */
internal class EvidenceAnchorHybrid(kb:WineKnowledgeBase) {
    data class Config(val roleGuard:Boolean,val appellationEngines:Int=0,val deepestAppellation:Boolean=false,val styleAppellationWine:Boolean=false,val producerFallback:Boolean=false) {
        val id get()=if(!roleGuard) "anchors-only" else if(appellationEngines==0) "anchors-role-guard" else "anchors-role-guard-appellation-$appellationEngines"
    }
    companion object { const val VERSION="anchor-hybrid-v2"; val experiments=listOf(Config(false),Config(true),Config(true,1),Config(true,2)) }
    private val fallback=AppellationFallback(kb)
    private val canonicalVintagePattern=Regex("(?:19|20)\\d{2}")
    fun combine(baseline:Map<FieldId,String>,revised:Map<FieldId,String>,records:List<TokenRecord>,config:Config,
        maxVintageYear:Int=Calendar.getInstance().get(Calendar.YEAR),allowVintageFallback:Boolean=true):Map<FieldId,String> {
        require(baseline.keys==FieldId.entries.toSet() && revised.keys==baseline.keys)
        // Keep the evidence-selected origin bundle together; never splice individual geography fields.
        val out=revised.toMutableMap()
        out[FieldId.PRODUCER]=baseline.getValue(FieldId.PRODUCER)
        // Item 3b (Lab producer_anchor_fallback): a blank anchor must not
        // discard a producer the evidence solver actually selected.
        if(config.producerFallback && out.getValue(FieldId.PRODUCER).isBlank())
            out[FieldId.PRODUCER]=revised.getValue(FieldId.PRODUCER)
        val savedVintage=baseline.getValue(FieldId.VINTAGE)
        // A populated saved anchor always wins. Only a canonical, plausible year produced by the
        // experimental evidence extractor may fill a genuinely blank saved value.
        out[FieldId.VINTAGE]=if(savedVintage.isNotBlank()) savedVintage else if(!allowVintageFallback) "" else {
            val candidate=TextNorm.normalize(revised.getValue(FieldId.VINTAGE))
            val year=candidate.toIntOrNull()
            if(canonicalVintagePattern.matches(candidate) && year!=null && year in 1900..maxVintageYear) candidate else ""
        }
        if(config.roleGuard && EvidenceIdentityRoles.headingOnly(out.getValue(FieldId.PRODUCER))) out[FieldId.PRODUCER]=""
        // No country/region inference or producer substitution is introduced by these repairs.
        return if(config.appellationEngines>0) fallback.combine(out,records,AppellationFallback.Config(config.appellationEngines,false),config.deepestAppellation,config.styleAppellationWine) else out
    }
}
