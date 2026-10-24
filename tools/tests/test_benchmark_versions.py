"""Static wiring contracts supplement the executable Kotlin extraction tests; not Android UI tests."""
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PHONE=ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus'
LAB=ROOT/'app/src/debug/java/com/yourapp/wine/textlab'
class BenchmarkVersionChecks(unittest.TestCase):
    def test_three_explicit_versions(self):
        runner=(PHONE/'PhoneCorpusRunner.kt').read_text()
        for label,key in [('Production v1.0','production-v1.0-15s'),('Production v2.0','production-v2.0-guarded'),('Lab · spatial merging','lab-spatial-v1')]:
            self.assertIn(label,runner);self.assertIn(key,runner)
        ui=(PHONE/'PhoneCorpusActivity.kt').read_text()
        self.assertIn('Mode.entries.forEach',ui);self.assertIn('Text(mode.title,',ui)
    def test_one_scored_contender_and_one_fresh_ocr_run(self):
        runner=(PHONE/'PhoneCorpusRunner.kt').read_text()
        self.assertEqual(runner.count('TextLabRunner.recognize('),1)
        self.assertIn('spatialPhrases=mode==Mode.LAB,benchmarkOnly=true',runner)
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
        self.assertIn('val readings=TextLabSpatialPhrases.readings(proposals)',spatial)
        self.assertNotIn('TextLabRunner.recognize',spatial)
        self.assertNotIn('scoring.json',spatial)
        self.assertIn('"spatial_evidence_summary","failed"',spatial)
    def test_checkpoint_and_export_version_identity(self):
        vm=(PHONE/'PhoneCorpusViewModel.kt').read_text()
        self.assertIn('${mode.name}',vm)
        self.assertIn('.put("pipeline",mode.pipelineId)',vm)
        self.assertIn('CsvUploadParts.split',vm)
