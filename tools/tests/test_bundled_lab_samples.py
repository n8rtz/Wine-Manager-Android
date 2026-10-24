"""Integrity checks for debug-only original photos and their explicit scoring truth."""
import hashlib,json,pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/debug/assets/ocr-lab-v1'
class BundledLabSamplesTests(unittest.TestCase):
    def test_originals_match_registry_and_are_debug_only(self):
        data=json.loads((ASSETS/'samples.json').read_text())
        self.assertEqual(data['version'],1)
        self.assertEqual(len(data['samples']),3)
        seen=set()
        for row in data['samples']:
            sha=row['sha256'];self.assertNotIn(sha,seen);seen.add(sha)
            self.assertEqual(row['asset'],sha+'.jpg')
            blob=(ASSETS/row['asset']).read_bytes()
            self.assertTrue(blob.startswith(b'\xff\xd8'))
            self.assertEqual(hashlib.sha256(blob).hexdigest(),sha)
            self.assertEqual(set(row['expected']),{'PRODUCER','WINE_NAME','COUNTRY','REGION','SUBREGION','VINTAGE'})
            self.assertFalse((ROOT/'app/src/main/assets/ocr-lab-v1'/row['asset']).exists())
        self.assertLess(sum((ASSETS/r['asset']).stat().st_size for r in data['samples']),12*1024*1024)
    def test_preserves_prior_user_answers_not_ocr_outputs(self):
        rows=json.loads((ASSETS/'samples.json').read_text())['samples']
        blackstone=next(r for r in rows if r['expected']['PRODUCER']=='Blackstone')
        self.assertEqual(blackstone['expected']['WINE_NAME'],'Merlot')
        self.assertEqual(blackstone['expected']['COUNTRY'],'California')
        self.assertEqual(blackstone['expected']['VINTAGE'],'')
        olarra=next(r for r in rows if r['expected']['PRODUCER']=='Bodegas Olarra')
        self.assertEqual(olarra['expected']['VINTAGE'],'2019')
        self.assertEqual(olarra['expected']['WINE_NAME'],'Reciente Gran Reserva')
        self.assertTrue(all('reviewed CSV' in r['source'] for r in rows))
    def test_registry_is_not_a_recognition_input(self):
        for folder in ('app/src/main/java','app/src/main/assets'):
            for p in (ROOT/folder).rglob('*'):
                if p.suffix in ('.kt','.json','.csv'):
                    self.assertNotIn('ocr-lab-v1/samples.json',p.read_text(),str(p))
