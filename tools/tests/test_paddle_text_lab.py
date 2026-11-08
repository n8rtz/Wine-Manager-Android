"""Offline provenance/config guards. This does NOT run the downloaded ONNX graph."""
import hashlib,json,pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
DATA=ROOT/'app/src/main/assets/text-lab-paddle-v1'
MAIN_DATA=ROOT/'app/src/main/assets/text-lab-paddle-v1'
class PaddleTextLabTests(unittest.TestCase):
    def test_dictionary_and_pin(self):
        meta=json.loads((DATA/'production-models.json').read_text())
        raw=(DATA/'ppocrv6_dict.utf8').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),meta['dictionary_sha256'])
        self.assertEqual(len(raw.decode().splitlines()),meta['dictionary_entries'])
        self.assertEqual(meta['dictionary_entries'],18708)
        self.assertEqual(len(raw),74947)
        self.assertIn('Apache License',(MAIN_DATA/'LICENSE-PaddleOCR.txt').read_text())
    def test_dictionary_is_byte_exact_on_windows(self):
        import subprocess
        asset='app/src/main/assets/text-lab-paddle-v1/ppocrv6_dict.utf8'
        attr=subprocess.check_output(['git','check-attr','text','--',asset],cwd=ROOT,text=True)
        self.assertIn(': text: unset',attr)
        raw=(ROOT/asset).read_bytes()
        self.assertNotIn(b'\r\n',raw)
        meta=json.loads((DATA/'production-models.json').read_text())
        self.assertEqual(meta['dictionary_asset'],pathlib.Path(asset).name)
        self.assertNotEqual(hashlib.sha256(raw.replace(b'\n',b'\r\n')).hexdigest(),meta['dictionary_sha256'])
        engine=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleTextRecognizer.kt').read_text()
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

    def test_shared_runtime_and_debug_extra_models(self):
        meta=json.loads((DATA/'production-models.json').read_text())
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('implementation("'+meta['runtime']+'")',gradle)
        self.assertNotIn('PrepareIndraLab',gradle)
        self.assertNotIn('textLabExtraPaddleAssets',gradle)
        self.assertIn(meta['v6_models'][1]['url'],gradle)
        self.assertIn(meta['v6_models'][1]['sha256'],gradle)
        self.assertIn('checksum mismatch',gradle)
        self.assertTrue((MAIN_DATA/'ppocrv6_dict.utf8').exists())
        self.assertIn('addGeneratedSourceDirectory(preparePaddleMedium',gradle)
    def test_runtime_verifies_and_cancellation_keeps_native_lease(self):
        engine=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleTextRecognizer.kt').read_text()
        self.assertIn('gate.acquire()',engine)
        self.assertIn('finally { lease.close() }',engine)
        self.assertIn('options.setTerminate(true)',engine)
        self.assertIn('stop.cancelAndJoin()',engine)
        self.assertIn('hash(temp)==expected',(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleAssets.kt').read_text())
        self.assertIn('active.run(',engine)
        self.assertNotIn('URL(',engine)
        self.assertNotIn('findProducer',engine)
    def test_failures_include_stage_and_contract_details(self):
        engine=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleTextRecognizer.kt').read_text()
        runner=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('PaddleCtc.validateInputShape(input.shape)',engine)
        self.assertIn('Actual output shape ${shape.contentToString()}',engine)
        self.assertIn('$stage: ${error.javaClass.simpleName}',engine)
        self.assertIn('error.message.orEmpty().take(2000)',runner)
        self.assertNotIn('Check model assets or use a shorter single-line crop',runner)

    def test_detector_is_genuine_and_build_time_only(self):
        meta=json.loads((DATA/'production-models.json').read_text())
        det=meta['v6_models'][0]
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        for value in (det['url'],det['sha256'],str(det['bytes'])):
            self.assertIn(value,gradle)
        self.assertGreater(det['bytes'],0)
        engine=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleTextDetector.kt').read_text()
        self.assertIn(det['sha256'],(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleCandidates.kt').read_text())
        self.assertIn('PaddleAssets.verified(context,spec.detector.asset,spec.detector.sha,spec.detector.bytes)',engine)
        self.assertIn('gate.acquire()',engine)
        self.assertIn('stop.cancelAndJoin()',engine)
        self.assertIn('finally { lease.close() }',engine)
        self.assertNotIn('URL(',engine)

    def test_full_label_pipeline_has_no_mlkit_box_dependency(self):
        pipeline=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleLabelPipeline.kt').read_text()
        self.assertIn('PaddleTextDetector.detect(context,base,workspace,gate,spec)',pipeline)
        self.assertIn('PaddleTextRecognizer.recognize(context,base,workspace,deadline,gate,region.quadBase,spec)',pipeline)
        self.assertIn('if(detection.regions.lines.isEmpty()) return Outcome("no_regions"',pipeline)
        self.assertIn('original_box_normalized',pipeline)
        self.assertIn('base_box_normalized',pipeline)
        self.assertNotIn('tokens.',pipeline)
        self.assertNotIn('BenchmarkScoring',pipeline)
        decoder=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleDbPostprocess.kt').read_text()
        self.assertIn('count<=4096',decoder)
        self.assertIn('ids.take(128)',decoder)
        self.assertIn('PaddleDetectionGeometry.MAX_LINES',decoder)
        self.assertIn('finally { binary.release()',decoder)

    def test_v6_dictionary_and_profiles(self):
        import subprocess
        raw=(MAIN_DATA/'ppocrv6_dict.utf8').read_bytes()
        self.assertEqual(len(raw),74947)
        self.assertEqual(len(raw.decode().splitlines()),18708)
        self.assertEqual(hashlib.sha256(raw).hexdigest(),'b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d')
        attr=subprocess.check_output(['git','check-attr','text','--',str(MAIN_DATA/'ppocrv6_dict.utf8')],cwd=ROOT,text=True)
        self.assertIn(': text: unset',attr)
        manifest=json.loads((DATA/'production-models.json').read_text())
        config=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleCandidates.kt').read_text()
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        for model in manifest['v6_models']:
            for value in (model['sha256'],str(model['bytes']),model['asset']):
                self.assertIn(value,config)
                self.assertIn(value,gradle)
            self.assertIn(model['url'],gradle)
        self.assertEqual(manifest['asset_budget_enforced'],False)

    def test_probability_diagnostics_precede_db_failure(self):
        engine=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleTextDetector.kt').read_text()
        self.assertLess(engine.index('"diagnostic_stage" to "detector_output"'),engine.index('PaddleProbabilityMap.validateAndClamp'))
        self.assertLess(engine.index('PaddleProbabilityMap.validateAndClamp'),engine.index('PaddleDbPostprocess.decode'))
        self.assertIn('buffer.remaining()==values.size',engine)
        pipeline=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleLabelPipeline.kt').read_text()
        self.assertIn('values["diagnostic_stage"] ?: "detection"',pipeline)

    def test_only_production_model_profiles_and_recipes_remain(self):
        config=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/PaddleCandidates.kt').read_text()
        self.assertEqual(config.count('PaddleCandidate("'),1)
        self.assertIn('PaddleCandidate("candidate_3"',config)
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        for retired in ('v6-small-det.onnx','inference.onnx','detection.onnx','huggingface.co','PrepareIndraLab','mediumOnly'):
            self.assertNotIn(retired,gradle)
        self.assertFalse((ROOT/'app/src/debug/assets/indra-v1').exists())
        self.assertFalse((ROOT/'app/src/main/java/com/yourapp/wine/ocr/PaddleEngineFactory.kt').exists())
        self.assertFalse((ROOT/'app/src/main/java/com/yourapp/wine/benchmark/BenchmarkPreprocess.kt').exists())
        self.assertFalse(list((ROOT/'app/src/main/java/com/yourapp/wine/textlab').glob('Indra*.kt')))

    def test_review_corpus_is_observed_data_not_ground_truth(self):
        data=json.loads((ROOT/'app/src/testDebug/resources/textlab/production-review.json').read_text())
        self.assertIn('NOT ground truth',data['warning'])
        self.assertEqual(len(data['cases']),12)
        self.assertEqual({x['engine'] for x in data['cases']},{'mlkit_reference','candidate_3'})
        self.assertEqual(sum(len(x['readings']) for x in data['cases']),103)
