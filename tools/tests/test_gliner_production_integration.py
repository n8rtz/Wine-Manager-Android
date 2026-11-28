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
        self.assertIn('ProductionLabelScanSession.captured',vm)
        self.assertIn('ProductionLabelScanSession.selected',vm)
        session=self.text(APP/'com/n8rtz/winetracker/scanner/ProductionLabelScanSession.kt')
        self.assertEqual(session.count('ProductionLabelScanner.scan(app, photo.file, started, source)'),1)
        self.assertIn('result.copy(diagnostics',session)
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
        self.assertIn('val laneConfig=GlinerLaneConfig()',scanner)
        self.assertIn('GlinerOnnxModel.open(context,status,laneConfig)',scanner)
        self.assertIn('GlinerLabRunner.runProduction(context,photo,opened,session,deadlineAtMs,onStage)',scanner)
        self.assertIn('LabPostOcrModules.none.withIntegrated()',lab)
        self.assertIn('GlinerOcr.recognize(context,photo',lab)
        self.assertIn('GlinerFieldDecisionPolicy.applyWithEvidence(',lab)
        self.assertIn('GlinerProductionScanner.scan(context,photo,started,source',router)
        self.assertNotIn('WineScannerServices.get(',router)
        self.assertNotIn('scanner.scan(',router)
        self.assertIn('Could not read the label — retake or enter details manually',router)
        self.assertIn('const val BUDGET_MS=90_000L',router)

    def test_first_scan_warmup_preopens_during_framing(self):
        warmup=self.text(GL/'GlinerScanWarmup.kt')
        scanner=self.text(GL/'GlinerProductionScanner.kt')
        session=self.text(APP/'com/n8rtz/winetracker/scanner/ProductionLabelScanSession.kt')
        vm=self.text(APP/'com/n8rtz/winetracker/viewmodels/ScannerViewModel.kt')
        # Warm-up pre-opens the cold, per-scan pieces: ONNX session,
        # knowledge base and expanded producer gazetteer.
        self.assertIn('GlinerOnnxModel.open(app, status, GlinerLaneConfig())',warmup)
        self.assertIn('private suspend fun prepareModel',warmup)
        self.assertIn('ProducerGazetteer.openExpanded(app)',warmup)
        self.assertIn('session.knowledgeBase()',warmup)
        # The scanner consumes the prepared pieces single-use and still
        # falls back to opening its own when nothing was prepared.
        self.assertIn('GlinerScanWarmup.takeModel(status) ?: GlinerOnnxModel.open(context,status,laneConfig)',scanner)
        self.assertIn('GlinerScanWarmup.takeWarmSession() ?: TextLabWineExtraction.WarmSession(context)',scanner)
        # A pre-opened model is never used against a changed bundle file.
        self.assertIn('file.lastModified() != prepared.modified',warmup)
        # Triggered when Add Wine opens and again at capture/import;
        # released when a capture is dismissed.
        self.assertIn('ProductionLabelScanSession.warmUp(getApplication())',vm)
        self.assertGreaterEqual(session.count('warmUp(app)'),2)
        self.assertIn('GlinerScanWarmup.discard()',session)

    def test_ocr_crops_to_detected_text_before_reading(self):
        ocr=self.text(GL/'GlinerOcr.kt')
        runner=self.text(GL/'GlinerLabRunner.kt')
        scanner=self.text(GL/'GlinerProductionScanner.kt')
        # Detection runs first and its regions define the crop; the
        # detector is not run a second time for the recognizer.
        self.assertLess(ocr.index('detectRegions(context,bitmap,workspace,spec)'),
                        ocr.index('readMlKit(ocrBitmap,deadline)'))
        self.assertIn('val crop=textRegionCrop(detection,bitmap.width,bitmap.height)',ocr)
        self.assertIn('Bitmap.createBitmap(bitmap,crop[0],crop[1],crop[2],crop[3])',ocr)
        self.assertEqual(ocr.count('PaddleTextDetector.detect'),1)
        # Both engines read the crop; Paddle regions are shifted into
        # crop coordinates rather than re-detected.
        self.assertIn('readPaddle(context,ocrBitmap,workspace,spec,deadlineAtMs,regions)',ocr)
        self.assertIn('PointF(point.x-crop[0],point.y-crop[1])',ocr)
        # Cropping is skipped when text spans the frame or is a speck.
        self.assertIn('if(areaFraction>0.92 || areaFraction<0.03) return null',ocr)
        self.assertIn('val marginX=unionW*0.10f',ocr)
        # Diagnostics say which path a scan actually took.
        self.assertIn('croppedToTextRegion=ocr.croppedToTextRegion',runner)
        self.assertIn('if(result.croppedToTextRegion) "text_region_crop" else "full_photo_gliner"',scanner)

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
        photo_scope=lab[lab.index('suspend fun runProduction('):lab.index('/** Per-photo budget of the production scanner')]
        self.assertIn('photo.delete()',copy_scope)
        self.assertNotIn('photo.delete()',photo_scope)
        self.assertIn('true,emptyMap()',photo_scope)
        self.assertIn('deadlineAtMs',photo_scope)

    def test_gliner_v2_is_the_production_pipeline_over_the_corpus(self):
        lab=self.text(GL/'GlinerLabRunner.kt')
        self.assertIn('suspend fun runProductionVariant(',lab)
        variant=lab[lab.index('suspend fun runProductionVariant('):lab.index('private suspend fun runPhoto')]
        # Identical computation to production: default lane config,
        # production modules, production 90s per-photo deadline; the
        # corpus sample (and its references) only drive scoring.
        self.assertIn('GlinerLaneConfig(),warm',variant)
        self.assertIn('LabPostOcrModules.none.withIntegrated(),started,started+PRODUCTION_SCAN_BUDGET_MS',variant)
        self.assertIn('const val PRODUCTION_SCAN_BUDGET_MS=90_000L',lab)
        viewmodel=(ROOT/'gliner-workflow/src/appIntegration/java/com/n8rtz/winetracker/viewmodels/GlinerLabViewModel.kt').read_text()
        self.assertIn('fun runAllV2() = launchRun(only=null,custom=false,production=true)',viewmodel)
        self.assertIn('GlinerLabRunner.runProductionVariant(app,row.sample,opened,warmSession,workFolder)',viewmodel)
        self.assertIn('extractor.warmUp()',viewmodel)
        self.assertIn('if(production) "GLiNER v2" else "GLiNER v1"',viewmodel)
        screen=(ROOT/'gliner-workflow/src/appIntegration/java/com/n8rtz/winetracker/ui/screens/GlinerLabScreen.kt').read_text()
        self.assertIn('Start GLiNER v1 run',screen)
        self.assertIn('Start GLiNER v2 run (production pipeline)',screen)
        self.assertIn('priorAggregate',viewmodel)

if __name__=='__main__': unittest.main()
