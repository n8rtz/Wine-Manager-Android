"""Test exploratory selector isolation, provenance and repeatable saved-data diagnostics."""
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
import selection_policies as policy
import comprehensive_diagnostics as diagnostics

class SelectionPolicyTests(unittest.TestCase):
    def setUp(self):
        self.a=dict(producer='Domaine des Asters',wine_name='Cuvée Lune',country='France',region='Bordeaux',subregion='Margaux',vintage='2018')
        self.b=dict(producer='Domaine des Étoiles',wine_name='Cuvée Soleil',country='Italy',region='Piedmont',subregion='Alba',vintage='2019')

    def test_fixed_grid_unique_and_source_only(self):
        configs=policy.configs()
        self.assertEqual(len(configs),107)
        self.assertEqual(len({c['id'] for c in configs}),107)
        for c in configs:
            output,owners=policy.select(c,self.a,self.b,[])
            self.assertEqual(set(output),set(policy.FIELDS))
            for f in policy.FIELDS:
                self.assertIn(output[f],('',self.a[f],self.b[f]))
                self.assertIn(owners[f],(-1,0,1))
            if c['kind']!='fields':
                self.assertLessEqual(len({owners[f] for f in policy.GEO}),1)

    def test_agreement_abstains_on_conflict_not_unknown_credit(self):
        out,_=policy.select(dict(kind='agreement'),self.a,self.b,[])
        self.assertTrue(all(not value for value in out.values()))
        self.b['vintage']='2018'
        out,_=policy.select(dict(kind='agreement'),self.a,self.b,[])
        self.assertEqual(out['vintage'],'2018')
        self.assertEqual(out['producer'],'')

    def test_blank_fallback_and_foundation_date_penalty(self):
        self.a['producer']=''
        out,_=policy.select(dict(kind='hybrid',prefer=0,fallback=True,disagree='prefer'),self.a,self.b,[])
        self.assertEqual(out['producer'],self.b['producer'])
        normal=[dict(raw_text='2018',candidate='mlkit_reference')]
        founded=[dict(raw_text='Established 2018',candidate='mlkit_reference')]
        self.assertGreater(policy.evidence('2018','vintage',normal),policy.evidence('2018','vintage',founded))

    def test_no_reference_or_filesystem_dependency_in_selector(self):
        source=(ROOT/'tools/corpus/selection_policies.py').read_text()
        for forbidden in ('import json','import csv','from pathlib','open(','target[','accepted[','reference.json'):
            self.assertNotIn(forbidden,source)

    def test_hypothesis_parser_preserves_values(self):
        self.assertEqual(diagnostics.parse_hypothesis('{PRODUCER=Name, Fils, VINTAGE=2018}'),dict(producer='Name, Fils',vintage='2018'))
        with self.assertRaises(AssertionError):diagnostics.parse_hypothesis('{BAD=unknown}')

class DiagnosticReproductionTests(unittest.TestCase):
    def test_complete_suite_reproduces_and_does_not_mutate_inputs(self):
        saved=ROOT/'tools/evaluation/comprehensive-diagnostics-v1'
        with tempfile.TemporaryDirectory() as temp,contextlib.redirect_stdout(io.StringIO()):
            out=Path(temp);diagnostics.run(out)
            actual=json.loads((out/'summary.json').read_text())
            self.assertEqual(actual,json.loads((saved/'summary.json').read_text()))
            for rel,sha in actual['inputs'].items():self.assertEqual(diagnostics.digest(ROOT/rel),sha)
            for name in ('report.md','case-diagnostics.csv','field-diagnostics.csv'):
                self.assertEqual((out/name).read_bytes(),(saved/name).read_bytes())
            for name in ('policy-outputs.json.gz','policy-decisions.json.gz'):
                with gzip.open(out/name,'rt') as a,gzip.open(saved/name,'rt') as b:self.assertEqual(json.load(a),json.load(b))
            self.assertEqual(actual['systems']['lab_baseline']['fields']['correct'],597)
            self.assertEqual(actual['systems']['experimental_v3']['fields']['correct'],599)
            self.assertEqual(sum(actual['stage_counts'].values()),1008)
            oracle=actual['reference_assisted_upper_bounds']
            self.assertLessEqual(oracle['either_whole_entry'],oracle['selected_field_union'])
            self.assertLessEqual(oracle['selected_field_union'],oracle['candidate_pool_union'])
            for p in actual['policies'].values():
                self.assertEqual(p['fields']['scored'],894)
                self.assertEqual(p['fields']['unscored'],114)
                self.assertEqual(p['images']['six_field_eligible'],103)
                self.assertEqual(sum(f['fields']['total'] for f in p['identity_group_slices'].values()),1008)
