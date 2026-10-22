"""Host acceptance tests with saved-output fixtures, not new Android accuracy claims."""
import contextlib
import gzip
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
import rule_iteration as suite

class RuleIterationTests(unittest.TestCase):
    def test_preparation_supplies_saved_baseline_not_targets(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.json';suite.prepare(p);items=json.loads(p.read_text())
            self.assertEqual(len(items),168)
            self.assertEqual(set(items[0]),{'image','rows','baseline_fields'})
            for item in items:self.assertEqual(set(item['baseline_fields']),set(suite.FIELDS))

    def test_all_variants_gated_without_baseline_execution(self):
        old=[json.loads(line) for line in gzip.open(suite.V3,'rt')]
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);suite.prepare(p/'input.json')
            validation=dict(images=168,variants=suite.VARIANTS,failures=[],corpus_budget_ms=60000,baseline_executions=0,old_experimental_executions=0)
            (p/'validation.json').write_text(json.dumps(validation))
            rows=[dict(image=r['image'],variant=v,build='a'*12,status='complete',fields=r['fields']) for v in suite.VARIANTS for r in old]
            (p/'results.jsonl').write_text('\n'.join(json.dumps(r) for r in rows))
            suite.collect(p,p/'out','a'*40)
            result=json.loads((p/'out/scores.json').read_text())
            self.assertEqual(len(result['variants']),9)
            self.assertEqual(result['decision']['advance_candidates'],[])
            self.assertFalse(result['decision']['production_promoted'])
            for value in result['variants'].values():
                self.assertEqual(value['all_fields']['correct'],599)
                self.assertEqual(value['paired']['experimental']['complete_lost'],[])
                self.assertEqual(sum(value['paired']['experimental']['transitions'].values()),1008)
            # Missing case is a failed run, not an accuracy result or an abstention.
            (p/'results.jsonl').write_text('\n'.join(json.dumps(r) for r in rows[:-1]))
            with self.assertRaises(AssertionError):suite.collect(p,p/'bad','a'*40)
            self.assertFalse((p/'bad').exists())

    def test_default_rules_remain_off_and_extractor_not_used_for_baseline(self):
        config=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/evidence/EvidenceRankingConfig.kt').read_text()
        self.assertIn('equivalentNames:Boolean=false',config)
        self.assertIn('engineAgreement:Boolean=false',config)
        self.assertIn('cuveeRole:Boolean=false',config)
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/RuleIterationDeviceTest.kt').read_text()
        self.assertNotIn('FillEngine',source)
        self.assertNotIn('TextLabFormPreview.append',source)
        self.assertNotIn('WineScannerServices',source)
        self.assertIn('baseline_fields',source)
