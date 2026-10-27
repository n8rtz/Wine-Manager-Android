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

    // Lab v4 bottle-column & medal-sticker filter + Prod v3.0 isolation + tiny-footprint zoom-crop checks.
    val noisy=TextLabJournal(setOf("candidate_3"),"bottle-filter-check",true)
    val noisyRows=listOf(
        Triple("candidate_3","2019","0.45 0.08 0.55 0.12"), // neck-band vintage (must survive)
        Triple("mlkit_reference","CHATEAU EXEMPLE","0.30 0.36 0.70 0.42"),
        Triple("candidate_3","SAINT-EMILION GRAND CRU","0.27 0.45 0.73 0.50"),
        Triple("candidate_3","20","0.29 0.52 0.35 0.55"), // split crest year left half (must survive)
        Triple("candidate_3","16","0.65 0.52 0.71 0.55"), // split crest year right half (must survive)
        Triple("candidate_3","MIS EN BOUTEILLE AU CHATEAU","0.29 0.59 0.71 0.63"),
        Triple("candidate_3","zxq","0.66 0.53 0.75 0.58"), // lowercase margin signature scribble (dropped in Lab)
        Triple("mlkit_reference","Decanter","0.58 0.24 0.65 0.26"), // critic award sticker (dropped in Lab)
        Triple("mlkit_reference","95","0.59 0.26 0.64 0.30"), // near medal seal score fragment (dropped in Lab)
        Triple("candidate_3","BADGE","0.60 0.78 0.68 0.81"), // detached lower round sticker (dropped in Lab)
        Triple("mlkit_reference","SIDE BOTTLE","0.01 0.40 0.14 0.46"), // neighboring bottle (dropped in Lab)
        Triple("mlkit_reference","WATERMARK TEXT","0.01 0.98 0.28 0.995"), // border watermark (dropped in Lab)
    )
    for((cand,txt,box) in noisyRows) {
        noisy.add(cand,"recognition",values=mapOf("raw_text" to txt,"base_box_normalized" to box,"original_box_normalized" to box,
            "mlkit_confidence" to "0.9","ctc_posterior" to "0.9","confidence_estimated" to "false"))
    }
    noisy.add("mlkit_reference","summary","complete")
    noisy.add("candidate_3","candidate_summary","complete")
    TextLabFormPreview.append(noisy,FillEngine(kb),2026)
    // Prod v3.0 keeps all 12 readings untouched; Lab v4 drops the 6 noise boxes and keeps all 6 bottle readings.
    TextLabSpatialPreview.append(noisy,extractor,hybrid,repair,2026,candidate="spatial_lwin_lab",stagePrefix="spatial_lwin_",
        policy=com.yourapp.wine.gazetteer.ProducerResolver.Policy.prod)
    TextLabSpatialPreview.append(noisy,extractor,hybrid,repair,2026,candidate="spatial_lab",stagePrefix="spatial_",
        policy=com.yourapp.wine.gazetteer.ProducerResolver.Policy.singleReading)
    val lwinSummary=noisy.rows.single { it["stage"]=="spatial_lwin_evidence_summary" }
    val labSummary=noisy.rows.single { it["stage"]=="spatial_evidence_summary" }
    verify(lwinSummary["spatial_source_count"]=="12" && lwinSummary["spatial_filter_dropped"]=="0")
    verify(labSummary["spatial_source_count"]=="6" && labSummary["spatial_filter_dropped"]=="6")
    val labObs=noisy.rows.filter { it["stage"]=="spatial_evidence_observation" }.map { it["raw_text"] }.toSet()
    verify("2019" in labObs && "20" in labObs && "16" in labObs && "CHATEAU EXEMPLE" in labObs)
    verify("zxq" !in labObs && "Decanter" !in labObs && "95" !in labObs && "BADGE" !in labObs && "SIDE BOTTLE" !in labObs)
    verify(TextLabBottleFilter.proposeZoomCrop(noisy)==null) // normal close-up footprint (>10%) does not zoom-crop

    val tiny=TextLabJournal(setOf("candidate_3"),"tiny-zoom-check",true)
    for((cand,txt,box) in listOf(
        Triple("mlkit_reference","BAROLO","0.42 0.42 0.58 0.46"),
        Triple("candidate_3","ESTATE","0.40 0.48 0.60 0.52"),
        Triple("candidate_3","2019","0.47 0.55 0.53 0.58"),
        Triple("mlkit_reference","DOCG","0.41 0.61 0.59 0.65"),
    )) {
        tiny.add(cand,"recognition",values=mapOf("raw_text" to txt,"base_box_normalized" to box,"original_box_normalized" to box,
            "mlkit_confidence" to "0.9","ctc_posterior" to "0.9","confidence_estimated" to "false"))
    }
    tiny.add("mlkit_reference","summary","complete")
    tiny.add("candidate_3","candidate_summary","complete")
    val zoomCrop=TextLabBottleFilter.proposeZoomCrop(tiny)
    verify(zoomCrop!=null && zoomCrop.top<=0.25f && zoomCrop.bottom>=0.75f && (zoomCrop.right-zoomCrop.left)*(zoomCrop.bottom-zoomCrop.top)<=0.40f)

    // Lab v4 d=0 geography fast-path + ancestral chain + country-context feed into ProducerResolver (Prod v3.0 untouched).
    val d0Geo=TextLabJournal(setOf("candidate_3"),"d0-geo-context-check",true)
    for((cand,txt,box,score) in listOf(
        listOf("mlkit_reference","Domaine Allary","0.25 0.20 0.75 0.27","0.9"),
        listOf("candidate_3","Domaine Allary","0.25 0.20 0.75 0.27","0.9"),
        listOf("mlkit_reference","Haut-Brion","0.30 0.42 0.70 0.48","0.9"),
        listOf("candidate_3","Haut-Brion","0.30 0.42 0.70 0.48","0.9"),
        listOf("mlkit_reference","PESSAC-LEOGNAN","0.30 0.68 0.70 0.74","0.56"),
        listOf("candidate_3","PESSAGILEOGNA","0.30 0.68 0.70 0.74","0.87"),
        listOf("mlkit_reference","2009","0.44 0.82 0.56 0.87","0.9"),
    )) {
        d0Geo.add(cand,"recognition",values=mapOf("raw_text" to txt,"base_box_normalized" to box,"original_box_normalized" to box,
            "mlkit_confidence" to score,"ctc_posterior" to score,"confidence_estimated" to "false"))
    }
    d0Geo.add("mlkit_reference","summary","complete")
    d0Geo.add("candidate_3","candidate_summary","complete")
    TextLabFormPreview.append(d0Geo,FillEngine(kb),2026)
    val homonymIndex=object:com.yourapp.wine.gazetteer.ProducerResolver.Index {
        override val version="d0-geo-fixture";override val primaryVerified=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(
            com.yourapp.wine.gazetteer.ProducerResolver.Entity("fr-alary","Domaine Alary","domaine alary",countryGroup="France"),
            com.yourapp.wine.gazetteer.ProducerResolver.Entity("es-alary","Domaine Alary","domaine alary",countryGroup="Spain"),
        )
    }
    TextLabSpatialPreview.append(d0Geo,extractor,hybrid,repair,2026,homonymIndex,candidate="spatial_lwin_lab",
        stagePrefix="spatial_lwin_",policy=com.yourapp.wine.gazetteer.ProducerResolver.Policy.prod)
    TextLabSpatialPreview.append(d0Geo,extractor,hybrid,repair,2026,homonymIndex,candidate="spatial_lab",
        stagePrefix="spatial_",policy=com.yourapp.wine.gazetteer.ProducerResolver.Policy.singleReading)
    val d0Prod=d0Geo.rows.single { it["stage"]=="spatial_lwin_evidence_summary" }
    val d0Lab=d0Geo.rows.single { it["stage"]=="spatial_evidence_summary" }
    verify(d0Prod["region"].orEmpty().isBlank() && d0Prod["subregion"].orEmpty().isBlank())
    verify(d0Lab["country"]=="France" && d0Lab["region"]=="Bordeaux" && d0Lab["subregion"]=="Pessac-Leognan")
    verify(d0Lab["wine_name"]=="Haut-Brion" && d0Lab["producer"]=="Domaine Alary" && d0Lab["producer_gazetteer_entity_id"]=="fr-alary")
    println("PASS: $checks actual Kotlin spatial-to-guarded extraction integration checks (host geometry/clock, not Android OCR).")
}
