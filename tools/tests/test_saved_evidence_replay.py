import json
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
from replay_saved_evidence import prepare,collect

class SavedEvidenceReplayTests(unittest.TestCase):
    def test_input_contains_original_readings_not_visual_targets(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'input.json';prepare(p)
            items=json.loads(p.read_text())
            self.assertEqual(len(items),168)
            self.assertEqual(len({i['image'] for i in items}),168)
            self.assertEqual(sum(r['stage']=='recognition' for i in items for r in i['rows']),2722)
            for item in items:
                self.assertEqual(set(item),{'image','rows'})
                self.assertTrue(all(r['stage'] in ('recognition','summary','candidate_summary') for r in item['rows']))

    def test_old_control_execution_policy_rejects_scoring(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/'validation.json').write_text(json.dumps(dict(images=168,mismatches=['image/producer/v2'])))
            with self.assertRaises(AssertionError):collect(root,root/'out','a'*40)
            self.assertFalse((root/'out').exists())

    def test_device_replays_only_revised_extractor(self):
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/SavedEvidenceReplayTest.kt').read_text()
        self.assertNotIn('FillEngine',source)
        self.assertNotIn('TextLabFormPreview.append',source)
        self.assertNotIn('typedIdentity=false',source)
        self.assertNotIn('expected_baseline',source)
        self.assertNotIn('old.extract',source)
        self.assertIn('revised.extract(records,2026)',source)

    def test_warmup_allowance_does_not_change_corpus_budget(self):
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/SavedEvidenceReplayTest.kt').read_text()
        self.assertIn('revised.extract(synthetic,2026,budgetMs=60_000)',source)
        self.assertIn('revised.extract(records,2026)',source)
        self.assertNotIn('revised.extract(records,2026,budgetMs=',source)
        self.assertIn('"progress.json"',source)
        self.assertIn('"failures",JSONArray(failures)',source)

    def test_incomplete_corpus_cannot_be_scored(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/'validation.json').write_text(json.dumps(dict(images=168,
                execution_policy='revised_experimental_only',baseline_executions=0,
                old_experimental_executions=0,completed=167,failures=['timeout'],corpus_budget_ms=2000)))
            with self.assertRaises(AssertionError):collect(root,root/'out','a'*40)
            self.assertFalse((root/'out').exists())
