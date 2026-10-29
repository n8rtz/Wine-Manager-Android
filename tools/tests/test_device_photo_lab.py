"""Source wiring/isolation checks; native OCR and Android picker behavior need device verification."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHONE = ROOT / 'app/src/main/java/com/yourapp/wine/phonecorpus'
APP = ROOT / 'app/src/main/java/com/n8rtz/winetracker'


class DevicePhotoLabContracts(unittest.TestCase):
    def test_multiple_photo_picker_and_private_activity(self):
        activity = (PHONE / 'DevicePhotoLabActivity.kt').read_text()
        self.assertIn('ActivityResultContracts.OpenMultipleDocuments()', activity)
        self.assertIn('picker.launch(arrayOf("image/*"))', activity)
        self.assertIn('vm.runPhotos(uris)', activity)
        self.assertIn('by viewModels()', activity)
        self.assertIn('CreateDocument("application/json")', activity)
        self.assertIn('DevicePhotoLabActivity" android:exported="false"',
                      (ROOT / 'app/src/main/AndroidManifest.xml').read_text())

    def test_current_configuration_is_snapshotted_not_replaced(self):
        parent = (PHONE / 'WineAppLabelsActivity.kt').read_text()
        vm = (APP / 'viewmodels/DevicePhotoLabViewModel.kt').read_text()
        self.assertIn('ArrayList(state.labModules.enabledKeys())', parent)
        self.assertIn('LabPostOcrModules.fromKeys(keys)', vm)
        self.assertIn('if(configured) return', vm)
        self.assertIn('val modules=mutable.value.modules', vm)
        self.assertIn('labModules=modules', vm)

    def test_no_reference_scoring_or_benchmark_persistence(self):
        vm = (APP / 'viewmodels/DevicePhotoLabViewModel.kt').read_text()
        for forbidden in ['scoring.json', 'manifest.json', 'WineAppLabelsBuildLog',
                          'WineAppLabelsBaselines', 'WineAppLabelsOcrCache', 'PhoneCorpusScoreboard']:
            self.assertNotIn(forbidden, vm)
        self.assertIn('SelectedLabelPhotoImporter', vm)
        self.assertIn('setOf(PhoneCorpusRunner.Mode.LAB)', vm)
        self.assertIn('cachedOcr=null', vm)
        self.assertIn('sourceFile=local', vm)
        self.assertIn('"reference_status" to "unscored"', vm)

    def test_runner_reuses_pipeline_and_requires_fresh_lab_for_device_files(self):
        runner = (PHONE / 'PhoneCorpusRunner.kt').read_text()
        self.assertIn('sourceFile:File?=null', runner)
        self.assertIn('sourceFile==null || (modes==setOf(Mode.LAB) && cachedOcr==null)', runner)
        self.assertIn('sourceFile?.inputStream() ?: context.assets.open(', runner)
        self.assertIn('check(sha(input)==hash)', runner)
        self.assertIn('TextLabWineExtraction.append(context,batch,warmSession=warmSession?.extraction)', runner)

    def test_result_cards_include_actual_photo_and_every_parameter(self):
        ui = (APP / 'ui/screens/DevicePhotoLabScreen.kt').read_text()
        result = (PHONE / 'DevicePhotoLabResult.kt').read_text()
        self.assertIn('items(state.photos,key={ it.index })', ui)
        self.assertIn('photo.localPhoto ?: photo.uri', ui)
        self.assertIn('SelectionContainer', ui)
        self.assertIn('Copy all results', ui)
        for key in ['producer', 'wine_name', 'country', 'region', 'subregion', 'vintage']:
            self.assertIn(f'"{key}" to ', result)
        self.assertIn('PhoneCorpusRunner.Mode.LAB.pipelineId', result)
        self.assertIn('engine_incomplete', result)
        self.assertIn('(not assigned)', ui)

    def test_retains_ocr_decisions_and_uses_uploadable_json_not_image_exports(self):
        vm = (APP / 'viewmodels/DevicePhotoLabViewModel.kt').read_text()
        self.assertIn('LabSuiteArchive.write(', vm)
        self.assertIn('onFreshOcr={ metadata,rows ->', vm)
        self.assertIn('"decision_record"', vm)
        self.assertIn('"shared_ocr"', vm)
        self.assertIn('"record_index" to index.toString()', vm)
        self.assertIn('output.remove("trace")', vm)
        self.assertIn('lifetime.invokeOnCompletion { root.deleteRecursively() }', vm)
        self.assertIn('if(mutable.value.busy || uris.isEmpty()) return', vm)
        self.assertIn('if(stopping) break', vm)


if __name__ == '__main__':
    unittest.main()
