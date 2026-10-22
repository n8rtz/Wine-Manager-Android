"""Offline catalog integrity checks; no network or Android runtime."""
import csv,json,subprocess,sys,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'data/wine-knowledge'
def read(name):
    with (DATA/name).open() as f:return list(csv.DictReader(f))
class WineKnowledgeTests(unittest.TestCase):
    def test_generated_outputs_are_current(self):
        subprocess.run([sys.executable,str(ROOT/'tools/generate-wine-knowledge.py'),'--check'],check=True,capture_output=True)
    def test_coverage_ids_sources_and_policies(self):
        rows=read('origins.csv');sources={r['id'] for r in json.loads((DATA/'sources/registry.json').read_text())['sources']}
        self.assertGreaterEqual(len(rows),1000);self.assertEqual(len(rows),len({r['id'] for r in rows}))
        self.assertGreaterEqual(len({r['country_code'] for r in rows}),29)
        for row in rows:
            self.assertIn(row['source_id'],sources);self.assertTrue(row['source_record'])
            self.assertEqual(row['review'],'reference_confirm');self.assertIn(row['match_policy'],('exact','context_required'))
            self.assertNotIn('|',row['regions']);self.assertTrue(row['country']);self.assertTrue(row['name'])
    def test_us_metadata_and_cross_state_membership(self):
        rows={r['name']:r for r in read('origins.csv') if r['country_code']=='US'}
        self.assertEqual(len(rows),276)
        self.assertEqual(rows['Altus']['regions'],'Arkansas')
        self.assertEqual(set(rows['Walla Walla Valley']['regions'].split(';')),{'Oregon','Washington'})
        self.assertEqual(set(rows['Mesilla Valley']['regions'].split(';')),{'New Mexico','Texas'})
        source=json.loads((DATA/'sources/ucd-ava-properties.json').read_text())
        self.assertEqual(source['license'],'CC0-1.0')
        self.assertEqual(source['commit'],'a00174df3809f4edcea101f4489961d8b97e4ff9')
        for record in source['records']:
            for prop in record['properties']:
                self.assertNotIn('geometry',prop);self.assertFalse(prop['valid_end'])
                self.assertLessEqual(prop['created'] or '',source['as_of'])
                self.assertLessEqual(prop['valid_start'] or '',source['as_of'])
    def test_classification_grapes_and_uncertain_regions_do_not_impute(self):
        self.assertTrue(all(r['action']=='never_autofill' for r in read('typical-grapes.csv')))
        self.assertTrue(all(r['action']=='context_only' for r in read('label-terms.csv') if r['role']=='classification'))
        rows={r['name']:r for r in read('origins.csv')}
        for name in ('Prosecco','Cava','Lugana','Orvieto','Canberra District'):
            self.assertEqual(rows[name]['regions'],'',name)
        self.assertEqual(rows['Orange']['match_policy'],'context_required')
        self.assertEqual(rows['Denmark']['match_policy'],'context_required')
if __name__=='__main__':unittest.main()
