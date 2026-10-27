"""Static wiring contracts supplement the executable Kotlin extraction tests; not Android UI tests."""
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PHONE=ROOT/'app/src/main/java/com/yourapp/wine/phonecorpus'
LAB=ROOT/'app/src/main/java/com/yourapp/wine/textlab'
class BenchmarkVersionChecks(unittest.TestCase):
    def test_four_explicit_versions_and_short_comparison_names(self):
        runner=(PHONE/'PhoneCorpusRunner.kt').read_text()
        for label,key in [('Lab v4','lab-v4-expanded-producers'),('Prod v3.0','production-v3.0-lwin-only'),('Prod v2.0','production-v2.0-guarded'),('Prod v1.0','production-v1.0-15s')]:
            self.assertIn(label,runner);self.assertIn(key,runner)
        # Prod v3.0 and Lab v4 run the same spatial pipeline and differ only in producer
        # data (LWIN-only vs Lab expanded index), so each harvests its own summary stage.
        self.assertIn('PRODUCTION_V3("Prod v3.0", "production-v3.0-lwin-only", "spatial_lwin_evidence_summary")',runner)
        self.assertIn('LAB("Lab v4", "lab-v4-expanded-producers", "spatial_evidence_summary")',runner)
        self.assertIn('val usesSpatialEvidence:Boolean get()=this==PRODUCTION_V3 || this==LAB',runner)
        # Saved scored CSVs keep the producing build's pipeline ID; retired IDs must
        # stay resolvable to their owning mode or saved tables fail to restore/export.
        self.assertIn('"lab-v4-lwin-only" to Mode.LAB',runner)
        self.assertIn('"production-v3.0-spatial-merged" to Mode.PRODUCTION_V3',runner)
        self.assertIn('fun fromPipelineId(id:String):Mode?',runner)
        # The retired corpus importer is gone; the aliases now normalize a promoted frozen record
        # so an older build's ID never hides its version from the scorer.
        baselines=(PHONE/'WineAppLabelsBaselines.kt').read_text()
        self.assertIn('if (PhoneCorpusRunner.Mode.fromPipelineId(row.optString("pipeline")) == mode) row.put("pipeline", mode.pipelineId)',baselines)
        diagnostics=(PHONE/'PhoneCorpusPipelineDiagnostics.kt').read_text()
        self.assertIn('val labResolvers = mode == "LAB" || mode == "PRODUCTION_V3"',diagnostics)
        self.assertIn('"PRODUCTION_V3" -> "spatial_lwin_evidence_"',diagnostics)
        self.assertIn('producer=[0-9a-f]{64};wine=[0-9a-f]{64}',diagnostics)
        producer=(ROOT/'app/src/main/java/com/yourapp/wine/gazetteer/ProducerGazetteer.kt').read_text()
        # The primary/production gate still rejects supplemental sources in the primary
        # database; the expanded index is a separate Lab-only asset with its own checks.
        self.assertIn('Unverified supplemental producer sources are not permitted',producer)
        self.assertIn('fun openExpanded(',producer)
        self.assertIn('manifest.getString("kind") == "lab-expanded-producer-index"',producer)
        self.assertIn('manifest.getBoolean("lab_only")',producer)
        self.assertIn('labSupplemental = true',producer)
        self.assertNotIn('ttb-support.sqlite',producer.lower())
        self.assertNotIn('osm-support.sqlite',producer.lower())
        resolver=(ROOT/'app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt').read_text()
        self.assertIn('val labSupplemental:Boolean get()=false',resolver)
        self.assertIn('Lab expanded producer candidate (',resolver)
        page=(PHONE/'WineAppLabelsViewModel.kt').read_text()
        self.assertIn('PhoneCorpusRunner.Mode.entries.map { comparedMode ->',page)
        self.assertIn('scoreboard.build(comparisonPredictions, comparedMode).rows.single()',page)
        ui=(PHONE/'WineAppLabelsActivity.kt').read_text()
        self.assertIn('PhoneCorpusRunner.Mode.entries.forEach { mode ->',ui)
        self.assertIn('Text(mode.title)',ui)
        # Prod versions are frozen comparators: their radios are display-only.
        self.assertIn('enabled = !state.busy && mode == PhoneCorpusRunner.Mode.LAB,',ui)
        self.assertIn('"Frozen ${capture.count}/${state.total} photos · captured by ${capture.build}"',ui)
    def test_all_versions_share_one_fresh_ocr_run(self):
        runner=(PHONE/'PhoneCorpusRunner.kt').read_text()
        self.assertEqual(runner.count('TextLabRunner.recognize('),1)
        self.assertIn('val needLwin=Mode.PRODUCTION_V3 in labModes',runner)
        self.assertIn('spatialPhrases=needSpatial,benchmarkOnly=true,benchmarkAllPipelines=true,',runner)
        self.assertIn('benchmarkEvidence=needEvidence,benchmarkLwinSpatial=needLwin',runner)
        self.assertIn('Mode.PRODUCTION_V3 -> "spatial_lwin_lab"',runner)
        self.assertIn('it["stage"]==mode.summaryStage',runner)
        self.assertIn('suspend fun runImageAllModes(context:Context,image:String,hash:String,modes:Set<Mode>',runner)
        # Every caller goes through the multi-mode entry point; the single-mode wrapper is retired.
        self.assertNotIn('suspend fun runImage(context:Context',runner)
        self.assertNotIn('compareGazetteer=true',runner)
        board=(PHONE/'PhoneCorpusScoreboard.kt').read_text()
        self.assertIn('val names=listOf(mode.pipelineId)',board)
        self.assertNotIn('lab-trigram',board)
    def test_extractor_dispatch_single_or_benchmark_both(self):
        extraction=(LAB/'TextLabWineExtraction.kt').read_text()
        self.assertIn('val runEvidence=if(batch.benchmarkAllPipelines) batch.benchmarkEvidence else !batch.spatialPhrases && !batch.benchmarkLwinSpatial',extraction)
        self.assertIn('val runLwinSpatial=batch.benchmarkLwinSpatial',extraction)
        self.assertIn('val runExpandedSpatial=if(batch.benchmarkAllPipelines) batch.spatialPhrases else batch.spatialPhrases && !batch.benchmarkLwinSpatial',extraction)
        self.assertIn('TextLabSpatialPreview.append',extraction)
        self.assertIn('ProducerGazetteer.openExpanded(',extraction)
        self.assertIn('appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=false)',extraction)
        self.assertIn('appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=true,singleReading=batch.resolverSingleReading)',extraction)
        self.assertIn('if(!batch.benchmarkOnly)',extraction)
        self.assertFalse((LAB/'TextLabMergedSpatialPreview.kt').exists())
        self.assertNotIn('TextLabMergedSpatialPreview',extraction)
        spatial=(LAB/'TextLabSpatialPreview.kt').read_text()
        self.assertIn('recordsOverride=readings',spatial)
        self.assertIn('val readings=original',spatial)
        self.assertNotIn('val readings=TextLabSpatialPhrases.readings(proposals)',spatial)
        self.assertNotIn('TextLabRunner.recognize',spatial)
        self.assertNotIn('scoring.json',spatial)
        self.assertIn('candidate:String="spatial_lab",stagePrefix:String="spatial_"',spatial)
        self.assertIn('summaryStage,"failed"',spatial)
    def test_checkpoint_and_export_version_identity(self):
        vm=(PHONE/'WineAppLabelsViewModel.kt').read_text()
        self.assertIn('${mode.name}',vm)
        self.assertIn('.put("pipeline", mode.pipelineId)',vm)
        self.assertIn('CsvUploadParts.split',vm)
        # Prod rows are scored from frozen records and say so in both exports.
        self.assertIn('"result_origin" to "frozen_baseline"',vm)
        baselines=(PHONE/'WineAppLabelsBaselines.kt').read_text()
        self.assertIn('.put("stored_pipeline", result.optString("pipeline"))',baselines)
        self.assertIn('record.put("corpus_signature", signature)',baselines)
