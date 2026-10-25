import json
import sys
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
import rescore_expected_blanks as score

class ExpectedBlankTests(unittest.TestCase):
    def test_expected_blank_is_correct_only_when_blank(self):
        t={'accepted':[]}
        self.assertEqual(score.classify('expected_blank',t,''),'correct_blank')
        self.assertEqual(score.classify('expected_blank',t,'  '),'correct_blank')
        self.assertEqual(score.classify('expected_blank',t,'Napa Valley'),'unsupported_fill')

    def test_unknown_remains_excluded(self):
        self.assertEqual(score.classify('unresolved',{},''),'unscored_blank')
        self.assertEqual(score.classify('unresolved',{},'anything'),'unscored_filled')

    def test_expected_values_still_require_inference_and_alias_match(self):
        t={'accepted':['Bordeaux']}
        self.assertEqual(score.classify('expected_value',t,''),'missing_value')
        self.assertEqual(score.classify('expected_value',t,'Bordeaux'),'correct_value')
        self.assertEqual(score.classify('expected_value',t,'Burgundy'),'wrong_value')

    def test_overlay_covers_unknowns_without_blanket_conversion(self):
        items=json.loads(score.REF.read_text())['images'];counts={}
        for item in items:
            for field,t in item['fields'].items():
                status,_=score.disposition(item,field);counts[status]=counts.get(status,0)+1
                if t['status']=='known':self.assertEqual(status,'expected_value')
        self.assertEqual(counts,dict(expected_value=892,expected_blank=97,unresolved=19))
        by={i['review_number']:i for i in items}
        self.assertEqual(score.disposition(by[12],'region')[0],'expected_blank')
        self.assertEqual(score.disposition(by[9],'producer')[0],'unresolved')
        self.assertEqual(score.disposition(by[19],'vintage')[0],'unresolved')
        self.assertEqual(score.disposition(by[150],'subregion')[0],'expected_value')
        self.assertEqual(score.disposition(by[125],'wine_name')[0],'unresolved')
