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
        check(journal.rows.filter { it["stage"] in setOf("evidence_observation","evidence_phrase","evidence_hypothesis") }.all { !it["evidence_details"].isNullOrBlank() })
        fun t(text:String,y:Float,engineId:String="mlkit")=LogicChecks.token(text,.9f,engine=engineId,y=y,h=.1f)
        val ownerGrower="PROPRIÉTAIRE - RÉCOLTANT DES RÉCOLTAN"
        check(EvidenceIdentityRoles.ownerGrowerLegalLine(ownerGrower))
        check(!EvidenceIdentityRoles.ownerGrowerLegalLine("Domaine du Propriétaire-Récoltant"))
        val legalFooterLabel=engine.extract(listOf(t("CHÂTEAU PEYROUQUET",.08f),t("SAINT-ÉMILION",.35f),
            t("2020",.56f),t(ownerGrower,.84f)),2026).fill
        check(legalFooterLabel.fields.getValue(FieldId.WINE_NAME).value=="SAINT-ÉMILION") { legalFooterLabel.fields }
        // Unrelated names test structure without bottle-name lookups or spelling fixtures.
        val novel=engine.extract(listOf(t("COLINASde",.1f),t("LUNAVISTA",.23f),t("CRIANZA",.4f)),2026).fill
        check(com.yourapp.wine.merge.TextNorm.normalize(novel.fields.getValue(FieldId.PRODUCER).value)=="colinas de lunavista") { novel.fields }
        check(novel.fields.getValue(FieldId.WINE_NAME).value=="Crianza") { novel.fields }
        val variant=engine.extract(listOf(t("COLINAS de",.1f),t("LUNAVISTA",.23f),t("Crianza",.4f),t("CRIANZA",.4f,"paddle-v6-medium")),2026).fill
        check(variant.fields.getValue(FieldId.WINE_NAME).value.equals("Crianza",true))
        val partial=EvidenceGraphBuilder.build(listOf(t("COLINASde",.1f),t("COLINAS de",.1f,"paddle-v6-medium")))
        check(partial.phrases.none { it.observations.size>1 }) // Same location is not two consecutive phrases.
        val novelReserve=engine.extract(listOf(t("BLACKSTONE",.02f),t("LUNAVISTA",.25f),t("GRAN RESERVA",.39f)),2026).fill
        check(novelReserve.fields.getValue(FieldId.WINE_NAME).value.equals("LUNAVISTA Gran Reserva",true)) { novelReserve.fields }
        val percentage=engine.extract(listOf(t("BLACKSTONE",.02f),t("100% TINTA DEL PAIS",.25f),t("CRIANZA",.39f)),2026).fill
        check(percentage.fields.getValue(FieldId.WINE_NAME).value=="Crianza")
        val partialOrigin=engine.extract(listOf(t("COTES DU RHONE VII",.2f)),2026).fill
        check(partialOrigin.fields.getValue(FieldId.COUNTRY).value=="France")
        check(partialOrigin.fields.getValue(FieldId.REGION).value=="Rhône")
        check(partialOrigin.fields.getValue(FieldId.SUBREGION).value.isBlank())
        val countrySuffix=engine.extract(listOf(t("Rueda-España",.2f)),2026).fill
        check(countrySuffix.fields.getValue(FieldId.SUBREGION).value=="Rueda")
        val suffixNegatives=listOf("Côtes du Rhône","Domaine Côtes du Rhône Vilas","Côtes du Rhône Violet")
        for(text in suffixNegatives) {
            val g=EvidenceLexicon.expand(EvidenceGraphBuilder.build(listOf(t(text,.2f)))) {}
            check(g.phrases.none { it.text=="Côtes du Rhône Villages" })
        }
        // No independent producer-tail correction from variants of a single engine.
        val singleEngine=EvidenceLexicon.expand(EvidenceGraphBuilder.build(listOf(t("UIGNERONS de 'Eclave",.8f),
            t("L'Enclave",.2f),t("L'Enclaue",.2f)))) {}
        check(singleEngine.phrases.none { it.text=="Vignerons de L'Enclave" })
        val unrelatedLocations=EvidenceLexicon.expand(EvidenceGraphBuilder.build(listOf(t("UIGNERONS de 'Eclave",.8f),
            t("L'Enclave",.2f),t("L'Enclaue",.5f,"paddle-v6-medium")))) {}
        check(unrelatedLocations.phrases.none { it.text=="Vignerons de L'Enclave" })
        println("PASS: independent experimental graph, raw observations, bounded alternatives, no score inflation, unchanged baseline, opt-in comparison, failure isolation")
    }
}
