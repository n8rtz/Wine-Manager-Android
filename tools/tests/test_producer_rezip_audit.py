"""Checks on the measured second-package audit (not an Android or OCR test)."""
import json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class ProducerRezipAuditChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.a=json.loads((ROOT/'tools/producer-gazetteer-review/rezip/audit.json').read_text())
    def test_archive_accounting(self):
        a=self.a
        self.assertEqual(a['source_blob_sha1'],'8bf9737463092b0a2ef233139dee8e88a0f6fae8')
        self.assertEqual(sum(f['bytes'] for f in a['files'].values()),a['expanded_bytes'])
        self.assertEqual(sum(f['bytes'] for n,f in a['files'].items() if '/.cache/' in n),a['hidden_cache_bytes'])
        self.assertEqual(a['expanded_bytes']-a['hidden_cache_bytes'],52913)
        for f in a['files'].values():self.assertRegex(f['sha256'],r'^[0-9a-f]{64}$')
    def test_duplicate_inputs_are_not_independent_producers(self):
        a=self.a
        self.assertEqual(sum(a['industry_rows'].values()),169456)
        self.assertEqual(sum(a['unique_record_industries'].values()),84689)
        self.assertEqual(a['unique_permits'],a['unique_parsed_records'])
        self.assertEqual(a['all_entity_counts'],{'producers':77668,'fts':77668})
        self.assertEqual(a['wine_only_entities'],25559)
    def test_owner_and_role_collisions_are_visible(self):
        p=self.a['exact_alias_probes']
        self.assertEqual({r['name'] for r in p['Chateau Montelena']},{'Standing Journey','Château Montelena'})
        self.assertTrue(p['Blackstone'])
        for r in p['Blackstone']:self.assertEqual(r['industries'],['Distilled Spirits Plant'])
        self.assertEqual(p['Chateau Margaux'],[])
    def test_retired_reference_is_not_used_for_source_evaluation(self):
        a=self.a
        self.assertEqual(a['reference_evaluation']['status'],'not_performed')
        self.assertNotIn('lexical_all',a)
        self.assertNotIn('lexical_wine_only',a)
