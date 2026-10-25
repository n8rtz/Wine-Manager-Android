"""Validation/scoring fixtures only; Android executes the actual search algorithms."""
import copy
import gzip
import json
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
from score_search_sweep import CONFIGS, V3, validate, collect
from replay_saved_evidence import prepare

class SearchSweepTests(unittest.TestCase):
    def fixture(self):
        old=[json.loads(line) for line in gzip.open(V3,'rt')]
        records=[]
        for name,(c,b,d,e) in CONFIGS.items():
            for index,r in enumerate(old):
                row=dict(image=r['image'],variant=name,build='a'*12,candidate_limit=c,beam_limit=b,
                         diversify=d,exhaustive_cap=e,elapsed_ms=1,status='complete',fields=r['fields'],trace_counts={},trace=[])
                if e is not None and index>=8:
                    row.update(status='skipped_space_limit',combinations=20001)
                    del row['fields']
                records.append(row)
        validation=dict(images=168,variants=list(CONFIGS),baseline_executions=0,old_experimental_executions=0,
                        corpus_budget_ms=60000,failures=[])
        return records,validation,{r['image'] for r in old}

    def test_strict_inventory_and_configuration_gate(self):
        rows,v,images=self.fixture()
        validate(rows,v,images,'a'*40)
        bad=copy.deepcopy(rows);bad[0]['beam_limit']=999
        with self.assertRaises(AssertionError):validate(bad,v,images,'a'*40)
        with self.assertRaises(AssertionError):validate(rows[:-1],v,images,'a'*40)
        bad=copy.deepcopy(rows);bad[0]['status']='skipped_space_limit';bad[0]['combinations']=20001
        with self.assertRaises(AssertionError):validate(bad,v,images,'a'*40)
        bad=copy.deepcopy(rows);bad[-1]['combinations']=20000
        with self.assertRaises(AssertionError):validate(bad,v,images,'a'*40)

    def test_identical_output_fixture_and_same_subset_comparators(self):
        rows,v,images=self.fixture()
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);prepare(root/'input.json')
            (root/'validation.json').write_text(json.dumps(v))
            (root/'results.jsonl').write_text('\n'.join(json.dumps(r) for r in rows))
            collect(root,root/'out','a'*40)
            scores=json.loads((root/'out/scores.json').read_text())
            for name,p in scores['variants'].items():
                self.assertEqual(p['revised']['all_fields'],p['saved_v3_same_subset']['all_fields'])
                if name=='exhaustive-small':
                    self.assertEqual(p['completed'],8)
                    self.assertEqual(p['skipped'],160)
                    self.assertEqual(p['saved_v3_same_subset']['image_results']['images'],8)
                    self.assertEqual(p['saved_lab_baseline_same_subset']['image_results']['images'],8)
                else:
                    self.assertEqual(p['completed'],168)
                    self.assertEqual(p['revised']['all_fields']['correct'],599)

    def test_production_and_saved_baseline_not_executed(self):
        test=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/SearchSweepDeviceTest.kt').read_text()
        self.assertNotIn('FillEngine',test)
        self.assertNotIn('TextLabFormPreview.append',test)
        self.assertNotIn('WineScannerServices',test)
        config=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceSearchConfig.kt').read_text()
        for name in CONFIGS:self.assertIn('"'+name+'"',config)
        self.assertIn('candidateLimit:Int=16,val beamLimit:Int=64',config)
        self.assertIn('diversify:Boolean=false',config)
