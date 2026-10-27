import json,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools/corpus'))
import producer_lab_validation as suite
class ProducerLabValidationTests(unittest.TestCase):
    def test_parity_input_has_old_predictions_not_reference_targets(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.json';suite.prepare(p);items=json.loads(p.read_text())
            self.assertEqual(len(items),168)
            for i in items:self.assertEqual(set(i),{'image','rows','anchor_fields','preceding_producer_output'})
    def test_default_off_and_producer_only_lab_update(self):
        lab=ROOT/'app/src/main/java/com/yourapp/wine/textlab'
        for name in ['TextLabViewModel.kt','TextLabBatch.kt']:
            self.assertIn('repairProducer:Boolean=false',(lab/name).read_text())
        preview=(lab/'TextLabEvidencePreview.kt').read_text()
        self.assertIn('useProducerRepair:Boolean=false',preview)
        self.assertIn('ProducerRepair.recommendedConfig',preview)
        self.assertIn('requires guarded hybrid',preview)
        self.assertNotIn('FillEngine',preview)
        self.assertIn('roleCache.getOrPut(text)',(lab/'evidence/ProducerRepair.kt').read_text())
    def test_validation_rejects_mismatches(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);suite.prepare(p/'input.json')
            v=dict(images=168,mismatches=[],source_commit='b'*12,lab_synthetic_passed=True,baseline_executions=0,old_experimental_executions=0,elapsed_ms=[1]*168)
            (p/'validation.json').write_text(json.dumps(v));suite.collect(p,p/'out','b'*40)
            v['mismatches']=['changed'];(p/'validation.json').write_text(json.dumps(v))
            with self.assertRaises(AssertionError):suite.collect(p,p/'bad','b'*40)
