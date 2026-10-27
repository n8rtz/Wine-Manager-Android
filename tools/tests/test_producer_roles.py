import json,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
import producer_roles as suite

class ProducerRoleTests(unittest.TestCase):
    def test_experiment_is_separate_and_archive_free(self):
        workflow=(ROOT/'.github/workflows/producer-refinement.yml').read_text()
        self.assertIn('&& !inputs.producer_roles',workflow)
        self.assertIn('PRODUCER_ROLES=true',workflow)
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/ProducerRoleDeviceTest.kt').read_text()
        self.assertIn('val alternatives=emptyList<String>()',source)
        self.assertNotIn('EvidenceExtractor(',source)
        repair=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/evidence/ProducerRepair.kt').read_text()
        self.assertIn('recommendedConfig get()=refinedExperiments.first()',repair)
        for v in suite.VARIANTS:self.assertIn(v,repair)

    def test_paired_report_detects_regressions_against_frozen_recommendation(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);suite.prepare(root/'input.json')
            items=json.loads((root/'input.json').read_text())
            (root/'validation.json').write_text(json.dumps(dict(images=168,variants=suite.VARIANTS,failures=[],corpus_budget_ms=60000,baseline_executions=0,old_experimental_executions=0)))
            rows=[dict(image=i['image'],variant=v,build='a'*12,status='complete',fields={f:dict(value=x,state='YELLOW' if x else 'RED') for f,x in i['anchor_fields'].items()}) for i in items for v in suite.VARIANTS]
            (root/'results.jsonl').write_text('\n'.join(map(json.dumps,rows)))
            suite.collect(root,root/'out','a'*40)
            report=json.loads((root/'out/paired-vs-recommended.json').read_text())
            for v in report['variants'].values():
                self.assertEqual(len(v['complete_lost']),1)
                self.assertEqual(len(v['complete_gained']),0)
                self.assertEqual(len(v['previously_correct_producers_lost']),5)
                self.assertEqual(len(v['newly_wrong_producers']),1)
