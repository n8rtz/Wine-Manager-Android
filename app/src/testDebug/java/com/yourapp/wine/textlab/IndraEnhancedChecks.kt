package com.yourapp.wine.textlab

import java.io.File

object IndraEnhancedChecks {
    fun run() {
        val root=listOf(File("app/src/testDebug/resources/indra"),File("src/testDebug/resources/indra")).first { it.isDirectory }
        val lm=IndraLanguageModel.load(File(root,"tiny-lm.npz")) {}
        check(lm.order==2 && lm.bits==3 && lm.vocabSize==3)
        File(root,"logp.tsv").forEachLine { line ->
            val parts=line.split('\t');val actual=lm.logp(parts[0].toLong(),parts[1].toInt())
            check(kotlin.math.abs(actual-parts[2].toDouble())<1e-7) { "LM backoff mismatch $line -> $actual" }
        }
        File(root,"beam.tsv").forEachLine { line ->
            val p=line.split('\t');val blank=p[0].split(',').map(String::toFloat).toFloatArray()
            val top=p[1].split(',').map(String::toFloat).toFloatArray();val ids=p[2].split(',').map(String::toLong).toLongArray()
            val expected=p[3].split(',').filter(String::isNotEmpty).map(String::toInt)
            val actual=IndraBeam.decode(blank,top,ids,blank.size,3,lm,{})
            check(actual==expected) { "Beam differs from upstream: $actual vs $expected" }
        }
        check(runCatching { IndraLanguageModel.load(File(root,"tiny-lm.npz")) { error("cancelled") } }.isFailure)
        check(IndraMath.tokensText(listOf("é","é","ß"))=="ééß")
        for(k in 0..3) {
            val m=IndraMath.turnMatrix(k,120,80)
            val point=android.graphics.PointF(11f,17f);val mapped=m.map(point);val original=m.inverse().map(mapped)
            check(kotlin.math.abs(original.x-point.x)<.0001f && kotlin.math.abs(original.y-point.y)<.0001f)
            if(k==1) check(mapped.x==63f && mapped.y==11f)
        }
        // Zero blank probability is valid log(0), and an LM count does not bound recognizer IDs.
        check(IndraBeam.decode(floatArrayOf(Float.NEGATIVE_INFINITY),floatArrayOf(-.1f),longArrayOf(4),1,1,lm,{},6)==listOf(4))
        check(runCatching { IndraBeam.decode(floatArrayOf(-1f),floatArrayOf(-.1f),longArrayOf(9),1,1,lm,{},6) }.isFailure)
        check(runCatching { IndraBeam.decode(floatArrayOf(Float.NaN),floatArrayOf(-.1f),longArrayOf(1),1,1,lm,{}) }.isFailure)
        check(IndraPassPlan.all.size==12 && IndraPassPlan.all.map { Triple(it.edge,it.deskew,it.contrast) }.distinct().size==12)
        val batchJournal=TextLabJournal(setOf(IndraSpec.MULTIPASS_ID,"candidate_3"),"test",true)
        batchJournal.passContext=IndraPassPlan.all.first().fields
        batchJournal.add(IndraSpec.MULTIPASS_ID,"recognition",values=mapOf("raw_text" to "Merlot"))
        batchJournal.add(IndraSpec.MULTIPASS_ID,"pass_summary","complete")
        batchJournal.passContext=emptyMap()
        batchJournal.finishPending("not_run_cancelled");batchJournal.finishPending("not_run_cancelled")
        check(batchJournal.rows.count { it["stage"]=="pass_summary" }==12)
        check(batchJournal.rows.first()["pass_id"]=="p01")
        check(batchJournal.rows.count { it["stage"]=="pass_summary" && it["status"]=="not_run_cancelled" }==11)
        check(batchJournal.rows.filter { it["candidate"]=="candidate_3" }.none { "pass_id" in it })
        val selected=setOf("candidate_3",IndraSpec.ENHANCED_ID)
        val j=TextLabJournal(selected,"test",true);j.finishPending("not_run_cancelled")
        val row=j.rows.single { it["candidate"]==IndraSpec.ENHANCED_ID }
        check(row["indra_lm_sha256"]==IndraSpec.LM_SHA && row["recognizer_max_width"]=="4096")
        check(row["candidate_order"]=="2" && row["status"]=="not_run_cancelled")
    }
}
