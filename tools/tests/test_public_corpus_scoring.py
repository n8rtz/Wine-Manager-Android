"""Scoring mechanics and reproducibility, not expert certification of visual targets."""
import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('public_scoring', ROOT/'tools/corpus/score_public_corpus.py')
scorer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scorer)
REF = ROOT/'tools/evaluation/public-corpus-reference-v1'
RUN = ROOT/'tools/evaluation/public-corpus-run-39716483937'


class ScoringUnitTests(unittest.TestCase):
    def test_normalization_is_not_fuzzy_or_substring(self):
        t = {'status':'known','accepted':['Château Amélisse']}
        self.assertEqual(scorer.outcome(t, 'CHATEAU AMELISSE'), 'correct')
        for bad in ('Chateau Amelise', 'Amelisse', 'Chateau Amelisse France'):
            self.assertEqual(scorer.outcome(t, bad), 'wrong')
        self.assertEqual(scorer.normalize('Blard & Fils'), scorer.normalize('Blard et Fils'))

    def test_unknowns_are_never_correct(self):
        t = {'status':'unknown','accepted':[]}
        self.assertEqual(scorer.outcome(t, ''), 'unscored_blank')
        self.assertEqual(scorer.outcome(t, '2019'), 'unscored_filled')

    def test_blank_known_is_abstention(self):
        self.assertEqual(scorer.outcome({'status':'known','accepted':['2019']}, '  '), 'abstained')

    def test_appellation_qualifiers_not_globally_removed(self):
        t = {'status':'known','accepted':['Saint-Émilion Grand Cru']}
        self.assertEqual(scorer.outcome(t,'Saint Emilion'), 'wrong')

    def test_partial_success_not_six_field_success(self):
        rows = [{'image':'a','outcome':s} for s in ['correct']*5+['unscored_blank']]
        s = scorer.image_summary(rows)
        self.assertEqual(s['six_field_eligible'],0)
        self.assertEqual(s['six_field_fully_correct'],0)
        self.assertEqual(s['all_known_fields_correct_not_full_six'],1)

    def test_freeze_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)
            (p/'reference.json').write_bytes((REF/'reference.json').read_bytes()+b' ')
            (p/'reference.sha256').write_bytes((REF/'reference.sha256').read_bytes())
            with self.assertRaisesRegex(ValueError,'hash mismatch'):
                scorer.load(p,RUN)


class FrozenScoringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ref,cls.pred,cls.prod=scorer.load(REF,RUN)
        cls.result,cls.rows=scorer.score(cls.ref,cls.pred,cls.prod)

    def test_reference_matches_pinned_inventory_and_review(self):
        inv=json.loads((ROOT/'tools/corpus/public-source-inventory.json').read_text())
        expected={i['source_blob_sha1']+'.jpg':i['image_sha256'] for i in inv['records']}
        self.assertEqual({i['image']:i['image_sha256'] for i in self.ref['images']},expected)
        with (REF/'readings.tsv').open() as f:
            notes=list(csv.DictReader(f,delimiter='\t'))
        self.assertEqual(len(notes),168)
        for item,row in zip(self.ref['images'],notes):
            self.assertEqual(item['review_number'],int(row['review_number']))
            for field in scorer.FIELDS:
                self.assertEqual(item['fields'][field]['value'],None if row[field]=='-' else row[field])
        self.assertEqual(sum(t['status']=='known' for i in self.ref['images'] for t in i['fields'].values()),892)

    def test_counts_partition_all_outputs(self):
        self.assertEqual(len(self.rows),3024)
        for p in self.result['pipelines'].values():
            s=p['all_fields']
            self.assertEqual(s['scored']+s['unscored'],1008)
            self.assertEqual(s['correct']+s['wrong']+s['abstained'],892)
            self.assertEqual(s['unscored_filled']+s['unscored_blank'],116)
            for key in ('correct','wrong','abstained','unscored','filled'):
                self.assertEqual(sum(f[key] for f in p['per_field'].values()),s[key])

    def test_frozen_scores_and_existing_fill_totals(self):
        expected={'production':(480,141,271,639,6),'lab_baseline':(597,100,195,724,23),'experimental':(566,121,205,713,8)}
        for name,(c,w,a,filled,full) in expected.items():
            p=self.result['pipelines'][name];s=p['all_fields']
            self.assertEqual((s['correct'],s['wrong'],s['abstained'],s['filled']),(c,w,a,filled))
            self.assertEqual(p['image_results']['six_field_fully_correct'],full)
            self.assertEqual(p['image_results']['six_field_eligible'],101)

    def test_duplicates_are_identity_not_byte_or_estate_only(self):
        self.assertEqual(self.result['identity_groups'],143)
        self.assertEqual(len(self.result['repeated_identity_groups']),23)
        by_number={i['review_number']:i for i in self.ref['images']}
        self.assertEqual(by_number[55]['identity_group'],by_number[168]['identity_group'])
        self.assertNotEqual(by_number[55]['identity_group'],by_number[32]['identity_group'])
        for p in self.result['pipelines'].values():
            self.assertEqual(p['one_photo_per_identity']['image_results']['images'],143)
            self.assertEqual(p['one_photo_per_identity']['all_fields']['scored'],760)

    def test_checked_in_scores_reproduce(self):
        saved=json.loads((ROOT/'tools/evaluation/public-corpus-scoring-v1/scores.json').read_text())
        for key,value in self.result.items():
            self.assertEqual(saved[key],value)
        for path,sha in saved['provenance'].items():
            self.assertEqual(scorer.digest(ROOT/path),sha)
        with (ROOT/'tools/evaluation/public-corpus-scoring-v1/field-results.csv').open() as f:
            stored=list(csv.DictReader(f))
        self.assertEqual(len(stored),len(self.rows))
        for actual,expected in zip(stored,self.rows):
            self.assertEqual(actual,{k:str(v) for k,v in expected.items()})
