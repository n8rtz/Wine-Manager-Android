"""Offline provenance/config guards. This does NOT run the downloaded ONNX graph."""
import hashlib,json,pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
DATA=ROOT/'app/src/debug/assets/text-lab-paddle-v1'
class PaddleTextLabTests(unittest.TestCase):
    def test_dictionary_and_pin(self):
        meta=json.loads((DATA/'provenance.json').read_text())
        raw=(DATA/'ppocrv5_dict.txt').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),meta['dictionary_sha256'])
        self.assertEqual(len(raw.decode().splitlines()),meta['dictionary_entries'])
        self.assertEqual(meta['dictionary_entries'],18383)
        self.assertLess(meta['model_bytes']+len(raw),80*1024*1024)
        self.assertIn('Apache License',(DATA/'LICENSE-PaddleOCR.txt').read_text())
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
        self.assertIn('hash(temp)==expected',engine)
        self.assertIn('active.run(',engine)
        self.assertNotIn('URL(',engine)
        self.assertNotIn('findProducer',engine)
    def test_comparison_preserves_partial_readings(self):
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('partial=buildString',runner)
        self.assertIn('PaddleTextRecognizer.recognize(context,crop,workspace,deadline,WineScannerServices.nativeGate)',runner)
        self.assertIn('return completed ?: partial.takeIf',runner)
        self.assertNotIn('BenchmarkScoring',runner)
        self.assertNotIn('FieldExtractor',runner)
