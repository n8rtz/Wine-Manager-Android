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
    verify(summary["spatial_source_count"]=="4" && summary["spatial_reading_count"]=="2")
    verify(summary["spatial_merged_groups"]=="2")
    verify(j.rows.count { it["stage"]=="recognition" }==4)
    verify(j.rows.filter { it["stage"]=="spatial_evidence_observation" }.any { it["raw_text"]=="Domaine de la Romanée-Conti" }) // actual extractor input, not just audit text
    verify(j.rows.filter { it["stage"]=="spatial_phrase" }.all { it["source_scores"].orEmpty().isNotBlank() && it["source_original_boxes"].orEmpty().isNotBlank() })
    val count=j.rows.size;TextLabSpatialPreview.append(j,extractor,hybrid,repair,2026);verify(j.rows.size==count)
    val failed=sample();TextLabSpatialPreview.append(failed,null,hybrid,repair,2026)
    verify(failed.rows.single { it["stage"]=="spatial_evidence_summary" }["status"]=="failed")
    val v2=sample();TextLabEvidencePreview.append(v2,extractor,2026,true,hybrid,true,repair)
    verify(v2.rows.none { it["stage"].orEmpty().startsWith("spatial_") })
    verify(v2.rows.single { it["stage"]=="evidence_summary" }["status"]!="failed")
    println("PASS: $checks actual Kotlin spatial-to-guarded extraction integration checks (host geometry/clock, not Android OCR).")
}
