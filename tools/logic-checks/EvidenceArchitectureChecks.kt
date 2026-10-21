package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.*
import com.yourapp.wine.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase

object EvidenceArchitectureChecks {
    fun run() {
        val kb=FixtureKnowledgeBase()
        val engine=EvidenceExtractor(kb)
        val records=listOf(LogicChecks.token("ALTOSde",.9f,y=.15f),LogicChecks.token("VALDOSO",.9f,y=.25f),
            LogicChecks.token("CRIANZA",.9f,y=.45f),LogicChecks.token("2019",.9f,y=.8f))
        val graph=EvidenceGraphBuilder.build(records)
        check(graph.observations.map { it.record.textRaw }==records.map { it.textRaw })
        check(graph.phrases.any { it.text=="ALTOSde" } && graph.phrases.any { it.text=="ALTOS de" })
        check(graph.observations.map { it.record.score }==records.map { it.score })
        val fill=engine.extract(records,2026).fill
        check(fill.fields.values.none { it.state==UiState.GREEN })
        check(fill.fields.getValue(FieldId.VINTAGE).value=="2019")
        val empty=engine.extract(emptyList(),2026)
        check(empty.fill.fields.values.all { it.value.isBlank() })
        check(engine.extract(listOf(LogicChecks.token("019",.99f)),2026).fill.fields.getValue(FieldId.VINTAGE).value.isBlank())
        val duplicates=List(200) { LogicChecks.token("Unknown",.7f) }
        val bounded=EvidenceGraphBuilder.build(duplicates)
        check(bounded.truncated && bounded.observations.size<=128 && bounded.phrases.size<=384)
        val repeated=engine.extract(listOf(records.last(),records.last().copy(variantId="V1")),2026)
        check(repeated.hypotheses.filter { FieldId.VINTAGE in it.values }.all { it.rank==55.0 })
        check(runCatching { engine.extract(records,2026,0) }.isFailure)
        val baseline=FillEngine(kb).fill(records,2026).fields.mapValues { it.value.value }
        engine.extract(records,2026)
        check(FillEngine(kb).fill(records,2026).fields.mapValues { it.value.value }==baseline)
        val journal=TextLabJournal(setOf("candidate_3"),"test",true)
        journal.add("candidate_3","recognition",values=mapOf("raw_text" to "2019","ctc_posterior" to ".9","base_box_normalized" to ".1 .1 .8 .2"))
        TextLabFormPreview.append(journal,FillEngine(kb),2026)
        val original=journal.rows.single { it["stage"]=="form_summary" }.toMap()
        TextLabEvidencePreview.append(journal,engine,2026)
        TextLabEvidencePreview.append(journal,engine,2026)
        check(journal.rows.count { it["stage"]=="evidence_summary" }==1)
        check(journal.rows.single { it["stage"]=="form_summary" }==original)
        check(TextLabWineTable.formRows(listOf(journal)).size==2)
        val singleLine=TextLabJournal(setOf("candidate_3"),"test",false)
        TextLabEvidencePreview.append(singleLine,engine,2026)
        check(singleLine.rows.last()["status"]=="failed")
        check(!TextLabBatch(emptyList(),emptySet(),"test",true).compareEvidence)
        println("PASS: independent experimental graph, raw observations, bounded alternatives, no score inflation, unchanged baseline, opt-in comparison, failure isolation")
    }
}
