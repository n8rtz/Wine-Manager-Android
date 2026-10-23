import hashlib,json,sys,unittest
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
from score_public_corpus import normalize
from rescore_expected_blanks import classify,disposition
ASSETS=ROOT/'app/src/debug/assets/phone-corpus-v1'
class PhoneCorpusTests(unittest.TestCase):
    def test_all_168_exact_frozen_photos(self):
        inputs=json.loads((ASSETS/'inputs.json').read_text())['images']
        frozen=json.loads((ROOT/'tools/evaluation/public-corpus-reference-v1/reference.json').read_text())['images']
        self.assertEqual(len(inputs),168)
        self.assertEqual({i['image']:i['sha256'] for i in inputs},{i['image']:i['image_sha256'] for i in frozen})
        self.assertEqual({p.name for p in (ASSETS/'images').glob('*.jpg')},{i['image'] for i in inputs})
        for i in inputs:self.assertEqual(hashlib.sha256((ASSETS/'images'/i['image']).read_bytes()).hexdigest(),i['sha256'])
        manifest=json.loads((ASSETS/'manifest.json').read_text())
        for name in ('inputs','scoring'):self.assertEqual(hashlib.sha256((ASSETS/f'{name}.json').read_bytes()).hexdigest(),manifest[f'{name}_sha256'])
        self.assertEqual(sum(p.stat().st_size for p in (ASSETS/'images').iterdir()),31163745)
    def test_reference_and_frozen_baseline_parity(self):
        scoring=json.loads((ASSETS/'scoring.json').read_text());counts=Counter();states=Counter();eligible=complete=0
        frozen={i['image']:i for i in json.loads((ROOT/'tools/evaluation/public-corpus-reference-v1/reference.json').read_text())['images']}
        for image in scoring['images']:
            outcomes=[]
            for field,target in image['fields'].items():
                original=frozen[image['image']]
                self.assertEqual(target['status'],disposition(original,field)[0])
                self.assertEqual(target['accepted'],[normalize(s) for s in original['fields'][field]['accepted']])
                outcome=classify(target['status'],original['fields'][field],scoring['baseline'][image['image']][field])
                counts[outcome]+=1;states[target['status']]+=1;outcomes.append(outcome)
            if all(t['status']!='unresolved' for t in image['fields'].values()):
                eligible+=1;complete+=all(o in ('correct_value','correct_blank') for o in outcomes)
        self.assertEqual(dict(states),{'expected_value':894,'expected_blank':97,'unresolved':17})
        self.assertEqual((eligible,complete),(160,26))
        for k,v in dict(correct_value=597,correct_blank=77,wrong_value=100,unsupported_fill=20,missing_value=197).items():self.assertEqual(counts[k],v)
    def test_debug_only_and_prediction_boundary(self):
        self.assertFalse((ROOT/'app/src/main/assets/phone-corpus-v1').exists())
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt').read_text()
        self.assertNotIn('scoring.json',runner);self.assertNotIn('baseline',runner)
        self.assertIn('WineScannerServices.get(context).scan(input,started)',runner)
        self.assertIn('TextLabRunner.recognize',runner)
        manifest=(ROOT/'app/src/debug/AndroidManifest.xml').read_text()
        self.assertIn('phonecorpus.PhoneCorpusActivity" android:exported="false"',manifest)
