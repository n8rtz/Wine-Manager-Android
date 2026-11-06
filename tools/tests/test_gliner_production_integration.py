"""Structural checks complement device/unit tests; they do not claim to run Android OCR."""
import pathlib
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[2]
APP=ROOT/'app/src/main/java'
GL=ROOT/'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner'

class GlinerProductionIntegrationTest(unittest.TestCase):
    def text(self,path): return path.read_text()

    def test_add_wine_camera_and_picker_share_router(self):
        vm=self.text(APP/'com/n8rtz/winetracker/viewmodels/ScannerViewModel.kt')
        self.assertIn('onLabelPhotoCaptured',vm)
        self.assertIn('onLabelPhotoSelected',vm)
        self.assertEqual(vm.count('ProductionLabelScanner.scan(getApplication(),photo.file,started,source)'),1)
        self.assertIn('result.copy(diagnostics',vm)
        manager=self.text(APP/'com/n8rtz/winetracker/scanner/ScannerManager.kt')
        self.assertIn('ProductionLabelScanner.scan(appContext,file,shutterMs,ScanInputSource.FILE)',manager)

    def test_real_lab_workflow_is_the_only_production_path(self):
        scanner=self.text(GL/'GlinerProductionScanner.kt')
        lab=self.text(GL/'GlinerLabRunner.kt')
        router=self.text(APP/'com/yourapp/wine/capture/ProductionLabelScanner.kt')
        self.assertIn('GlinerBundle.status(context)',scanner)
        self.assertIn('scanContext.ensureActive()',scanner)
        self.assertIn('!it.pinned || !it.matchesPin',scanner)
        self.assertIn('!status.int4',scanner)
        self.assertIn('GlinerOnnxModel.open(context,status,GlinerLaneConfig())',scanner)
        self.assertIn('GlinerLabRunner.runProduction(context,photo,opened,session,deadlineAtMs)',scanner)
        self.assertIn('LabPostOcrModules.none.withIntegrated()',lab)
        self.assertIn('GlinerOcr.recognize(context,photo',lab)
        self.assertIn('GlinerFieldDecisionPolicy.applyWithEvidence(',lab)
        self.assertIn('GlinerProductionScanner.scan(context,photo,started,source',router)
        self.assertNotIn('WineScannerServices.get(',router)
        self.assertNotIn('scanner.scan(',router)
        self.assertIn('Could not read the label — retake or enter details manually',router)
        self.assertIn('const val BUDGET_MS=90_000L',router)

    def test_normal_review_does_not_add_model_specific_ui(self):
        adapter=self.text(GL/'GlinerProductionAdapter.kt')
        router=self.text(APP/'com/yourapp/wine/capture/ProductionLabelScanner.kt')
        self.assertNotIn('GLiNER full-photo suggestion',adapter)
        self.assertNotIn('GLiNER read the full photo',adapter)
        self.assertNotIn('GLiNER unavailable;',router)
        self.assertIn('states[key]=="GREEN" -> UiState.GREEN',adapter)
        self.assertIn('states[key]=="YELLOW" -> UiState.YELLOW',adapter)

    def test_production_image_never_deleted_by_shared_runner(self):
        lab=self.text(GL/'GlinerLabRunner.kt')
        copy_scope=lab[lab.index('suspend fun run('):lab.index('suspend fun runProduction(')]
        photo_scope=lab[lab.index('suspend fun runProduction('):lab.index('private data class PostOutcome')]
        self.assertIn('photo.delete()',copy_scope)
        self.assertNotIn('photo.delete()',photo_scope)
        self.assertIn('true,emptyMap()',photo_scope)
        self.assertIn('deadlineAtMs',photo_scope)

if __name__=='__main__': unittest.main()
