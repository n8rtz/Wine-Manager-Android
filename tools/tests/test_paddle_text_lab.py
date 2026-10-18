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
        self.assertLess(meta['model_bytes']+len(raw),80*1024*1024)
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
        self.assertIn('verified(context,"ppocrv5_dict.utf8",DICT_HASH,74012)',engine)

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
        self.assertIn('error.message?.take(1500)',runner)
        self.assertNotIn('Check model assets or use a shorter single-line crop',runner)

    def test_comparison_preserves_partial_readings(self):
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('partial=buildString',runner)
        self.assertIn('PaddleTextRecognizer.recognize(context,crop,workspace,deadline,WineScannerServices.nativeGate)',runner)
        self.assertIn('return completed ?: partial.takeIf',runner)
        self.assertNotIn('BenchmarkScoring',runner)
        self.assertNotIn('FieldExtractor',runner)

    def test_detector_is_genuine_and_build_time_only(self):
        meta=json.loads((DATA/'provenance.json').read_text())
        det=meta['detector']
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        for value in (det['model_url'],det['model_sha256'],str(det['model_bytes'])):
            self.assertIn(value,gradle)
        self.assertLess(meta['model_bytes']+det['model_bytes']+74012,80*1024*1024)
        engine=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleTextDetector.kt').read_text()
        self.assertIn(det['model_sha256'],engine)
        self.assertIn('PaddleAssets.verified(context,"detection.onnx"',engine)
        self.assertIn('gate.acquire()',engine)
        self.assertIn('stop.cancelAndJoin()',engine)
        self.assertIn('finally { lease.close() }',engine)
        self.assertNotIn('URL(',engine)

    def test_full_label_pipeline_has_no_mlkit_box_dependency(self):
        pipeline=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleLabelPipeline.kt').read_text()
        self.assertIn('PaddleTextDetector.detect(context,base,workspace,gate)',pipeline)
        self.assertIn('PaddleTextRecognizer.recognize(context,base,workspace,deadline,gate,region.quadBase)',pipeline)
        self.assertIn('No text regions detected. Recognition was not run',pipeline)
        self.assertIn('Original normalized box',pipeline)
        self.assertIn('Base-crop normalized box',pipeline)
        self.assertNotIn('tokens.',pipeline)
        self.assertNotIn('BenchmarkScoring',pipeline)
        decoder=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/PaddleDbPostprocess.kt').read_text()
        self.assertIn('count<=4096',decoder)
        self.assertIn('ids.take(128)',decoder)
        self.assertIn('PaddleDetectionGeometry.MAX_LINES',decoder)
        self.assertIn('finally { binary.release()',decoder)

    def test_incremental_reports_survive_timeout(self):
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('update -> partial +=',runner)
        self.assertIn('return completed ?: partial.takeIf',runner)
        state=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabViewModel.kt').read_text()
        self.assertIn('detectLines:Boolean=true',state)
