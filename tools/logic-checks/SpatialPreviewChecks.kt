import com.yourapp.wine.textlab.*
import com.yourapp.wine.textlab.evidence.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.fill.FillEngine

fun main() {
    var checks=0
    fun verify(ok:Boolean) { checks++;check(ok) { "Spatial preview check $checks failed" } }
    val kb=FixtureKnowledgeBase()
    val extractor=TextLabEvidencePreview.engine(kb,true)
    val hybrid=EvidenceAnchorHybrid(kb);val repair=ProducerRepair(kb)
    fun sample():TextLabJournal {
        val j=TextLabJournal(setOf("candidate_3"),"host-check",true)
        for(candidate in listOf("mlkit_reference","candidate_3")) {
            j.add(candidate,"recognition",values=mapOf("raw_text" to "Domaine de la","base_box_normalized" to "0.0 0.1 0.4 0.2",
                "original_box_normalized" to "0.0 0.1 0.4 0.2","mlkit_confidence" to "0.9","ctc_posterior" to "0.8","confidence_estimated" to "false"))
            j.add(candidate,"recognition",values=mapOf("raw_text" to "Romanée-Conti","base_box_normalized" to "0.41 0.1 0.8 0.2",
                "original_box_normalized" to "0.41 0.1 0.8 0.2","mlkit_confidence" to "0.9","ctc_posterior" to "0.8","confidence_estimated" to "false"))
            j.add(candidate,if(candidate=="mlkit_reference") "summary" else "candidate_summary","complete")
        }
        TextLabFormPreview.append(j,FillEngine(kb),2026)
        return j
    }
    val j=sample();val before=j.rows.toList()
    TextLabSpatialPreview.append(j,extractor,hybrid,repair,2026)
    verify(j.rows.take(before.size)==before)
    verify(j.rows.none { it["stage"]=="evidence_summary" }) // v2 contender never executed
    val summary=j.rows.single { it["stage"]=="spatial_evidence_summary" }
    verify(summary["status"]!="failed")
    verify(summary["spatial_source_count"]=="4" && summary["spatial_reading_count"]=="4")
    verify(summary["spatial_merged_groups"]=="2")
    verify(j.rows.count { it["stage"]=="recognition" }==4)
    verify(j.rows.filter { it["stage"]=="spatial_evidence_observation" }.any { it["raw_text"]=="Romanee-Conti" }) // originals survive; joins are producer alternatives, not replacements
    verify(j.rows.filter { it["stage"]=="spatial_phrase" }.all { it["source_scores"].orEmpty().isNotBlank() && it["source_original_boxes"].orEmpty().isNotBlank() })
    verify(summary["producer_gazetteer_status"]=="unavailable")
    val withProducer=sample()
    // Simulate a missing initial producer; fixture index exercises actual final-summary wiring.
    val anchorIndex=withProducer.rows.indexOfFirst { it["stage"]=="form_summary" }
    withProducer.rows[anchorIndex]=withProducer.rows[anchorIndex]+mapOf("producer" to "","producer_state" to "RED")
    val fixtureIndex=object:com.yourapp.wine.gazetteer.ProducerResolver.Index {
        override val version="integration-fixture";override val primaryVerified=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(
            com.yourapp.wine.gazetteer.ProducerResolver.Entity("fixture-estate","Domaine de la Romanée-Conti","domaine de la romanee conti"))
    }
    TextLabSpatialPreview.append(withProducer,extractor,hybrid,repair,2026,fixtureIndex)
    val produced=withProducer.rows.single { it["stage"]=="spatial_evidence_summary" }
    verify(produced["producer"]=="Domaine de la Romanee-Conti")
    verify(produced["producer_state"]=="YELLOW")
    verify(produced["funnel.producer.independent.status"]=="PASSED")
    verify(withProducer.rows.count { it["stage"]=="recognition" }==4)
    verify(withProducer.rows.any { it["stage"]=="producer_gazetteer_candidate" && it["source_observation_ids"].orEmpty().isNotBlank() })
    verify(listOf("wine_name","country","region","subregion","vintage").all { produced.containsKey(it) && summary.containsKey(it) && produced[it]==summary[it] })
    val withWine=TextLabJournal(setOf("candidate_3"),"linked-host-check",true)
    for(candidate in listOf("mlkit_reference","candidate_3")) {
        for((text,box) in listOf("Domaine Example" to "0.1 0.1 0.8 0.2", "Moonlight Cuvee" to "0.1 0.5 0.8 0.6"))
            withWine.add(candidate,"recognition",values=mapOf("raw_text" to text,"base_box_normalized" to box,"original_box_normalized" to box,
                "mlkit_confidence" to "0.9","ctc_posterior" to "0.9","confidence_estimated" to "false"))
        withWine.add(candidate,if(candidate=="mlkit_reference") "summary" else "candidate_summary","complete")
    }
    TextLabFormPreview.append(withWine,FillEngine(kb),2026)
    val wineAnchor=withWine.rows.indexOfFirst { it["stage"]=="form_summary" }
    withWine.rows[wineAnchor]=withWine.rows[wineAnchor]+mapOf("producer" to "Domaine Example","producer_state" to "YELLOW")
    val wineIndex=object:com.yourapp.wine.gazetteer.LinkedWineResolver.Index {
        override val version="linked-integration-fixture";override val primaryVerified=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(com.yourapp.wine.gazetteer.ProducerResolver.Entity("fixture-estate","Domaine Example","domaine example"))
        override fun lookupWines(key:String,producerIds:Set<String>,limit:Int,checkBudget:()->Unit)=listOf(
            com.yourapp.wine.gazetteer.LinkedWineResolver.Wine("fixture-wine","Moonlight Cuvee","moonlight cuvee","fixture-estate","[1234567]"))
    }
    TextLabSpatialPreview.append(withWine,extractor,hybrid,repair,2026,wineIndex)
    val wineSummary=withWine.rows.single { it["stage"]=="spatial_evidence_summary" }
    verify(wineSummary["wine_gazetteer_status"]=="supported_pair")
    verify(wineSummary["funnel.wine.independent.status"]=="PASSED")
    verify(wineSummary["wine_name"]=="Moonlight Cuvee" && wineSummary["wine_name_state"]=="YELLOW")
    verify(wineSummary["wine_gazetteer_lwin7"]=="[1234567]")
    verify(withWine.rows.any { it["stage"]=="linked_wine_candidate" && it["source_observation_ids"].orEmpty().isNotBlank() })
    verify(withWine.rows.count { it["stage"]=="recognition" }==4)
    val lookupFailure=sample()
    val brokenIndex=object:com.yourapp.wine.gazetteer.ProducerResolver.Index by fixtureIndex {
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit):List<com.yourapp.wine.gazetteer.ProducerResolver.Entity> = error("fixture lookup failure")
    }
    TextLabSpatialPreview.append(lookupFailure,extractor,hybrid,repair,2026,brokenIndex)
    val failureSummary=lookupFailure.rows.single { it["stage"]=="spatial_evidence_summary" }
    verify(failureSummary["funnel.producer.mlkit.eligible.status"]=="PASSED" && failureSummary["funnel.producer.mlkit.lookup.status"]=="BLOCKED")
    verify(failureSummary["funnel.producer.execution.status"]=="BLOCKED" && failureSummary["producer"]==failureSummary["producer_before_gazetteer"])
    val count=j.rows.size;TextLabSpatialPreview.append(j,extractor,hybrid,repair,2026);verify(j.rows.size==count)
    val failed=sample();TextLabSpatialPreview.append(failed,null,hybrid,repair,2026)
    verify(failed.rows.single { it["stage"]=="spatial_evidence_summary" }["status"]=="failed")
    val v2=sample();TextLabEvidencePreview.append(v2,extractor,2026,true,hybrid,true,repair)
    verify(v2.rows.none { it["stage"].orEmpty().startsWith("spatial_") })
    verify(v2.rows.single { it["stage"]=="evidence_summary" }["status"]!="failed")
    println("PASS: $checks actual Kotlin spatial-to-guarded extraction integration checks (host geometry/clock, not Android OCR).")
}
