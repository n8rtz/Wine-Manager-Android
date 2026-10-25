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
    def test_user_review_selection_excludes_color_coded_and_omitted_rows(self):
        selection=json.loads((ASSETS/'evaluation-set.json').read_text())
        self.assertEqual((selection['included_count'],selection['excluded_count']),(107,61))
        self.assertEqual(sum(x['review_color']=='red' for x in selection['excluded_images']),55)
        self.assertEqual(sum(x['review_color']=='yellow' for x in selection['excluded_images']),4)
        self.assertEqual({x['review_number'] for x in selection['excluded_images'] if x['review_color']=='absent_from_review_workbook'},{27,47})
        self.assertEqual(len({x['image'] for x in selection['included_images']}),107)
        self.assertEqual(len({x['image'] for x in selection['excluded_images']}),61)
        self.assertFalse(({x['image'] for x in selection['included_images']}&{x['image'] for x in selection['excluded_images']}))
        self.assertEqual({x['image'] for x in selection['included_images']}|{x['image'] for x in selection['excluded_images']},
            {x['image'] for x in json.loads((ASSETS/'inputs.json').read_text())['images']})
        self.assertIn('reference-data-168.xlsx',selection['source_workbook'])
    def test_reference_and_frozen_baseline_parity(self):
        scoring=json.loads((ASSETS/'scoring.json').read_text());counts=Counter();states=Counter();eligible=complete=0
        frozen={i['image']:i for i in json.loads((ROOT/'tools/evaluation/public-corpus-reference-v1/reference.json').read_text())['images']}
        adjudications=json.loads((ASSETS/'reference-adjudications-v1.json').read_text())['adjudications']
        overrides={(x['image'],x['field']):x for x in adjudications}
        for image in scoring['images']:
            outcomes=[]
            for field,target in image['fields'].items():
                original=frozen[image['image']]
                override=overrides.get((image['image'],field))
                if override:
                    source=original['fields'][field]
                    self.assertEqual(source['value'],override['imported_target'])
                    self.assertEqual([normalize(s) for s in source['accepted']],override['imported_accepted'])
                    self.assertEqual((target['status'],target['target'],target['accepted']),
                        (override['status'],override['target'],override['accepted']))
                else:
                    self.assertEqual(target['status'],disposition(original,field)[0])
                    self.assertEqual(target['accepted'],[normalize(s) for s in original['fields'][field]['accepted']])
                outcome=classify(target['status'],target,scoring['baseline'][image['image']][field])
                counts[outcome]+=1;states[target['status']]+=1;outcomes.append(outcome)
            if all(t['status']!='unresolved' for t in image['fields'].values()):
                eligible+=1;complete+=all(o in ('correct_value','correct_blank') for o in outcomes)
        self.assertEqual(dict(states),{'expected_value':894,'expected_blank':97,'unresolved':17})
        self.assertEqual((eligible,complete),(160,26))
        for k,v in dict(correct_value=597,correct_blank=77,wrong_value=100,unsupported_fill=20,missing_value=197).items():self.assertEqual(counts[k],v)
    def test_user_adjudication_overrides_only_scored_target_and_is_rebuildable(self):
        adjudication_doc=json.loads((ASSETS/'reference-adjudications-v1.json').read_text())
        self.assertEqual(len(adjudication_doc['adjudications']),1)
        decision=adjudication_doc['adjudications'][0]
        self.assertEqual((decision['review_number'],decision['field'],decision['imported_target'],decision['target']),
            (63,'wine_name','Volcanie Rosé','Volcanic Rosé'))
        self.assertIn('User-confirmed',decision['decision_source'])
        builder=(ROOT/'tools/corpus/bundle_phone_corpus.py').read_text()
        self.assertIn('ADJUDICATIONS',builder)
        self.assertIn("source['value']==override['imported_target']",builder)
        row=next(x for x in json.loads((ASSETS/'scoring.json').read_text())['images'] if x['review_number']==63)
        self.assertEqual(row['fields']['wine_name']['target'],'Volcanic Rosé')
        self.assertEqual(row['fields']['wine_name']['accepted'],['volcanicrose'])
    def test_debug_only_and_prediction_boundary(self):
        self.assertFalse((ROOT/'app/src/main/assets/phone-corpus-v1').exists())
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt').read_text()
        self.assertNotIn('scoring.json',runner);self.assertNotIn('baseline',runner)
        # The same production scanner/input/shared-budget start is preserved, with setup telemetry between calls.
        self.assertRegex(runner,r'val scanner=WineScannerServices\.get\(context\)[\s\S]*?val report=scanner\.scan\(input,started\)')
        self.assertIn('TextLabRunner.recognize',runner)
        viewmodel=(ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusViewModel.kt').read_text()
        self.assertIn('evaluation-set.json',viewmodel)
        self.assertIn('filter { it.getString("image") in included }',viewmodel)
        self.assertIn('ACTIVE_IMAGE_COUNT=107', (ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt').read_text())
        scoreboard=(ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusScoreboard.kt').read_text()
        self.assertIn('savedV2Items(includedImages)',scoreboard)
        self.assertIn('baselineItems(includedImages)',scoreboard)
        manifest=(ROOT/'app/src/debug/AndroidManifest.xml').read_text()
        self.assertIn('phonecorpus.PhoneCorpusActivity" android:exported="false"',manifest)
