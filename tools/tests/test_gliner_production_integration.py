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

    def test_ocr_crops_conservatively_or_reads_full_frame(self):
        ocr=self.text(GL/'GlinerOcr.kt')
        runner=self.text(GL/'GlinerLabRunner.kt')
        scanner=self.text(GL/'GlinerProductionScanner.kt')
        # Detection runs first and its regions drive the line recognizer;
        # the detector is not run a second time for the recognizer.
        self.assertLess(ocr.index('detectRegions(context,bitmap,workspace,spec)'),
                        ocr.index('readMlKit(ocrBitmap,deadline)'))
        self.assertEqual(ocr.count('PaddleTextDetector.detect'),1)
        # The crop is conservative: half a text-union of slack on every
        # side keeps detector-missed neighbour lines inside (the tight
        # 10% crop amputated them and cost fields), and cropping only
        # happens when the slackened crop still excludes >=20% of the
        # frame — anything closer reads the full photo unchanged.
        self.assertIn('val marginX=unionW*0.5f',ocr)
        self.assertIn('val marginY=unionH*0.5f',ocr)
        self.assertIn('if(areaFraction>0.80 || areaFraction<0.03) return null',ocr)
        self.assertNotIn('unionW*0.10f',ocr)
        self.assertNotIn('unionH*0.10f',ocr)
        # Both engines read the crop; Paddle regions are shifted into
        # crop coordinates rather than re-detected.
        self.assertIn('readPaddle(context,ocrBitmap,workspace,spec,deadlineAtMs,regions)',ocr)
        self.assertIn('PointF(point.x-crop[0],point.y-crop[1])',ocr)
        # The full-size bitmap is released once the crop exists, freeing
        # BitmapWorkspace's second slot for the recognizer warp (leaving
        # it owned once silently zeroed PP-OCRv6 on cropped photos).
        release=ocr.index('if(crop!=null) workspace.release(bitmap)')
        self.assertLess(ocr.index('Bitmap.createBitmap(bitmap,crop[0],crop[1],crop[2],crop[3])'),release)
        self.assertLess(release,ocr.index('readMlKit(ocrBitmap,deadline)'))
        # Diagnostics say which path a scan actually took.
        self.assertIn('croppedToTextRegion=ocr.croppedToTextRegion',runner)
        self.assertIn('if(result.croppedToTextRegion) "text_region_crop" else "full_photo_gliner"',scanner)

    def test_paddle_sessions_stay_open_across_photos(self):
        ocr=self.text(GL/'GlinerOcr.kt')
        warmup=self.text(GL/'GlinerScanWarmup.kt')
        # Release+reopen per photo re-hashed ~138 MB of model files and
        # re-created both ONNX sessions every photo (~1 s/photo in the lab
        # A/B) with identical outputs; the warm-up pre-creates the
        # sessions precisely so scans reuse them instead.
        self.assertNotIn('PaddleTextDetector.release',ocr)
        self.assertNotIn('PaddleTextRecognizer.release',ocr)
        self.assertIn('PaddleTextDetector.warm(app, gate, spec)',warmup)
        self.assertIn('PaddleTextRecognizer.warm(app, gate, spec)',warmup)
        # A systematic Paddle failure must show in the outcome note, not
        # read as a silent "0 lines".
        self.assertIn('var firstError:String?=null',ocr)
        self.assertIn('" · paddle: $it"',ocr)

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

    def test_v1_results_importer_rescores_export_against_current_references(self):
        viewmodel=(ROOT/'gliner-workflow/src/appIntegration/java/com/n8rtz/winetracker/viewmodels/GlinerLabViewModel.kt').read_text()
        self.assertIn('fun importV1Results(uri:Uri)',viewmodel)
        self.assertIn('require(document.optString("pipeline")=="gliner-lane-v1")',viewmodel)
        # Rescored from fields against the live sample targets, not
        # trusting the export's stored outcomes.
        self.assertIn('PhoneCorpusMath.outcome(row.sample.target(f),fields[f].orEmpty(),failed)',viewmodel)
        self.assertIn('aggregate=aggregateGlinerRows(samples,stopped=false)',viewmodel)
        screen=(ROOT/'gliner-workflow/src/appIntegration/java/com/n8rtz/winetracker/ui/screens/GlinerLabScreen.kt').read_text()
        self.assertIn('Import saved GLiNER v1 results (JSON)',screen)
        activity=(ROOT/'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerLabActivity.kt').read_text()
        self.assertIn('uri?.let(vm::importV1Results)',activity)

    def test_bundled_v1_baseline_is_baked_in_and_auto_loaded(self):
        asset=ROOT/'app/src/main/assets/gliner-v1-baseline/results.json'
        self.assertTrue(asset.exists())
        import json as _json
        doc=_json.loads(asset.read_text())
        self.assertEqual(doc['pipeline'],'gliner-lane-v1')
        self.assertEqual(len(doc['images']),193)
        self.assertTrue(all('fields' in im and 'total_ms' in im for im in doc['images']))
        viewmodel=(ROOT/'gliner-workflow/src/appIntegration/java/com/n8rtz/winetracker/viewmodels/GlinerLabViewModel.kt').read_text()
        self.assertIn('BUNDLED_V1_BASELINE_ASSET="gliner-v1-baseline/results.json"',viewmodel)
        self.assertIn('bundledV1Baseline(seeded.samples)',viewmodel)
        self.assertIn('Bundled GLiNER v1 baseline loaded',viewmodel)

if __name__=='__main__': unittest.main()
