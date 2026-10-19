"""Offline provenance/config guards. This does NOT run the downloaded ONNX graph."""
import hashlib,json,pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
DATA=ROOT/'app/src/debug/assets/text-lab-paddle-v1'
class PaddleTextLabTests(unittest.TestCase):
    def test_dictionary_and_pin(self):
        meta=json.loads((DATA/'provenance.json').read_text())
        raw=(DATA/'ppocrv5_dict.utf8').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),meta['dictionary_sha256'])
        self.assertEqual(len(raw.decode().splitlines()),meta['dictionary_entries'])
        self.assertEqual(meta['dictionary_entries'],18383)
        self.assertEqual(len(raw),74012)
        self.assertIn('Apache License',(DATA/'LICENSE-PaddleOCR.txt').read_text())
    def test_dictionary_is_byte_exact_on_windows(self):
        import subprocess
        asset='app/src/debug/assets/text-lab-paddle-v1/ppocrv5_dict.utf8'
        attr=subprocess.check_output(['git','check-attr','text','--',asset],cwd=ROOT,text=True)
        self.assertIn(': text: unset',attr)
        raw=(ROOT/asset).read_bytes()
        self.assertNotIn(b'\r\n',raw)
        meta=json.loads((DATA/'provenance.json').read_text())
        self.assertEqual(meta['dictionary_asset'],pathlib.Path(asset).name)
        self.assertNotEqual(hashlib.sha256(raw.replace(b'\n',b'\r\n')).hexdigest(),meta['dictionary_sha256'])
        engine=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleTextRecognizer.kt').read_text()
        self.assertIn('verified(context,spec.dictionary.asset,spec.dictionary.sha,spec.dictionary.bytes)',engine)

    def test_gradle_task_avoids_java_accessor_shadowing(self):
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        task=gradle.split('abstract class PrepareTextLabPaddle',1)[1]
        for name in ('java.io.File','java.net.URI','java.nio.file.Files',
                     'java.nio.file.StandardCopyOption','java.security.MessageDigest'):
            self.assertIn('import '+name,gradle)
            self.assertNotIn(name,task)
        self.assertIn('MessageDigest.getInstance("SHA-256")',task)
        self.assertIn('Files.move(',task)

    def test_debug_only_generated_model_and_runtime(self):
        meta=json.loads((DATA/'provenance.json').read_text())
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('debugImplementation("'+meta['runtime']+'")',gradle)
        self.assertIn('selector().withBuildType("debug")',gradle)
        self.assertIn('addGeneratedSourceDirectory(prepareTextLabPaddle',gradle)
        self.assertIn(meta['model_url'],gradle)
        self.assertIn(meta['model_sha256'],gradle)
        self.assertIn('checksum mismatch',gradle)
        self.assertFalse((ROOT/'app/src/main/assets/text-lab-paddle-v1').exists())
    def test_runtime_verifies_and_cancellation_keeps_native_lease(self):
        engine=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleTextRecognizer.kt').read_text()
        self.assertIn('gate.acquire()',engine)
        self.assertIn('finally { lease.close() }',engine)
        self.assertIn('options.setTerminate(true)',engine)
        self.assertIn('stop.cancelAndJoin()',engine)
        self.assertIn('hash(temp)==expected',(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleAssets.kt').read_text())
        self.assertIn('active.run(',engine)
        self.assertNotIn('URL(',engine)
        self.assertNotIn('findProducer',engine)
    def test_failures_include_stage_and_contract_details(self):
        engine=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleTextRecognizer.kt').read_text()
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('PaddleCtc.validateInputShape(input.shape)',engine)
        self.assertIn('Actual output shape ${shape.contentToString()}',engine)
        self.assertIn('$stage: ${error.javaClass.simpleName}',engine)
        self.assertIn('error.message.orEmpty().take(2000)',runner)
        self.assertNotIn('Check model assets or use a shorter single-line crop',runner)

    def test_comparison_preserves_partial_readings(self):
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        vm=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabViewModel.kt').read_text()
        self.assertIn('journal.add(spec.id,"candidate_summary",status',runner)
        self.assertIn('batch.finishPending(',vm)
        self.assertIn('csv=batch.save(csvFolder)',vm)
        self.assertNotIn('BenchmarkScoring',runner)
        self.assertNotIn('FieldExtractor',runner)

    def test_detector_is_genuine_and_build_time_only(self):
        meta=json.loads((DATA/'provenance.json').read_text())
        det=meta['detector']
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        for value in (det['model_url'],det['model_sha256'],str(det['model_bytes'])):
            self.assertIn(value,gradle)
        self.assertGreater(det['model_bytes'],0)
        engine=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleTextDetector.kt').read_text()
        self.assertIn(det['model_sha256'],(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleCandidates.kt').read_text())
        self.assertIn('PaddleAssets.verified(context,spec.detector.asset,spec.detector.sha,spec.detector.bytes)',engine)
        self.assertIn('gate.acquire()',engine)
        self.assertIn('stop.cancelAndJoin()',engine)
        self.assertIn('finally { lease.close() }',engine)
        self.assertNotIn('URL(',engine)

    def test_full_label_pipeline_has_no_mlkit_box_dependency(self):
        pipeline=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleLabelPipeline.kt').read_text()
        self.assertIn('PaddleTextDetector.detect(context,base,workspace,gate,spec)',pipeline)
        self.assertIn('PaddleTextRecognizer.recognize(context,base,workspace,deadline,gate,region.quadBase,spec)',pipeline)
        self.assertIn('if(detection.regions.lines.isEmpty()) return Outcome("no_regions"',pipeline)
        self.assertIn('original_box_normalized',pipeline)
        self.assertIn('base_box_normalized',pipeline)
        self.assertNotIn('tokens.',pipeline)
        self.assertNotIn('BenchmarkScoring',pipeline)
        decoder=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleDbPostprocess.kt').read_text()
        self.assertIn('count<=4096',decoder)
        self.assertIn('ids.take(128)',decoder)
        self.assertIn('PaddleDetectionGeometry.MAX_LINES',decoder)
        self.assertIn('finally { binary.release()',decoder)

    def test_incremental_reports_survive_timeout(self):
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('journal.add(spec.id,"candidate_summary",status',runner)
        self.assertIn('status=if(done==null) "timed_out"',runner)
        state=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabViewModel.kt').read_text()
        self.assertIn('detectLines:Boolean=true',state)

    def test_v6_dictionary_and_profiles(self):
        import subprocess
        raw=(DATA/'ppocrv6_dict.utf8').read_bytes()
        self.assertEqual(len(raw),74947)
        self.assertEqual(len(raw.decode().splitlines()),18708)
        self.assertEqual(hashlib.sha256(raw).hexdigest(),'b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d')
        attr=subprocess.check_output(['git','check-attr','text','--',str(DATA/'ppocrv6_dict.utf8')],cwd=ROOT,text=True)
        self.assertIn(': text: unset',attr)
        manifest=json.loads((DATA/'candidate-models.json').read_text())
        config=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleCandidates.kt').read_text()
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        for model in manifest['v6_models']:
            for value in (model['sha256'],str(model['bytes']),model['asset']):
                self.assertIn(value,config)
                self.assertIn(value,gradle)
            self.assertIn(model['url'],gradle)
        self.assertEqual(manifest['asset_budget_enforced'],False)

    def test_sessions_are_released_and_all_candidates_export_together(self):
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        pipeline=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleLabelPipeline.kt').read_text()
        ui=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/OcrTextLabActivity.kt').read_text()
        self.assertIn('for(spec in PaddleCandidates.selected(journal.selected))',runner)
        self.assertIn('PaddleTextRecognizer.release(gate)',runner)
        self.assertIn('withContext(NonCancellable) { PaddleTextDetector.release(gate) }',pipeline)
        self.assertIn('PaddleCandidates.all.forEach',ui)
        self.assertIn('ActivityResultContracts.CreateDocument("text/csv")',ui)
        self.assertNotIn('80*1024*1024',runner)

    def test_experimental_timeout_is_60_seconds_without_changing_normal_scan(self):
        root=ROOT/'app/src/debug/java/com/yourapp/wine/textlab'
        journal=(root/'TextLabJournal.kt').read_text()
        runner=(root/'TextLabRunner.kt').read_text()
        ui=(root/'OcrTextLabActivity.kt').read_text()
        self.assertIn('const val OCR_MS = 60_000L',journal)
        self.assertIn('"candidate_budget_ms" to TextLabBudgets.OCR_MS.toString()',journal)
        self.assertIn('"mlkit_budget_ms" to TextLabBudgets.OCR_MS.toString()',journal)
        self.assertIn('ScanDeadline(mlStarted+TextLabBudgets.OCR_MS)',runner)
        self.assertIn('ScanDeadline(caseStart+TextLabBudgets.OCR_MS)',runner)
        self.assertIn('${TextLabBudgets.OCR_MS / 1000}',ui)
        self.assertIn('const val BUDGET_MS = 8_000L',(ROOT/'app/src/main/java/com/yourapp/wine/ocr/ScanDeadline.kt').read_text())

    def test_probability_diagnostics_precede_db_failure(self):
        engine=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleTextDetector.kt').read_text()
        self.assertLess(engine.index('"diagnostic_stage" to "detector_output"'),engine.index('PaddleProbabilityMap.validateAndClamp'))
        self.assertLess(engine.index('PaddleProbabilityMap.validateAndClamp'),engine.index('PaddleDbPostprocess.decode'))
        self.assertIn('buffer.remaining()==values.size',engine)
        pipeline=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleLabelPipeline.kt').read_text()
        self.assertIn('values["diagnostic_stage"] ?: "detection"',pipeline)

    def test_multi_image_picker_and_per_image_metadata(self):
        root=ROOT/'app/src/debug/java/com/yourapp/wine/textlab'
        ui=(root/'OcrTextLabActivity.kt').read_text()
        vm=(root/'TextLabViewModel.kt').read_text()
        batch=(root/'TextLabBatch.kt').read_text()
        self.assertIn('ActivityResultContracts.OpenMultipleDocuments()',ui)
        self.assertIn('Run all ${state.images.size} images',ui)
        self.assertIn('for((index,image) in snapshot.images.withIndex())',vm)
        self.assertIn('val journal=batch.journals[index]',vm)
        self.assertIn('requireNotNull(image.selection)',vm)
        self.assertIn('batch.save(csvFolder)',vm)
        self.assertIn('imageStatus="import_failed"',vm)
        self.assertIn('imageStatus="cancelled"',vm)
        self.assertIn('journal.metadata+it',batch)
        for field in ('batch_id','image_id','image_name','image_index','image_count'):
            self.assertIn('"'+field+'"',batch)
        self.assertNotIn('async(',vm)
