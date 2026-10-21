"""Configuration/provenance guards, not native inference or accuracy tests."""
import hashlib,json,pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
LAB=ROOT/'app/src/debug/java/com/yourapp/wine/textlab'
ASSETS=ROOT/'app/src/debug/assets/indra-v1'
class IndraLabTests(unittest.TestCase):
    def test_metadata_pin_and_model_contract(self):
        raw=(ASSETS/'meta.json').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),'4ce4c94b46f5d644033c02e718322014ff4081270f385038c571912b56696339')
        meta=json.loads(raw);vocab=json.loads(meta['vocab'])
        self.assertEqual((meta['det_long_side'],meta['rec_height'],meta['rec_window']),(1024,32,3))
        self.assertEqual((vocab['blank'],len(vocab['chars'])),(0,1697))
        self.assertIn('SHARE', (ASSETS/'LICENSE').read_text().upper().replace('-',''))
        self.assertIn('Apache License',(ASSETS/'LICENSE-code.txt').read_text())
    def test_models_debug_only_and_no_runtime_network(self):
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('resolve/v1.1.0/$name',gradle)
        self.assertIn('temp.length()==size && hash(temp)==sha',gradle)
        self.assertIn('androidComponents.selector().withBuildType("debug")',gradle)
        self.assertFalse((ROOT/'app/src/main/assets/indra-v1').exists())
        source=(LAB/'IndraPipeline.kt').read_text()
        for forbidden in ('openConnection','HttpClient','URL(', 'PaddleTextDetector.detect'):
            self.assertNotIn(forbidden,source)
        self.assertIn('options.setCPUArenaAllocator(false)',source)
        self.assertIn('options.setTerminate(true)',source)
        self.assertIn('lease.close()',source)
        self.assertLess(source.index('OpenCvRuntime.ensureLoaded()'),source.index('val gray=Mat()'))
    def test_batch_wiring_and_explicit_scope(self):
        journal=(LAB/'TextLabJournal.kt').read_text()
        self.assertIn('IndraSpec.metadata',journal)
        self.assertIn('TextLabChoices.selected(selected)',journal)
        self.assertIn('IndraLabCase.run(context,crop,mapping,photo,gate,journal,checkpoint)',(LAB/'TextLabRunner.kt').read_text())
        case=(LAB/'IndraLabCase.kt').read_text()
        for text in ('TextLabBudgets.OCR_MS','"timed_out"','"cancelled"','"candidate_summary"','checkpoint()'):
            self.assertIn(text,case)
        self.assertIn('Accept and enable',(LAB/'OcrTextLabActivity.kt').read_text())
        self.assertIn('no LM, script routing, orientation, deskew', (LAB/'IndraSpec.kt').read_text())
    def test_production_medium_with_shared_deadline(self):
        main=ROOT/'app/src/main/java/com/yourapp/wine'
        services=(main/'capture/WineScannerServices.kt').read_text()
        self.assertIn('MlKitOcrEngine(gate)',services)
        self.assertIn('PaddleMediumOcrEngine.create(app, gate)',services)
        self.assertNotIn('Indra',services)
        engine=(main/'textlab/PaddleMediumOcrEngine.kt').read_text()
        self.assertIn('it.id=="candidate_3"',engine)
        self.assertIn('variantId=="V0"',engine)
        self.assertIn('scoreIsEstimated=true',engine)
        self.assertIn('const val BUDGET_MS = 15_000L',(main/'ocr/ScanDeadline.kt').read_text())
