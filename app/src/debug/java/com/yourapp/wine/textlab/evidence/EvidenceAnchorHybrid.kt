package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.ocr.TokenRecord

/** Experimental architecture, not fieldwise reference selection. Named components are fixed for every image. */
internal class EvidenceAnchorHybrid(kb:WineKnowledgeBase) {
    data class Config(val roleGuard:Boolean,val appellationEngines:Int=0) {
        val id get()=if(!roleGuard) "anchors-only" else if(appellationEngines==0) "anchors-role-guard" else "anchors-role-guard-appellation-$appellationEngines"
    }
    companion object { val experiments=listOf(Config(false),Config(true),Config(true,1),Config(true,2)) }
    private val fallback=AppellationFallback(kb)
    fun combine(baseline:Map<FieldId,String>,revised:Map<FieldId,String>,records:List<TokenRecord>,config:Config):Map<FieldId,String> {
        require(baseline.keys==FieldId.entries.toSet() && revised.keys==baseline.keys)
        // Keep the evidence-selected origin bundle together; never splice individual geography fields.
        val out=revised.toMutableMap()
        out[FieldId.PRODUCER]=baseline.getValue(FieldId.PRODUCER)
        out[FieldId.VINTAGE]=baseline.getValue(FieldId.VINTAGE)
        if(config.roleGuard && EvidenceIdentityRoles.headingOnly(out.getValue(FieldId.PRODUCER))) out[FieldId.PRODUCER]=""
        // No country/region inference or producer substitution is introduced by these repairs.
        return if(config.appellationEngines>0) fallback.combine(out,records,AppellationFallback.Config(config.appellationEngines,false)) else out
    }
}
