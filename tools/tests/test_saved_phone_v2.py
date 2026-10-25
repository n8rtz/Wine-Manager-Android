import csv,hashlib,json,sys,unittest
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
from bundle_saved_phone_v2 import build
from rescore_expected_blanks import classify
ASSETS=ROOT/'app/src/debug/assets/phone-corpus-v1'
class SavedPhoneV2Checks(unittest.TestCase):
    def test_bundle_exactly_reproduces_source_predictions(self):
        saved=json.loads((ASSETS/'saved-production-v2.json').read_text())
        self.assertEqual(saved,build())
        self.assertEqual(saved['source_build'],'28bce9e81cc3')
        self.assertEqual(saved['source_pipeline'],'lab-guarded-producer')
        self.assertEqual(saved['scoring_sha256'],hashlib.sha256((ASSETS/'scoring.json').read_bytes()).hexdigest())
    def test_full_and_partial_scoring_parity(self):
        saved=json.loads((ASSETS/'saved-production-v2.json').read_text())['predictions']
        targets=json.loads((ASSETS/'scoring.json').read_text())['images']
        self.assertEqual(set(saved),{im['image'] for im in targets})
        for size,expected_forms in [(168,(34,43,83)),(19,(4,5,6)),(0,(0,0,0))]:
            counts=Counter();forms=Counter()
            for im in targets[:size]:
                outcomes=[]
                for field,t in im['fields'].items():
                    outcome=classify(t['status'],t,saved[im['image']][field]);counts[outcome]+=1;outcomes.append(outcome)
                if all(t['status']!='unresolved' for t in im['fields'].values()):
                    forms[min(2,sum(o not in ('correct_value','correct_blank') for o in outcomes))]+=1
            self.assertEqual(tuple(forms[i] for i in range(3)),expected_forms)
            if size==168:
                for key,value in dict(correct_value=630,wrong_value=80,missing_value=184,correct_blank=77,unsupported_fill=20).items():self.assertEqual(counts[key],value)
    def test_scoring_only_no_recognition_input(self):
        base=ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus'
        board=(base/'PhoneCorpusScoreboard.kt').read_text()
        self.assertIn('savedV2Items(ids),v2Provenance',board)
        self.assertIn('savedV2Items(includedImages),v2Provenance',board)
        for file in ('PhoneCorpusRunner.kt','PhoneCorpusViewModel.kt'):
            self.assertNotIn('saved-production-v2.json',(base/file).read_text())
