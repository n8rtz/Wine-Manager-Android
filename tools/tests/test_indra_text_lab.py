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
        self.assertLess(source.index('OpenCvRuntime.ensureLoaded()'),source.index('var gray=Mat()'))
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

    def test_enhanced_indra_keeps_separate_identity_and_provenance(self):
        spec=(LAB/'IndraSpec.kt').read_text()
        journal=(LAB/'TextLabJournal.kt').read_text()
        for key in ('indra_enhanced','indra_lm_sha256','indra_orientation_sha256','enhanced-base-1'):
            self.assertIn(key,spec)
        self.assertIn('IndraSpec.enhancedMetadata',journal)
        source=(LAB/'IndraPipeline.kt').read_text()
        self.assertIn('IndraLanguageModel.load',source)
        self.assertIn('IndraBeam.decode',source)
        self.assertIn('indra_greedy_text',source)
        self.assertIn('indra_lm_applied',source)
        self.assertIn('1024*12/maxOf(median,1.0)',source)
        self.assertIn('pageToBase*Matrix3',source)
        self.assertIn('ownedPage?.release()',source)
    def test_enhanced_preprocessing_and_limits(self):
        orientation=(LAB/'IndraOrientation.kt').read_text()
        self.assertIn('BORDER_CONSTANT,Scalar(255.0)',orientation)
        self.assertIn('(affine*turn).inverse()',orientation)
        source=(LAB/'IndraPipeline.kt').read_text()
        self.assertIn('if(enhanced) 4096 else 1024',source)
        self.assertIn('if(enhanced) 256 else 32',source)
        self.assertIn('chosen=if(probability>.95f',source)
        self.assertIn('checkActive()',orientation)
    def test_genuine_extra_models_are_checksum_pinned(self):
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        spec=(LAB/'IndraSpec.kt').read_text()
        for sha in ('6c0fb618a363a0acdd05d4f5b0edd9e8146d2d990541b87f6130bbfe17cea58d',
                    '20eaf670f55a7cf9facfe1bdd6c7b8e40be75bbf4d48a3df43f66aa3bbd874fd'):
            self.assertIn(sha,gradle);self.assertIn(sha,spec)
