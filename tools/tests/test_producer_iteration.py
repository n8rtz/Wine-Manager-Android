import json,tempfile,sys,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools/corpus'))
import producer_iteration as suite
class ProducerIterationTests(unittest.TestCase):
    def test_input_contains_no_reference_answers(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.json';suite.prepare(p);items=json.loads(p.read_text())
            self.assertEqual(len(items),168)
            for i in items:
                self.assertEqual(set(i),{'image','rows','anchor_fields','producer_alternatives'})
                self.assertLessEqual(len(i['producer_alternatives']),64)
    def test_collector_preserves_nonproducer_fields_and_uses_new_target(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);suite.prepare(p/'input.json');items=json.loads((p/'input.json').read_text())
            (p/'validation.json').write_text(json.dumps(dict(images=168,variants=suite.VARIANTS,failures=[],corpus_budget_ms=60000,baseline_executions=0,old_experimental_executions=0)))
            rows=[dict(image=i['image'],variant=v,build='a'*12,status='complete',fields={f:dict(value=x,state='YELLOW' if x else 'RED') for f,x in i['anchor_fields'].items()}) for i in items for v in suite.VARIANTS]
            (p/'results.jsonl').write_text('\n'.join(map(json.dumps,rows)))
            s=suite.collect(p,p/'out','a'*40)
            decision=json.loads((p/'out/decision.json').read_text())
            self.assertFalse(decision['production_promoted'])
            self.assertEqual(decision['meets_user_target'],[])
            for v in suite.VARIANTS:
                g=s['systems'][v]['completion'];self.assertEqual(g['complete'],32);self.assertEqual(g['two_or_more_errors'],81);self.assertFalse(g['passes'])
            rows[0]['fields']['wine_name']['value']='changed'
            (p/'results.jsonl').write_text('\n'.join(map(json.dumps,rows)))
            with self.assertRaises(AssertionError):suite.collect(p,p/'bad','a'*40)
            self.assertFalse((p/'bad').exists())
    def test_refined_batch_has_four_new_names(self):
        import producer_refinement
        self.assertEqual(len(producer_refinement.VARIANTS),4)
        self.assertFalse(set(producer_refinement.VARIANTS)&set(suite.VARIANTS))
        workflow=(ROOT/'.github/workflows/producer-refinement.yml').read_text()
        self.assertIn('PRODUCER_REFINEMENT=true',workflow)
        self.assertIn('set -euo pipefail',workflow)
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/ProducerRefinementDeviceTest.kt').read_text()
        self.assertIn('ProducerRepair.refinedExperiments',source)
        self.assertNotIn('EvidenceExtractor(',source)

    def test_device_only_runs_new_producer_logic(self):
        s=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/ProducerRepairDeviceTest.kt').read_text()
        for forbidden in ['FillEngine','EvidenceExtractor(', 'TextLabFormPreview.append','WineScannerServices']:self.assertNotIn(forbidden,s)
        self.assertIn('repair.repair(',s)
        production_roots=[ROOT/'app/src/main/java/com/yourapp/wine/capture',ROOT/'app/src/main/java/com/yourapp/wine/fill']
        for root in production_roots:
            for p in root.rglob('*.kt'):self.assertNotIn('ProducerRepair',p.read_text())
