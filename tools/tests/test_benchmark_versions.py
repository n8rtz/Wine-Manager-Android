"""Static wiring contracts supplement the executable Kotlin extraction tests; not Android UI tests."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHONE = ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus"
LAB = ROOT / "app/src/main/java/com/yourapp/wine/textlab"


class BenchmarkVersionChecks(unittest.TestCase):
    def test_candidate_discovery_modules_are_independent_and_documented(self):
        modules = (LAB / "evidence/LabPostOcrModules.kt").read_text()
        doc = (ROOT / "_refs/LAB_MODIFIERS.md").read_text()
        ui = (PHONE / "WineAppLabelsActivity.kt").read_text()
        self.assertIn("LabPostOcrModules.ModuleId.entries.forEach", ui)
        best = modules.split("val bestBuild = LabPostOcrModules(", 1)[1].split("val all", 1)[0]
        for prop, enum, key in [
            ("geographySpans", "GEOGRAPHY_SPANS", "geography_spans"),
            ("producerSpans", "PRODUCER_SPANS", "producer_spans"),
            ("grapeSpans", "GRAPE_SPANS", "grape_spans"),
            ("classificationSpans", "CLASSIFICATION_SPANS", "classification_spans"),
            ("vintageSpans", "VINTAGE_SPANS", "vintage_spans"),
            ("wineNameSpans", "WINE_NAME_SPANS", "wine_name_spans"),
        ]:
            self.assertIn(f"val {prop}: Boolean = false", modules)
            self.assertIn(f"ModuleId.{enum} -> copy({prop} = enabled)", modules)
            self.assertIn(f"ModuleId.{enum} -> {prop}", modules)
            self.assertIn(key, doc)
            self.assertNotIn(f"{prop} = true", best)
        extractor = (LAB / "evidence/EvidenceExtractor.kt").read_text()
        self.assertLess(extractor.index("discoverSpans(it,year"), extractor.index("val labelContext="))
        self.assertIn('values.keys.any { it.name !in p.construction.substringAfter', extractor)
        self.assertIn('setOf(o.id)', extractor)

    def test_foundations_are_ordered_and_do_not_change_the_measured_preset(self):
        modules = (LAB / "evidence/LabPostOcrModules.kt").read_text()
        vm = (PHONE / "WineAppLabelsViewModel.kt").read_text()
        best = modules.split("val bestBuild = LabPostOcrModules(", 1)[1].split("val all", 1)[0]
        flags = ["candidateIsolation", "exactProducerProtection", "administrativeFiltering",
                 "roleAwareJoins", "broaderDiscovery"]
        for flag in flags:
            self.assertIn(f"val {flag}: Boolean = false", modules)
            self.assertNotIn(f"{flag} = true", best)
        positions = [vm.index("ModuleId." + key, vm.index("if (foundations)")) for key in
                     ["CANDIDATE_ISOLATION", "EXACT_PRODUCER_PROTECTION", "ADMINISTRATIVE_FILTERING",
                      "ROLE_AWARE_JOINS", "BROADER_DISCOVERY"]]
        self.assertEqual(positions, sorted(positions))
        extractor = (LAB / "evidence/EvidenceExtractor.kt").read_text()
        self.assertIn("graph.observations.filter { other ->", extractor)
        self.assertIn("permits(FieldId.VINTAGE)", extractor)
        self.assertIn("permits(FieldId.SUBREGION)", extractor)
        resolver = (ROOT / "app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt").read_text()
        self.assertIn("if(protectExact && candidates.count", resolver)

    def test_four_explicit_versions_and_short_comparison_names(self):
        runner = (PHONE / "PhoneCorpusRunner.kt").read_text()
        for label, key in [
            ("Lab v5", "lab-v5-expanded-producers"),
            ("Prod v4.0", "production-v4.0-lwin-only"),
            ("Prod v2.0", "production-v2.0-guarded"),
            ("Prod v1.0", "production-v1.0-15s"),
        ]:
            self.assertIn(label, runner)
            self.assertIn(key, runner)
        # Prod v4.0 and Lab v5 run the same spatial pipeline and differ only in producer
        # data (LWIN-only vs Lab expanded index), so each harvests its own summary stage.
        self.assertIn(
            'PRODUCTION_V4("Prod v4.0", "production-v4.0-lwin-only", "spatial_lwin_evidence_summary")',
            runner,
        )
        self.assertIn(
            'LAB("Lab v5", "lab-v5-expanded-producers", "spatial_evidence_summary")',
            runner,
        )
        self.assertIn(
            "val usesSpatialEvidence:Boolean get()=this==PRODUCTION_V4 || this==LAB",
            runner,
        )
        # Captures retain their old IDs, but those IDs still resolve to the renamed modes.
        for alias in (
            '"lab-v4-expanded-producers" to Mode.LAB',
            '"lab-v4-lwin-only" to Mode.LAB',
            '"production-v3.0-lwin-only" to Mode.PRODUCTION_V4',
            '"production-v3.0-spatial-merged" to Mode.PRODUCTION_V4',
        ):
            self.assertIn(alias, runner)
        self.assertIn("fun fromPipelineId(id:String):Mode?", runner)
        self.assertIn("val acceptedPipelineIds:Set<String>", runner)

        baselines = (PHONE / "WineAppLabelsBaselines.kt").read_text()
        self.assertIn('File(root, "PRODUCTION_V3")', baselines)
        self.assertIn("private fun readFolders(mode: PhoneCorpusRunner.Mode)", baselines)
        self.assertIn(
            'if (PhoneCorpusRunner.Mode.fromPipelineId(row.optString("pipeline")) == mode) row.put("pipeline", mode.pipelineId)',
            baselines,
        )
        diagnostics = (PHONE / "PhoneCorpusPipelineDiagnostics.kt").read_text()
        self.assertIn('val labResolvers = mode == "LAB" || mode == "PRODUCTION_V4"', diagnostics)
        self.assertIn('"PRODUCTION_V4" -> "spatial_lwin_evidence_"', diagnostics)
        self.assertIn("producer=[0-9a-f]{64};wine=[0-9a-f]{64}", diagnostics)

        producer = (ROOT / "app/src/main/java/com/yourapp/wine/gazetteer/ProducerGazetteer.kt").read_text()
        # The primary/production gate still rejects supplemental sources in the primary
        # database; the expanded index is a separate Lab-only asset with its own checks.
        self.assertIn("Unverified supplemental producer sources are not permitted", producer)
        self.assertIn("fun openExpanded(", producer)
        self.assertIn('manifest.getString("kind") == "lab-expanded-producer-index"', producer)
        self.assertIn('manifest.getBoolean("lab_only")', producer)
        self.assertIn("labSupplemental = true", producer)
        self.assertNotIn("ttb-support.sqlite", producer.lower())
        self.assertNotIn("osm-support.sqlite", producer.lower())
        resolver = (ROOT / "app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt").read_text()
        self.assertIn("val labSupplemental:Boolean get()=false", resolver)
        self.assertIn("Lab expanded producer candidate (", resolver)

        page = (PHONE / "WineAppLabelsViewModel.kt").read_text()
        self.assertIn("PhoneCorpusRunner.Mode.entries.map { comparedMode ->", page)
        self.assertIn("scoreboard.build(comparisonPredictions, comparedMode).rows.single()", page)
        self.assertIn('savedMode == "PRODUCTION_V3"', page)
        self.assertIn("Lab v4 runs intentionally do not satisfy this", page)
        ui = (PHONE / "WineAppLabelsActivity.kt").read_text()
        self.assertIn("val mode = PhoneCorpusRunner.Mode.LAB", ui)
        self.assertIn("Text(mode.title)", ui)
        self.assertNotIn("PhoneCorpusRunner.Mode.entries.forEach", ui)
        self.assertNotIn("RadioButton(", ui)

    def test_all_versions_share_one_fresh_ocr_run(self):
        runner = (PHONE / "PhoneCorpusRunner.kt").read_text()
        self.assertEqual(runner.count("TextLabRunner.recognize("), 1)
        self.assertIn("val needLwin=Mode.PRODUCTION_V4 in labModes", runner)
        self.assertIn("spatialPhrases=needSpatial,benchmarkOnly=true,benchmarkAllPipelines=true,", runner)
        self.assertIn("benchmarkEvidence=needEvidence,benchmarkLwinSpatial=needLwin", runner)
        self.assertIn('Mode.PRODUCTION_V4 -> "spatial_lwin_lab"', runner)
        self.assertIn('it["stage"]==mode.summaryStage', runner)
        self.assertIn("suspend fun runImageAllModes(context:Context,image:String,hash:String,modes:Set<Mode>", runner)
        # Every caller goes through the multi-mode entry point; the single-mode wrapper is retired.
        self.assertNotIn("suspend fun runImage(context:Context", runner)
        self.assertNotIn("compareGazetteer=true", runner)
        board = (PHONE / "PhoneCorpusScoreboard.kt").read_text()
        self.assertIn("val names=listOf(mode.pipelineId)", board)
        self.assertIn("it.optString(\"pipeline\") in acceptedPipelineIds", board)
        funnel = (PHONE / "PhoneCorpusFunnelJson.kt").read_text()
        self.assertIn("ownerMode?.acceptedPipelineIds", funnel)
        self.assertIn("it.optString(\"pipeline\") in acceptedPipelineIds", funnel)
        self.assertNotIn("lab-trigram", board)

    def test_extractor_dispatch_single_or_benchmark_both(self):
        extraction = (LAB / "TextLabWineExtraction.kt").read_text()
        self.assertIn(
            "val runEvidence=if(batch.benchmarkAllPipelines) batch.benchmarkEvidence else !batch.spatialPhrases && !batch.benchmarkLwinSpatial",
            extraction,
        )
        self.assertIn("val runLwinSpatial=batch.benchmarkLwinSpatial", extraction)
        self.assertIn(
            "val runExpandedSpatial=if(batch.benchmarkAllPipelines) batch.spatialPhrases else batch.spatialPhrases && !batch.benchmarkLwinSpatial",
            extraction,
        )
        self.assertIn("TextLabSpatialPreview.append", extraction)
        self.assertIn("ProducerGazetteer.openExpanded(", extraction)
        self.assertIn("appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=false)", extraction)
        self.assertIn(
            "appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=true,singleReading=batch.resolverSingleReading)",
            extraction,
        )
        self.assertIn("if(!batch.benchmarkOnly)", extraction)
        self.assertFalse((LAB / "TextLabMergedSpatialPreview.kt").exists())
        self.assertNotIn("TextLabMergedSpatialPreview", extraction)
        spatial = (LAB / "TextLabSpatialPreview.kt").read_text()
        self.assertIn("recordsOverride=readings", spatial)
        self.assertIn("val readings=original", spatial)
        self.assertNotIn("val readings=TextLabSpatialPhrases.readings(proposals)", spatial)
        self.assertNotIn("TextLabRunner.recognize", spatial)
        self.assertNotIn("scoring.json", spatial)
        self.assertIn('candidate:String="spatial_lab",stagePrefix:String="spatial_"', spatial)
        self.assertIn('summaryStage,"failed"', spatial)

    def test_checkpoint_and_export_version_identity(self):
        vm = (PHONE / "WineAppLabelsViewModel.kt").read_text()
        self.assertIn("${mode.name}", vm)
        self.assertIn('.put("pipeline", mode.pipelineId)', vm)
        self.assertIn("CsvUploadParts.split", vm)
        # Prod rows are scored from frozen records and say so in both exports.
        self.assertIn('"result_origin" to "frozen_baseline"', vm)
        baselines = (PHONE / "WineAppLabelsBaselines.kt").read_text()
        self.assertIn('.put("stored_pipeline", result.optString("pipeline"))', baselines)
        self.assertIn('record.put("corpus_signature", signature)', baselines)

    def test_build_log_upgrades_legacy_labels_without_losing_provenance(self):
        build_log = (PHONE / "WineAppLabelsBuildLog.kt").read_text()
        self.assertIn('"Lab v4" -> PhoneCorpusRunner.Mode.LAB.title', build_log)
        self.assertIn('"Prod v3.0" -> PhoneCorpusRunner.Mode.PRODUCTION_V4.title', build_log)
        self.assertIn("ownerMode?.title", build_log)
        self.assertIn("pipelineId = savedPipeline.ifBlank", build_log)
        self.assertIn('const val BEST_BUILD_ROW_TITLE = "Lab v5 Best build"', build_log)
        self.assertIn("modelIdx >= 0 && record.getOrNull(modelIdx) == BEST_BUILD_ROW_TITLE", build_log)
