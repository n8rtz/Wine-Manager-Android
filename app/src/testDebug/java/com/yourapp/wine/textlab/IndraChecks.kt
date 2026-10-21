package com.yourapp.wine.textlab

object IndraChecks {
    fun run() {
        val r=IndraMath.decode(longArrayOf(1,1,0,1,2),floatArrayOf(.9f,.9f,1f,.8f,.7f),5,listOf("é","ß"))
        check(r.raw=="ééß" && kotlin.math.abs(r.confidence-.8f)<.00001f)
        check(IndraMath.decode(longArrayOf(1),floatArrayOf(.49f),1,listOf("x")).accepted.not())
        check(runCatching { IndraMath.decode(longArrayOf(2),floatArrayOf(.9f),1,listOf("x")) }.isFailure)
        check(runCatching { IndraMath.decode(longArrayOf(1),floatArrayOf(Float.NaN),1,listOf("x")) }.isFailure)
        // Two horizontal cells at y=1: predicted top=1,bottom=5, stride=2 and .15*height x expansion.
        val out=FloatArray(3*16) { -10f };out[5]=2f;out[6]=2f
        for(i in 0 until 16) { out[16+i]=2f;out[32+i]=2f }
        val regions=IndraMath.regions(out,4,4,2.0,4,4) {}
        check(regions.boxes.size==1)
        val b=regions.boxes.single();check(kotlin.math.abs(b.left-.7f)<.00001f && b.top==.5f && b.bottom==2.5f)
        // Diagonal components are NOT connected; single-cell components are dropped.
        out[6]=-10f;out[10]=2f
        check(IndraMath.regions(out,4,4,2.0,4,4) {}.boxes.isEmpty())
        check(runCatching { IndraMath.regions(out,4,4,2.0,4,4) { error("cancel") } }.isFailure)
        val hist=IntArray(256);hist[0]=90;hist[255]=10;check(IndraMath.dark(hist,100))
        hist[0]=10;hist[255]=90;check(!IndraMath.dark(hist,100))
        val choices=TextLabChoices.all
        check(choices.size==4)
        for(mask in 1..15) {
            val ids=choices.filterIndexed { i,_ -> mask and (1 shl i)!=0 }.map { it.id }.toSet()
            val journal=TextLabJournal(ids,"test",true);journal.finishPending("not_run_cancelled");journal.finishPending("not_run_cancelled")
            check(journal.rows.size==ids.size)
            if(IndraSpec.ID in ids) {
                val row=journal.rows.single { it["candidate"]==IndraSpec.ID }
                check(row["detector_sha256"]==IndraSpec.DET_SHA && row["model_license"]!!.contains("OpenRAIL"))
                check(row["candidate_order"]==ids.size.toString())
                val outCsv=java.io.StringWriter();journal.writeCsv(outCsv)
                check(outCsv.toString().contains(IndraSpec.REC_SHA))
            }
        }
    }
}
