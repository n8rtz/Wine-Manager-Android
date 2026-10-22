import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'corpus'))
from completion_problem_breakdown import completion_gate
class CompletionTargetTests(unittest.TestCase):
    def test_exact_eighty_percent_and_strict_five_percent(self):
        self.assertTrue(completion_gate([0]*128+[1]*25+[2]*7)['passes'])
        self.assertFalse(completion_gate([0]*128+[1]*24+[2]*8)['passes'])
        self.assertFalse(completion_gate([0]*127+[1]*33)['passes'])
    def test_current_results_fail(self):
        r=completion_gate([0]*32+[1]*46+[2]*82)
        self.assertFalse(r['passes']);self.assertEqual(r['minimum_complete'],128)
        self.assertEqual(r['maximum_two_or_more_errors'],7)
    def test_empty_and_invalid_do_not_pass(self):
        for c in [[],[-1],[7]]:
            with self.assertRaises(ValueError):completion_gate(c)
