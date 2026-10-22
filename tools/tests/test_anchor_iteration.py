"""Frozen-input provenance and host acceptance, not a substitute for Android execution."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
import anchor_iteration as suite

class AnchorIterationTests(unittest.TestCase):
    def test_preparation_has_component_values_without_reference_answers(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.json';suite.prepare(p);items=json.loads(p.read_text())
            self.assertEqual(len(items),168)
            for item in items:
                self.assertEqual(set(item),{'image','rows','baseline_fields','revised_fields'})
                self.assertEqual(set(item['baseline_fields']),set(item['revised_fields']))
                self.assertTrue(all(isinstance(v,str) for v in item['revised_fields'].values()))

    def test_collector_checks_protected_fields_and_frozen_input(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);suite.prepare(p/'input.json');items=json.loads((p/'input.json').read_text())
            validation=dict(images=168,variants=suite.VARIANTS,failures=[],corpus_budget_ms=60000,baseline_executions=0,old_experimental_executions=0)
            (p/'validation.json').write_text(json.dumps(validation))
            rows=[]
            for item in items:
                values=dict(item['revised_fields'])
                for f in ('producer','vintage'):values[f]=item['baseline_fields'][f]
                for v in suite.VARIANTS:
                    rows.append(dict(image=item['image'],variant=v,build='c'*12,status='complete',fields={f:dict(value=x,state='YELLOW' if x else 'RED') for f,x in values.items()}))
            (p/'results.jsonl').write_text('\n'.join(map(json.dumps,rows)))
            suite.collect(p,p/'out','c'*40)
            result=json.loads((p/'out/scores.json').read_text())
            self.assertEqual(len(result['variants']),4)
            self.assertEqual(result['saved_revised_variant'],'rules-001')
            self.assertEqual(result['decision']['advance_candidates'],[])
            self.assertFalse(result['decision']['production_promoted'])
            self.assertEqual(result['saved_revised_sha256'],suite.digest(suite.REVISED))
            rows[0]['fields']['region']['value']='Must reject'
            (p/'results.jsonl').write_text('\n'.join(map(json.dumps,rows)))
            with self.assertRaises(AssertionError):suite.collect(p,p/'bad','c'*40)
            self.assertFalse((p/'bad').exists())

    def test_device_does_not_execute_old_components(self):
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/AnchorHybridDeviceTest.kt').read_text()
        for forbidden in ('EvidenceExtractor(', 'FillEngine', 'WineScannerServices', 'TextLabFormPreview.append'):
            self.assertNotIn(forbidden,source)
        self.assertIn('hybrid.combine(baseline,revised,records,config)',source)
        self.assertIn('revised_fields',source)
        workflow=(ROOT/'.github/workflows/anchor-iteration.yml').read_text()
        self.assertIn('run_anchor_and_appellation.sh',workflow)
        self.assertIn('emulator-boot-timeout: 600',workflow)
        script=(ROOT/'tools/corpus/run_anchor_and_appellation.sh').read_text()
        self.assertIn('ANCHOR_ITERATION=true',script)
        self.assertIn('APPELLATION_ITERATION=true',script)
        self.assertIn('set -euo pipefail',workflow)
