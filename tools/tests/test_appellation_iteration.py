"""Host integrity tests; actual rule behavior is asserted by the Android device test."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
import appellation_iteration as suite

class AppellationIterationTests(unittest.TestCase):
    def test_baseline_preserved_fixture_and_four_candidate_collector(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);suite.prepare(p/'input.json');items=json.loads((p/'input.json').read_text())
            validation=dict(images=168,variants=suite.VARIANTS,failures=[],corpus_budget_ms=60000,baseline_executions=0,old_experimental_executions=0)
            (p/'validation.json').write_text(json.dumps(validation))
            rows=[dict(image=r['image'],variant=v,build='b'*12,status='complete',fields={f:dict(value=x,state='YELLOW' if x else 'RED') for f,x in r['baseline_fields'].items()}) for v in suite.VARIANTS for r in items]
            (p/'results.jsonl').write_text('\n'.join(map(json.dumps,rows)))
            suite.collect(p,p/'out','b'*40)
            result=json.loads((p/'out/scores.json').read_text())
            self.assertEqual(len(result['variants']),4)
            self.assertEqual(result['decision']['advance_candidates'],[])
            for value in result['variants'].values():
                self.assertEqual(value['all_fields']['correct'],597)
                self.assertEqual(value['paired']['lab_baseline']['complete_lost'],[])
            rows[0]['fields']['producer']['value']='must reject'
            (p/'results.jsonl').write_text('\n'.join(map(json.dumps,rows)))
            with self.assertRaises(AssertionError):suite.collect(p,p/'bad','b'*40)
            self.assertFalse((p/'bad').exists())

    def test_only_new_fallback_executed_and_defaults_untouched(self):
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/AppellationFallbackDeviceTest.kt').read_text()
        for forbidden in ('EvidenceExtractor(', 'FillEngine', 'WineScannerServices', 'TextLabFormPreview.append'):
            self.assertNotIn(forbidden,source)
        self.assertIn('fallback.combine(baseline,records,config)',source)
        workflow=(ROOT/'.github/workflows/appellation-iteration.yml').read_text()
        self.assertIn('set -euo pipefail',workflow)
        self.assertIn('APPELLATION_ITERATION=true',workflow)
        production_roots = [ROOT/'app/src/main/java/com/yourapp/wine/capture', ROOT/'app/src/main/java/com/yourapp/wine/fill']
        production_sources = [p for root in production_roots for p in root.rglob('*.kt')]
        self.assertFalse(any('AppellationFallback' in p.read_text() for p in production_sources))
