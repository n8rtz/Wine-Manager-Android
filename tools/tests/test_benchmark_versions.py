"""Static wiring contracts supplement the executable Kotlin extraction tests; not Android UI tests."""
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PHONE=ROOT/'app/src/main/java/com/yourapp/wine/phonecorpus'
LAB=ROOT/'app/src/main/java/com/yourapp/wine/textlab'
class BenchmarkVersionChecks(unittest.TestCase):
    def test_four_explicit_versions_and_short_comparison_names(self):
        runner=(PHONE/'PhoneCorpusRunner.kt').read_text()
        for label,key in [('Lab v4','lab-v4-lwin-only'),('Prod v3.0','production-v3.0-lwin-only'),('Prod v2.0','production-v2.0-guarded'),('Prod v1.0','production-v1.0-15s')]:
            self.assertIn(label,runner);self.assertIn(key,runner)
        self.assertIn('val usesSpatialEvidence:Boolean get()=this==PRODUCTION_V3 || this==LAB',runner)
        diagnostics=(PHONE/'PhoneCorpusPipelineDiagnostics.kt').read_text()
        self.assertIn('mode == "LAB" || mode == "PRODUCTION_V3"',diagnostics)
        self.assertIn('producer=[0-9a-f]{64};wine=[0-9a-f]{64}',diagnostics)
        batch=(LAB/'TextLabBatch.kt').read_text()
        producer=(ROOT/'app/src/main/java/com/yourapp/wine/gazetteer/ProducerGazetteer.kt').read_text()
        self.assertIn('Unverified supplemental producer sources are not permitted',producer)
        self.assertNotIn('xwines',producer.lower())
        self.assertNotIn('ttb-support.sqlite',producer.lower())
        self.assertNotIn('osm-support.sqlite',producer.lower())
        page=(PHONE/'WineAppLabelsViewModel.kt').read_text()
        self.assertIn('PhoneCorpusRunner.Mode.entries.map { comparedMode ->',page)
        self.assertIn('scoreboard.build(comparisonPredictions, comparedMode).rows.single()',page)
        ui=(PHONE/'PhoneCorpusActivity.kt').read_text()
        self.assertIn('Mode.entries.forEach',ui);self.assertIn('Text(mode.title,',ui)
    def test_one_scored_contender_and_one_fresh_ocr_run(self):
        runner=(PHONE/'PhoneCorpusRunner.kt').read_text()
        self.assertEqual(runner.count('TextLabRunner.recognize('),1)
        self.assertIn('spatialPhrases=mode.usesSpatialEvidence,benchmarkOnly=true',runner)
        self.assertIn('it["stage"]==mode.summaryStage',runner)
        self.assertNotIn('compareGazetteer=true',runner)
        board=(PHONE/'PhoneCorpusScoreboard.kt').read_text()
        self.assertIn('val names=listOf(mode.pipelineId)',board)
        self.assertNotIn('lab-trigram',board)
    def test_exclusive_extractor_dispatch(self):
        extraction=(LAB/'TextLabWineExtraction.kt').read_text()
        self.assertIn('if(batch.spatialPhrases)',extraction)
        self.assertIn('TextLabSpatialPreview.append',extraction)
        self.assertIn('if(!batch.benchmarkOnly)',extraction)
        spatial=(LAB/'TextLabSpatialPreview.kt').read_text()
        self.assertIn('recordsOverride=readings',spatial)
        self.assertIn('val readings=original',spatial)
        self.assertNotIn('val readings=TextLabSpatialPhrases.readings(proposals)',spatial)
        self.assertNotIn('TextLabRunner.recognize',spatial)
        self.assertNotIn('scoring.json',spatial)
        self.assertIn('"spatial_evidence_summary","failed"',spatial)
    def test_checkpoint_and_export_version_identity(self):
        vm=(PHONE/'PhoneCorpusViewModel.kt').read_text()
        self.assertIn('${mode.name}',vm)
        self.assertIn('.put("pipeline",mode.pipelineId)',vm)
        self.assertIn('CsvUploadParts.split',vm)
