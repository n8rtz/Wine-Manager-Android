package com.yourapp.wine.textlab

/** Distinct deterministic ablations, not independent OCR engines or repeated votes. */
internal data class IndraPass(val index:Int,val edge:Int,val deskew:Boolean,val contrast:Boolean) {
    val id="p${index.toString().padStart(2,'0')}"
    val fields get()=mapOf("pass_id" to id,"pass_index" to "$index","pass_count" to "12",
        "pass_detector_edge" to "$edge","pass_orientation_deskew" to "$deskew",
        "pass_contrast" to if(contrast) "CLAHE clip=2 grid=8x8" else "none",
        "pass_independence" to "same engine; not independent corroboration")
}
internal object IndraPassPlan {
    // Interleave resolution before treatment changes, so a stopped test still covers all three scales.
    val all=listOf(false,true).flatMap { contrast -> listOf(false,true).flatMap { deskew ->
        listOf(1024,1536,2048).map { Triple(it,deskew,contrast) }
    } }.mapIndexed { i,(edge,deskew,contrast) -> IndraPass(i+1,edge,deskew,contrast) }
    fun finishPending(journal:TextLabJournal,status:String) {
        for(pass in all) if(journal.rows.none { it["candidate"]==IndraSpec.MULTIPASS_ID && it["pass_id"]==pass.id && it["stage"]=="pass_summary" })
            journal.add(IndraSpec.MULTIPASS_ID,"pass_summary",status,pass.fields)
    }

}
