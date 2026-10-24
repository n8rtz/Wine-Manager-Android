package com.yourapp.wine.textlab

import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.kb.FixtureKnowledgeBase

object TextLabFormChecks {
    fun run() {
        val batch=TextLabBatch(listOf(TextLabImageKey("one","label.jpg"),TextLabImageKey("two","empty.jpg")),setOf("candidate_3"),"test",true)
        val journal=batch.journals.first()
        fun read(candidate:String,text:String,y:Float,confidence:String) {
            journal.add(candidate,"recognition",values=mapOf("raw_text" to text,"base_box_normalized" to ".1 $y .8 ${y+.05f}",
                "ctc_posterior" to confidence,"mlkit_confidence" to confidence,"confidence_estimated" to "false"))
        }
        read("mlkit_reference","KIRKLAND",.1f,".95")
        read("candidate_3","BRUNELLO DI MONTALCINO",.4f,".95")
        read("candidate_3","2021",.7f,".95")
        journal.add("candidate_3","candidate_summary","complete")
        val records=TextLabFormPreview.records(journal)
        check(records.count { it.engineId=="paddle-v6-medium" && it.scoreIsEstimated }==2)
        val engine=FillEngine(FixtureKnowledgeBase())
        batch.journals.forEach { TextLabFormPreview.append(it,engine,2026) }
        TextLabFormPreview.append(journal,engine,2026)
        check(journal.rows.count { it["stage"]=="form_summary" }==1)
        val summary=journal.rows.single { it["stage"]=="form_summary" }
        check(summary["producer"]=="Kirkland Signature" && summary["subregion"]=="Montalcino")
        check(summary["region"]=="Tuscany" && summary["vintage"]=="2021")
        check(summary["wine_name_state"]=="YELLOW")
        check(TextLabWineTable.formRows(batch.journals).size==2)
        check(batch.summary().startsWith("COMBINED SCANNING FORM PREVIEW"))
        check(batch.journals.last().rows.single { it["stage"]=="form_summary" }["status"]=="no_fields")
        val invalid=TextLabJournal(setOf("candidate_3"),"test",true)
        invalid.add("candidate_3","recognition",values=mapOf("raw_text" to "Example","base_box_normalized" to ".1 .1 .8 .2","ctc_posterior" to "NaN"))
        TextLabFormPreview.append(invalid,engine,2026)
        check(invalid.rows.last()["status"]=="failed")
        println("PASS: combined form preview uses actual scores, correct IDs, uncalibrated Paddle, per-image rows and explicit failures")
    }
}
