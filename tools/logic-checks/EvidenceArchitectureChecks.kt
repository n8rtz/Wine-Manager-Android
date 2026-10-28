package com.yourapp.wine.textlab

import com.yourapp.wine.textlab.evidence.*
import com.yourapp.wine.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.extract.WineDesignations
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.kb.SupplementedWineKnowledgeBase

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
        check(legalFooterLabel.fields.getValue(FieldId.WINE_NAME).value=="SAINT-EMILION") { legalFooterLabel.fields }
        // Unrelated names test structure without bottle-name lookups or spelling fixtures.
        val novel=engine.extract(listOf(t("COLINASde",.1f),t("LUNAVISTA",.23f),t("CRIANZA",.4f)),2026).fill
        check(com.yourapp.wine.merge.TextNorm.normalize(novel.fields.getValue(FieldId.PRODUCER).value)=="colinas de lunavista") { novel.fields }
        check(novel.fields.getValue(FieldId.WINE_NAME).value=="Crianza") { novel.fields }
        val variant=engine.extract(listOf(t("COLINAS de",.1f),t("LUNAVISTA",.23f),t("Crianza",.4f),t("CRIANZA",.4f,"paddle-v6-medium")),2026).fill
        check(variant.fields.getValue(FieldId.WINE_NAME).value.equals("Crianza",true))
        val partial=EvidenceGraphBuilder.build(listOf(t("COLINASde",.1f),t("COLINAS de",.1f,"paddle-v6-medium")))
        check(partial.phrases.none { it.observations.size>1 }) // Same location is not two consecutive phrases.
        check(engine.isBroadGeographyLine("Willamette Valley"))
        check(!engine.isBroadGeographyLine("Chianti Classico")) // Catalog appellation, not broad geography.
        val defaultNoTitlePair=engine.extract(listOf(t("Inscription",.2f),t("Pinot Noir",.3f)),2026)
        check(defaultNoTitlePair.graph.phrases.none { it.construction=="bounded vertical wine-title pair" })
        val titlePairEngine=EvidenceExtractor(FixtureKnowledgeBase(),allowWineTitlePairs=true)
        val inscriptionPinot=titlePairEngine.extract(listOf(t("Inscription",.2f),t("Pinot Noir",.3f)),2026)
        check(inscriptionPinot.graph.phrases.any { it.construction=="bounded vertical wine-title pair" && it.text=="Inscription Pinot Noir" })
        check(com.yourapp.wine.merge.TextNorm.normalize(inscriptionPinot.fill.fields.getValue(FieldId.WINE_NAME).value)=="inscription pinot noir") {
            inscriptionPinot.fill.fields
        }
        val peppoliChianti=titlePairEngine.extract(listOf(t("Peppoli",.2f),t("Chianti Classico",.3f)),2026)
        check(peppoliChianti.graph.phrases.any { it.construction=="bounded vertical wine-title pair" && it.text=="Peppoli Chianti Classico" })
        check(com.yourapp.wine.merge.TextNorm.normalize(peppoliChianti.fill.fields.getValue(FieldId.WINE_NAME).value)=="peppoli chianti classico") {
            peppoliChianti.fill.fields
        }
        val regionBoundary=titlePairEngine.extract(listOf(t("Inscription",.2f),t("Willamette Valley",.3f)),2026)
        check(regionBoundary.graph.phrases.none { it.construction=="bounded vertical wine-title pair" || it.observations.size>1 })
        val vintageBoundary=titlePairEngine.extract(listOf(t("Inscription",.2f),t("2019",.3f)),2026)
        check(vintageBoundary.graph.phrases.none { it.construction=="bounded vertical wine-title pair" || it.observations.size>1 })
        val vintageWordBoundary=engine.extract(listOf(t("VINTAGE",.2f),t("Pinot Noir",.3f)),2026)
        check(vintageWordBoundary.graph.phrases.none { it.observations.size>1 })
        val framingBoundary=engine.extract(listOf(t("APPELLATION",.2f),t("Pinot Noir",.3f)),2026)
        check(framingBoundary.graph.phrases.none { it.observations.size>1 })
        val horizontalRegionBoundary=engine.extract(listOf(
            LogicChecks.token("Inscription",.9f,x=.1f,y=.4f,w=.22f,h=.06f),
            LogicChecks.token("Willamette Valley",.9f,x=.34f,y=.4f,w=.35f,h=.06f),
        ),2026)
        check(horizontalRegionBoundary.graph.phrases.none { it.observations.size>1 })
        val horizontalVintageBoundary=engine.extract(listOf(
            LogicChecks.token("Inscription",.9f,x=.1f,y=.4f,w=.22f,h=.06f),
            LogicChecks.token("2019",.9f,x=.34f,y=.4f,w=.12f,h=.06f),
        ),2026)
        check(horizontalVintageBoundary.graph.phrases.none { it.observations.size>1 })
        val crossEngineTitle=engine.extract(listOf(t("Inscription",.2f,"mlkit"),t("Pinot Noir",.3f,"paddle-v6-medium")),2026)
        check(crossEngineTitle.graph.phrases.none { it.observations.mapNotNull { id -> crossEngineTitle.graph.observations.firstOrNull { it.id==id }?.record?.engineId }.distinct().size>1 })
        val crossPassTitle=engine.extract(listOf(
            LogicChecks.token("Inscription",.9f,engine="mlkit",variant="V0",y=.2f,h=.1f),
            LogicChecks.token("Pinot Noir",.9f,engine="mlkit",variant="V1",y=.3f,h=.1f),
        ),2026)
        check(crossPassTitle.graph.phrases.none { it.observations.size>1 })
        val crossEngineConnector=EvidenceGraphBuilder.build(listOf(
            LogicChecks.token("Domaine",.9f,engine="mlkit",variant="V0",y=.2f,h=.1f),
            LogicChecks.token("de",.9f,engine="paddle-v6-medium",variant="V0",y=.3f,h=.1f),
            LogicChecks.token("Romanée-Conti",.9f,engine="mlkit",variant="V0",y=.4f,h=.1f),
        ),typedIdentity=true)
        check(crossEngineConnector.phrases.none { it.observations.size>1 })
        val crossPassConnector=EvidenceGraphBuilder.build(listOf(
            LogicChecks.token("Domaine",.9f,engine="mlkit",variant="V0",y=.2f,h=.1f),
            LogicChecks.token("de",.9f,engine="mlkit",variant="V1",y=.3f,h=.1f),
            LogicChecks.token("Romanée-Conti",.9f,engine="mlkit",variant="V0",y=.4f,h=.1f),
        ),typedIdentity=true)
        check(crossPassConnector.phrases.none { it.observations.size>1 })
        check(!engine.allowsSpatialProducerJoin("Domaine", "2019"))
        check(!engine.allowsSpatialProducerJoin("Domaine", "Willamette Valley"))
        check(!engine.allowsSpatialProducerJoin("Willamette", "Valley"))
        check(!engine.allowsSpatialProducerJoin("Domaine", "Pinot Noir"))
        check(engine.allowsSpatialProducerJoin("Domaine de la", "Romanée-Conti"))
        fun producerSpatialJoin(a:String,b:String)=engine.allowsSpatialProducerJoin(a,b)
        val horizontalWineTitle=listOf(
            LogicChecks.token("Inscription",.9f,engine="mlkit",x=.1f,y=.4f,w=.22f,h=.06f),
            LogicChecks.token("Pinot Noir",.9f,engine="mlkit",x=.34f,y=.4f,w=.25f,h=.06f),
        )
        val horizontalTitleProposals=TextLabSpatialPhrases.propose(horizontalWineTitle,
            allowHorizontalJoin={ left,right -> producerSpatialJoin(left,right) },
            allowVerticalJoin={ upper,lower -> producerSpatialJoin(upper,lower) })
        check(horizontalTitleProposals.none { it.phrase.sourceIndices.size>1 })
        val horizontalProducer=listOf(
            LogicChecks.token("Domaine",.9f,engine="mlkit",x=.1f,y=.5f,w=.16f,h=.06f),
            LogicChecks.token("de",.9f,engine="mlkit",x=.27f,y=.5f,w=.04f,h=.06f),
            LogicChecks.token("la",.9f,engine="mlkit",x=.32f,y=.5f,w=.03f,h=.06f),
            LogicChecks.token("Romanée-Conti",.9f,engine="mlkit",x=.36f,y=.5f,w=.35f,h=.06f),
        )
        val horizontalProducerProposals=TextLabSpatialPhrases.propose(horizontalProducer,
            allowHorizontalJoin={ left,right -> producerSpatialJoin(left,right) },
            allowVerticalJoin={ upper,lower -> producerSpatialJoin(upper,lower) })
        check(horizontalProducerProposals.any { it.phrase.mergedText=="Domaine de la Romanée-Conti" })
        check(horizontalProducerProposals.flatMap { it.sources }.toSet()==horizontalProducer.toSet())
        val novelReserve=engine.extract(listOf(t("BLACKSTONE",.02f),t("LUNAVISTA",.25f),t("GRAN RESERVA",.39f)),2026).fill
        check(novelReserve.fields.getValue(FieldId.WINE_NAME).value.equals("LUNAVISTA Gran Reserva",true)) { novelReserve.fields }
        val percentage=engine.extract(listOf(t("BLACKSTONE",.02f),t("100% TINTA DEL PAIS",.25f),t("CRIANZA",.39f)),2026).fill
        check(percentage.fields.getValue(FieldId.WINE_NAME).value=="Crianza")
        val partialOrigin=engine.extract(listOf(t("COTES DU RHONE VII",.2f)),2026).fill
        check(partialOrigin.fields.getValue(FieldId.COUNTRY).value=="France")
        check(partialOrigin.fields.getValue(FieldId.REGION).value=="Rhone")
        check(partialOrigin.fields.getValue(FieldId.SUBREGION).value.isBlank())
        val countrySuffix=engine.extract(listOf(t("Rueda-España",.2f)),2026).fill
        check(countrySuffix.fields.getValue(FieldId.SUBREGION).value=="Rueda")
        val suffixNegatives=listOf("Côtes du Rhône","Domaine Côtes du Rhône Vilas","Côtes du Rhône Violet")
        for(text in suffixNegatives) {
            val g=EvidenceLexicon.expand(EvidenceGraphBuilder.build(listOf(t(text,.2f)))) {}
            check(g.phrases.none { it.text=="Cotes du Rhone Villages" })
        }
        // No independent producer-tail correction from variants of a single engine.
        val singleEngine=EvidenceLexicon.expand(EvidenceGraphBuilder.build(listOf(t("UIGNERONS de 'Eclave",.8f),
            t("L'Enclave",.2f),t("L'Enclaue",.2f)))) {}
        check(singleEngine.phrases.none { it.text=="Vignerons de L'Enclave" })
        val unrelatedLocations=EvidenceLexicon.expand(EvidenceGraphBuilder.build(listOf(t("UIGNERONS de 'Eclave",.8f),
            t("L'Enclave",.2f),t("L'Enclaue",.5f,"paddle-v6-medium")))) {}
        check(unrelatedLocations.phrases.none { it.text=="Vignerons de L'Enclave" })
        designationAndLevelRules()
        println("PASS: independent experimental graph, raw observations, bounded alternatives, no score inflation, unchanged baseline, opt-in comparison, failure isolation, designation levels")
    }

    /**
     * Owner benchmark cases (2026-10-27): a classification-qualified appellation keeps the base
     * place, a bare designation is never a wine name, one name never fills two levels, and
     * zone-forming suffixes such as `Chianti Classico` keep their own identity.
     */
    private fun designationAndLevelRules() {
        check(WineDesignations.classificationBase("Saint-Émilion Grand Cru")=="Saint-Emilion")
        check(WineDesignations.classificationBase("Bordeaux Supérieur")=="Bordeaux")
        check(WineDesignations.classificationBase("BORDEAUX SUPERIEUR")=="BORDEAUX")
        check(WineDesignations.classificationBase("Médoc Supérieur")=="Medoc")
        check(WineDesignations.classificationBase("Graves Supérieures")=="Graves")
        for(text in listOf("Chianti Classico","Soave Superiore","Beaujolais Villages","Barolo Riserva","Amarone della Valpolicella"))
            check(WineDesignations.classificationBase(text)==null) { text }
        val kb=SupplementedWineKnowledgeBase(FixtureKnowledgeBase())
        fun line(text:String,y:Float,engine:String="mlkit")=LogicChecks.token(text,.9f,engine=engine,y=y,h=.1f)
        // A designation on its own qualifies a name and never becomes one.
        val bare=EvidenceExtractor(kb).extract(listOf(line("GRAND CRU",.2f),line("2019",.8f)),2026).fill
        check(bare.fields.getValue(FieldId.WINE_NAME).value.isBlank()) { bare.fields }
        val bareDesignation=EvidenceExtractor(kb).extract(listOf(line("DOCG",.2f),line("2019",.8f)),2026).fill
        check(bareDesignation.fields.getValue(FieldId.WINE_NAME).value.isBlank()) { bareDesignation.fields }
        // One name may not fill the region and the subregion at once, and the classification-qualified
        // wine name must not ownership-conflict with its own stripped base place in EvidenceSolver.
        val superior=EvidenceExtractor(kb).extract(listOf(line("BORDEAUX SUPÉRIEUR",.3f),line("PRODUCE OF FRANCE",.6f),
            line("2016",.8f)),2026).fill
        check(superior.fields.getValue(FieldId.COUNTRY).value=="France") { superior.fields }
        check(superior.fields.getValue(FieldId.REGION).value=="Bordeaux") { superior.fields }
        check(superior.fields.getValue(FieldId.SUBREGION).value.isBlank()) { superior.fields }
        check(superior.fields.getValue(FieldId.WINE_NAME).value=="BORDEAUX SUPERIEUR") { superior.fields }
        // The printed classification stays the wine name while the base appellation populates its ancestors,
        // both alongside a country-only line and when no country/region line is printed at all.
        val emilion=EvidenceExtractor(kb).extract(listOf(line("SAINT-ÉMILION GRAND CRU",.3f),line("PRODUCE OF FRANCE",.6f),
            line("CHÂTEAU ABELYCE",.5f,"paddle-v6-medium"),line("2010",.8f)),2026).fill
        check(emilion.fields.getValue(FieldId.COUNTRY).value=="France") { emilion.fields }
        check(emilion.fields.getValue(FieldId.REGION).value=="Bordeaux") { emilion.fields }
        check(emilion.fields.getValue(FieldId.SUBREGION).value=="Saint-Emilion") { emilion.fields }
        check(emilion.fields.getValue(FieldId.WINE_NAME).value=="SAINT-EMILION GRAND CRU") { emilion.fields }
        val framedEmilion=EvidenceExtractor(kb).extract(listOf(
            line("CHATEAU ABELYCE",.2f,"paddle-v6-medium"),
            line("APPELLATION SAINT-EMILION GRAND CRU CONTROLEE",.5f),
            line("MIS EN BOUTEILLE AU CHATEAU",.7f),
            line("2015",.85f)),2026).fill
        check(framedEmilion.fields.getValue(FieldId.COUNTRY).value=="France") { framedEmilion.fields }
        check(framedEmilion.fields.getValue(FieldId.REGION).value=="Bordeaux") { framedEmilion.fields }
        check(framedEmilion.fields.getValue(FieldId.SUBREGION).value=="Saint-Emilion") { framedEmilion.fields }
        check(framedEmilion.fields.getValue(FieldId.WINE_NAME).value=="SAINT-EMILION GRAND CRU") { framedEmilion.fields }
        val contextGraves=EvidenceExtractor(kb).extract(listOf(
            line("CHATEAU CARBONEAU",.2f,"paddle-v6-medium"),
            line("GRAVES",.45f),
            line("MIS EN BOUTEILLE AU CHATEAU",.7f),
            line("2019",.85f)),2026).fill
        check(contextGraves.fields.getValue(FieldId.COUNTRY).value=="France") { contextGraves.fields }
        check(contextGraves.fields.getValue(FieldId.REGION).value=="Bordeaux") { contextGraves.fields }
        check(contextGraves.fields.getValue(FieldId.SUBREGION).value=="Graves") { contextGraves.fields }
        val saintJulienOnly=EvidenceExtractor(kb).extract(listOf(line("CHÂTEAU TALBOT",.2f,"paddle-v6-medium"),
            line("SAINT-JULIEN",.5f),line("2018",.8f)),2026).fill
        check(saintJulienOnly.fields.getValue(FieldId.COUNTRY).value=="France") { saintJulienOnly.fields }
        check(saintJulienOnly.fields.getValue(FieldId.REGION).value=="Bordeaux") { saintJulienOnly.fields }
        check(saintJulienOnly.fields.getValue(FieldId.SUBREGION).value=="Saint-Julien") { saintJulienOnly.fields }
        val twoItalianRegions=EvidenceExtractor(kb).extract(listOf(
            line("BAROLO",.25f),line("CHIANTI",.55f),line("2019",.85f)),2026).fill
        check(twoItalianRegions.fields.getValue(FieldId.COUNTRY).value=="Italy") { twoItalianRegions.fields }
        check(twoItalianRegions.fields.getValue(FieldId.REGION).value.isBlank()) { twoItalianRegions.fields }
        val extractor=EvidenceExtractor(kb)
        check(extractor.canonicalCountryName("Russian Federation")=="Russia")
        val baroloGeo=extractor.resolveLinkedWineGeography("Barolo","","","","Italy")
        check(baroloGeo[FieldId.COUNTRY]=="Italy" && baroloGeo[FieldId.REGION]=="Piedmont" && baroloGeo[FieldId.SUBREGION]=="Barolo") { baroloGeo.toString() }
        check(extractor.resolveLinkedWineGeography("Cuvee Speciale","","","","France").isEmpty())
        // Year-attached classification lines (#7, #115, #140) detach the vintage without losing subregion or wine name.
        val emilionYear=extractor.extract(listOf(
            line("Château Améliss",.2f),
            line("SAINT-ÉMILION GRAND CRU 2016",.45f),
            line("Bordeaux-Produit de France",.75f)),2026).fill
        check(emilionYear.fields.getValue(FieldId.SUBREGION).value=="Saint-Emilion") { emilionYear.fields }
        check(emilionYear.fields.getValue(FieldId.WINE_NAME).value=="SAINT-EMILION GRAND CRU") { emilionYear.fields }
        check(emilionYear.fields.getValue(FieldId.VINTAGE).value=="2016") { emilionYear.fields }
        // Bare legal classification codes (`DOCG`, `AVA`, `AOC`) never become wine names (#173).
        check(WineDesignations.isBareClassification("DOCG"))
        val barbarescoDocg=extractor.extract(listOf(
            line("CIABOT",.15f),line("BARBARESCO",.35f),line("DOCG",.55f),line("Baronetto",.75f)),2026).fill
        check(barbarescoDocg.fields.getValue(FieldId.WINE_NAME).value!="DOCG") { barbarescoDocg.fields }
        check(barbarescoDocg.fields.getValue(FieldId.SUBREGION).value=="Barbaresco") { barbarescoDocg.fields }
        // Embedded `Sicilia` in a Spanish estate name (#128) does not override Ribera del Duero; Cosecha year is read.
        val vegaSicilia=extractor.extract(listOf(
            line("VIEGAI-SICILIA",.15f),line("OOSECHA 1991 UNICO",.35f),
            line("Ribera del Duero",.55f),line("MODEGAS VEGA SICILIA, &. A",.8f)),2026).fill
        check(vegaSicilia.fields.getValue(FieldId.COUNTRY).value=="Spain") { vegaSicilia.fields }
        check(vegaSicilia.fields.getValue(FieldId.REGION).value=="Ribera del Duero") { vegaSicilia.fields }
        check(vegaSicilia.fields.getValue(FieldId.SUBREGION).value.isBlank()) { vegaSicilia.fields }
        check(vegaSicilia.fields.getValue(FieldId.VINTAGE).value=="1991") { vegaSicilia.fields }
        // Nested US AVAs (#150 St. Helena, #182 Stags Leap District) and Hautes-Cotes de Beaune (#30) resolve under their parent.
        val stHelena=extractor.extract(listOf(line("SPOTTSWOODE",.2f),line("CABERNET SAUVIGNON",.45f),line("ST.HELENA·NAPA VALLEY",.75f)),2026).fill
        check(stHelena.fields.getValue(FieldId.COUNTRY).value=="California") { stHelena.fields }
        check(stHelena.fields.getValue(FieldId.REGION).value=="Napa Valley") { stHelena.fields }
        check(stHelena.fields.getValue(FieldId.SUBREGION).value=="St. Helena") { stHelena.fields }
        val stagsLeap=extractor.extract(listOf(line("REPUTATION",.2f),line("STAGS LEAP DISTRICT",.5f),line("NAPA VALLEY",.7f)),2026).fill
        check(stagsLeap.fields.getValue(FieldId.REGION).value=="Napa Valley") { stagsLeap.fields }
        check(stagsLeap.fields.getValue(FieldId.SUBREGION).value=="Stags Leap District") { stagsLeap.fields }
        val hautesBeaune=extractor.extract(listOf(line("Maison Chandesais",.2f),line("BOURGOGNE",.45f),line("HAUTES CÔTES DE BEAUNE",.65f)),2026).fill
        check(hautesBeaune.fields.getValue(FieldId.SUBREGION).value=="Bourgogne Hautes-Cotes de Beaune") { hautesBeaune.fields }
        // French Commune - Department - France bottler headquarters (#57) does not override Chablis.
        val chablisAddress=extractor.extract(listOf(
            line("DOMAINE MARGUERITE CARILLON",.2f),line("CHABLIS",.45f),line("MEURSAULT - CÔTE D'OR - FRANCE",.8f)),2026).fill
        check(chablisAddress.fields.getValue(FieldId.REGION).value=="Burgundy") { chablisAddress.fields }
        check(chablisAddress.fields.getValue(FieldId.SUBREGION).value=="Chablis") { chablisAddress.fields }
        // Regional-wide indications (`Pays d'Oc`, `Coteaux du Languedoc`) fill Region without inventing a Subregion (#44, #129).
        val paysDoc=extractor.extract(listOf(line("ROCHE MAZET",.2f),line("CABERNET SAUVIGNON",.45f),line("PAYS D'OC",.7f)),2026).fill
        check(paysDoc.fields.getValue(FieldId.COUNTRY).value=="France") { paysDoc.fields }
        check(paysDoc.fields.getValue(FieldId.REGION).value=="Languedoc") { paysDoc.fields }
        check(paysDoc.fields.getValue(FieldId.SUBREGION).value.isBlank()) { paysDoc.fields }
        val gascogne=extractor.extract(listOf(line("SAUVIGNON BLANC",.3f),line("CÔTES DE GASCOGNE",.65f)),2026).fill
        check(gascogne.fields.getValue(FieldId.REGION).value=="South West France") { gascogne.fields }
        check(gascogne.fields.getValue(FieldId.SUBREGION).value=="Cotes de Gascogne") { gascogne.fields }
        // Lab v5 d=0 exact geography fast-path: locks coherent exact appellation/region chains, populates
        // ancestral Country/Region/Subregion, feeds LabelContext, and leaves Prod default untouched.
        val labRepairExtractor=EvidenceExtractor(kb,placeRepair=PlaceRepair.Config.experimental)
        val heritageProd=labRepairExtractor.extract(
            listOf(line("ROCHE MAZET",.15f),line("PAYS D'OC",.4f),line("HERITAGE",.6f),line("PRODUIT DE FRANCE",.8f)),
            2026,
            exactGeographyFastPath=false,
        ).fill
        check(heritageProd.fields.getValue(FieldId.REGION).value.isBlank()) { heritageProd.fields }
        val heritageLab=labRepairExtractor.extract(
            listOf(line("ROCHE MAZET",.15f),line("PAYS D'OC",.4f),line("HERITAGE",.6f),line("PRODUIT DE FRANCE",.8f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(heritageLab.fields.getValue(FieldId.COUNTRY).value=="France") { heritageLab.fields }
        check(heritageLab.fields.getValue(FieldId.REGION).value=="Languedoc") { heritageLab.fields }
        check(heritageLab.fields.getValue(FieldId.SUBREGION).value.isBlank()) { heritageLab.fields }
        val bourgogneOnlyLab=extractor.extract(
            listOf(line("ALBERT BICHOT",.2f),line("BOURGOGNE",.45f),line("CHARDONNAY",.7f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(bourgogneOnlyLab.fields.getValue(FieldId.COUNTRY).value=="France") { bourgogneOnlyLab.fields }
        check(bourgogneOnlyLab.fields.getValue(FieldId.REGION).value=="Burgundy") { bourgogneOnlyLab.fields }
        check(bourgogneOnlyLab.fields.getValue(FieldId.SUBREGION).value.isBlank()) { bourgogneOnlyLab.fields }
        val hautesBeauneLab=extractor.extract(
            listOf(line("Maison Chandesais",.2f),line("BOURGOGNE",.45f),line("HAUTES CÔTES DE BEAUNE",.65f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(hautesBeauneLab.fields.getValue(FieldId.COUNTRY).value=="France") { hautesBeauneLab.fields }
        check(hautesBeauneLab.fields.getValue(FieldId.REGION).value=="Burgundy") { hautesBeauneLab.fields }
        check(hautesBeauneLab.fields.getValue(FieldId.SUBREGION).value=="Bourgogne Hautes-Cotes de Beaune") { hautesBeauneLab.fields }
        val riojaBrandSubstringLab=extractor.extract(
            listOf(line("La RiojaAlta",.25f),line("GRAN RESERVA 904",.5f),line("RIOJA",.75f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(riojaBrandSubstringLab.fields.getValue(FieldId.COUNTRY).value=="Spain") { riojaBrandSubstringLab.fields }
        check(riojaBrandSubstringLab.fields.getValue(FieldId.REGION).value=="Rioja") { riojaBrandSubstringLab.fields }
        check(riojaBrandSubstringLab.fields.getValue(FieldId.SUBREGION).value.isBlank()) { riojaBrandSubstringLab.fields }
        val corroboratedPessacLab=extractor.extract(
            listOf(
                line("Haut-Brion",.40f),
                LogicChecks.token("PESSAC-LEOGNAN",.56f,engine="mlkit",y=.70f,h=.06f),
                LogicChecks.token("PESSAGILEOGNA",.87f,engine="paddle-v6-medium",y=.70f,h=.06f),
                line("2009",.85f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(corroboratedPessacLab.fields.getValue(FieldId.COUNTRY).value=="France") { corroboratedPessacLab.fields }
        check(corroboratedPessacLab.fields.getValue(FieldId.REGION).value=="Bordeaux") { corroboratedPessacLab.fields }
        check(corroboratedPessacLab.fields.getValue(FieldId.SUBREGION).value=="Pessac-Leognan") { corroboratedPessacLab.fields }
        check(corroboratedPessacLab.fields.getValue(FieldId.WINE_NAME).value=="Haut-Brion") { corroboratedPessacLab.fields }
        // Last-resort blank-subregion raw-OCR span search: runs ONLY when SUBREGION would otherwise
        // remain blank, across any span length / spacing / line count / glued token, without letting
        // weak OCR lines enter main hypothesis competition or clobber already-resolved fields.
        val weakSaintEmilion=labRepairExtractor.extract(
            listOf(LogicChecks.token("AINT-ÉMILION O GRAND CRU",.149f,engine="mlkit",x=.24f,y=.64f,w=.44f,h=.10f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(weakSaintEmilion.fields.getValue(FieldId.COUNTRY).value=="France") { weakSaintEmilion.fields }
        check(weakSaintEmilion.fields.getValue(FieldId.REGION).value=="Bordeaux") { weakSaintEmilion.fields }
        check(weakSaintEmilion.fields.getValue(FieldId.SUBREGION).value=="Saint-Emilion") { weakSaintEmilion.fields }
        check(weakSaintEmilion.fields.getValue(FieldId.WINE_NAME).value.isBlank()) { weakSaintEmilion.fields }
        val saintEmilionProdOff=extractor.extract(
            listOf(LogicChecks.token("aint-emilion",.149f,engine="mlkit",x=.24f,y=.64f,w=.44f,h=.10f)),
            2026,
            exactGeographyFastPath=false,
        ).fill
        check(saintEmilionProdOff.fields.getValue(FieldId.SUBREGION).value.isBlank()) { saintEmilionProdOff.fields }
        val multiLineSpanFallback=labRepairExtractor.extract(
            listOf(
                LogicChecks.token("aint",.12f,engine="mlkit",x=.24f,y=.52f,w=.20f,h=.06f),
                LogicChecks.token("emilion",.12f,engine="mlkit",x=.24f,y=.62f,w=.28f,h=.06f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(multiLineSpanFallback.fields.getValue(FieldId.SUBREGION).value=="Saint-Emilion") { multiLineSpanFallback.fields }
        val gluedTokenFallback=labRepairExtractor.extract(
            listOf(LogicChecks.token("appellationsaintemilioncontrolee",.20f,engine="paddle-v6-medium",x=.18f,y=.70f,w=.64f,h=.06f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(gluedTokenFallback.fields.getValue(FieldId.SUBREGION).value=="Saint-Emilion") { gluedTokenFallback.fields }
        val nonInterferenceWhenSubregionPresent=labRepairExtractor.extract(
            listOf(
                line("PAUILLAC",.45f),
                LogicChecks.token("aint-emilion",.20f,engine="mlkit",x=.24f,y=.75f,w=.44f,h=.06f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(nonInterferenceWhenSubregionPresent.fields.getValue(FieldId.SUBREGION).value=="Pauillac") { nonInterferenceWhenSubregionPresent.fields }
        val incompatibleRegionGuard=labRepairExtractor.extract(
            listOf(
                line("NAPA VALLEY",.45f),
                LogicChecks.token("aint-emilion",.20f,engine="mlkit",x=.24f,y=.75f,w=.44f,h=.06f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(incompatibleRegionGuard.fields.getValue(FieldId.REGION).value=="Napa Valley") { incompatibleRegionGuard.fields }
        check(incompatibleRegionGuard.fields.getValue(FieldId.SUBREGION).value.isBlank()) { incompatibleRegionGuard.fields }
        val producerSubspanGuard=labRepairExtractor.fallbackBlankSubregionFromRawOcr(
            records=listOf(line("MOULIN DE JAURE",.30f),line("BORDEAUX",.60f)),
            existingCountry="France",
            existingRegion="Bordeaux",
            existingProducer="Chateau Moulin de Jaure",
            knownProducerLineObservations=setOf("MOULIN DE JAURE"),
        )
        check(producerSubspanGuard==null) { "Producer sub-span must not invent subregion: $producerSubspanGuard" }
        val stackedCastillonLab=labRepairExtractor.extract(
            listOf(
                LogicChecks.token("CASTILLON",.95f,engine="mlkit",x=.30f,y=.54f,w=.40f,h=.07f),
                LogicChecks.token("CÔTES DE BORDEAUX",.95f,engine="mlkit",x=.22f,y=.62f,w=.56f,h=.05f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(stackedCastillonLab.fields.getValue(FieldId.SUBREGION).value=="Castillon Cotes de Bordeaux") { stackedCastillonLab.fields }
        val rescuedBordeauxSuperieurLab=labRepairExtractor.extract(
            listOf(
                LogicChecks.token("BORDEAUX SUPÉRIEUR",.55f,engine="mlkit",x=.22f,y=.62f,w=.56f,h=.06f),
                LogicChecks.token("BORDEAUX SUPERIEU",.88f,engine="paddle-v6-medium",x=.22f,y=.62f,w=.56f,h=.06f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(rescuedBordeauxSuperieurLab.fields.getValue(FieldId.REGION).value=="Bordeaux") { rescuedBordeauxSuperieurLab.fields }
        check(rescuedBordeauxSuperieurLab.fields.getValue(FieldId.SUBREGION).value.isBlank()) { rescuedBordeauxSuperieurLab.fields }
        val rescuedVintageLab=labRepairExtractor.extract(
            listOf(
                line("PAUILLAC",.45f),
                LogicChecks.token("I998",.598f,engine="mlkit",x=.42f,y=.78f,w=.16f,h=.06f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(rescuedVintageLab.fields.getValue(FieldId.VINTAGE).value=="1998") { rescuedVintageLab.fields }
        val noiseOnly=labRepairExtractor.extract(
            listOf(LogicChecks.token("CHATEAU MOUTON",.175f,engine="mlkit",x=.24f,y=.64f,w=.44f,h=.10f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(noiseOnly.fields.getValue(FieldId.COUNTRY).value.isBlank()) { noiseOnly.fields }
        check(noiseOnly.fields.getValue(FieldId.REGION).value.isBlank()) { noiseOnly.fields }
        check(noiseOnly.fields.getValue(FieldId.SUBREGION).value.isBlank()) { noiseOnly.fields }
        val contradictoryLab=extractor.extract(
            listOf(line("BAROLO",.25f),line("CHIANTI",.55f),line("2019",.85f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(contradictoryLab.fields.getValue(FieldId.COUNTRY).value=="Italy") { contradictoryLab.fields }
        check(contradictoryLab.fields.getValue(FieldId.REGION).value.isBlank()) { contradictoryLab.fields }

        // Modular post-OCR isolation: LabPostOcrModules.none is the pre-improvement control (all 7
        // modules off), and the Best build preset restores exactly the modules the last best measured
        // Lab v5 build shipped with, leaving only the two post-best modules off.
        check(LabPostOcrModules.none.signatureKey()=="best-v5") { LabPostOcrModules.none.signatureKey() }
        check(LabPostOcrModules.none.summaryLabel()=="Baseline (pre-improvement)") { LabPostOcrModules.none.summaryLabel() }
        check(LabPostOcrModules.bestBuild.summaryLabel()=="Best build (5 modules)") { LabPostOcrModules.bestBuild.summaryLabel() }
        check(LabPostOcrModules.bestBuild.enabledModules()==listOf(
            LabPostOcrModules.ModuleId.VINTAGE_RECOVERY,
            LabPostOcrModules.ModuleId.FINAL_PRODUCER_NEAR_MATCH,
            LabPostOcrModules.ModuleId.PRODUCER_ROLE_AND_PARTICLE,
            LabPostOcrModules.ModuleId.SPATIAL_SEMANTIC_GUARDS,
            LabPostOcrModules.ModuleId.VERTICAL_WINE_TITLE_PAIRS,
        )) { LabPostOcrModules.bestBuild.enabledKeys() }
        check(!LabPostOcrModules.bestBuild.blankSubregionFuzzy && !LabPostOcrModules.bestBuild.stackedAppellations)
        check(LabPostOcrModules.bestBuild!=LabPostOcrModules.none && LabPostOcrModules.bestBuild!=LabPostOcrModules.all)
        check(LabPostOcrModules.fromKeys(LabPostOcrModules.bestBuild.activeKeys())==LabPostOcrModules.bestBuild)
        check(LabPostOcrModules.fromKeys(LabPostOcrModules.all.activeKeys())==LabPostOcrModules.all)
        val bestV5Extractor=EvidenceExtractor(base,placeRepair=PlaceRepair.Config.conservativeLab,modules=LabPostOcrModules.none)
        val bestV5SubregionOff=bestV5Extractor.extract(
            listOf(LogicChecks.token("aint-emilion",.149f,engine="mlkit",x=.24f,y=.64f,w=.44f,h=.10f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(bestV5SubregionOff.fields.getValue(FieldId.SUBREGION).value.isBlank()) { bestV5SubregionOff.fields }
        val bestV5VintageOff=bestV5Extractor.extract(
            listOf(line("PAUILLAC",.45f),LogicChecks.token("I998",.598f,engine="mlkit",x=.42f,y=.78f,w=.16f,h=.06f)),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(bestV5VintageOff.fields.getValue(FieldId.VINTAGE).value.isBlank()) { bestV5VintageOff.fields }
        val bestV5StackedOff=bestV5Extractor.extract(
            listOf(
                LogicChecks.token("CASTILLON",.95f,engine="mlkit",x=.30f,y=.54f,w=.40f,h=.07f),
                LogicChecks.token("CÔTES DE BORDEAUX",.95f,engine="mlkit",x=.22f,y=.62f,w=.56f,h=.05f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(bestV5StackedOff.fields.getValue(FieldId.SUBREGION).value!="Castillon Cotes de Bordeaux") { bestV5StackedOff.fields }
        val subregionOnlyExtractor=EvidenceExtractor(
            base,
            placeRepair=PlaceRepair.Config.conservativeLab,
            modules=LabPostOcrModules(blankSubregionFuzzy=true),
        )
        val subregionOnlyFill=subregionOnlyExtractor.extract(
            listOf(
                LogicChecks.token("aint-emilion",.149f,engine="mlkit",x=.24f,y=.64f,w=.44f,h=.10f),
                LogicChecks.token("I998",.598f,engine="mlkit",x=.42f,y=.78f,w=.16f,h=.06f),
            ),
            2026,
            exactGeographyFastPath=true,
        ).fill
        check(subregionOnlyFill.fields.getValue(FieldId.SUBREGION).value=="Saint-Emilion") { subregionOnlyFill.fields }
        check(subregionOnlyFill.fields.getValue(FieldId.VINTAGE).value.isBlank()) { subregionOnlyFill.fields }
    }
}
