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

    def test_warmup_and_corpus_use_generous_experimental_budget(self):
        source=(ROOT/'app/src/androidTest/java/com/yourapp/wine/textlab/SavedEvidenceReplayTest.kt').read_text()
        self.assertIn('revised.extract(synthetic,2026,budgetMs=EvidenceExtractor.DEFAULT_BUDGET_MS)',source)
        self.assertIn('revised.extract(records,2026)',source)
        self.assertNotIn('revised.extract(records,2026,budgetMs=',source)
        self.assertIn('"progress.json"',source)
        self.assertIn('"failures",JSONArray(failures)',source)

    def test_incomplete_corpus_cannot_be_scored(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/'validation.json').write_text(json.dumps(dict(images=168,
                execution_policy='revised_experimental_only',baseline_executions=0,
                old_experimental_executions=0,completed=167,failures=['timeout'],corpus_budget_ms=60000)))
            with self.assertRaises(AssertionError):collect(root,root/'out','a'*40)
            self.assertFalse((root/'out').exists())

    def test_experimental_budget_and_outer_timeout_are_consistent(self):
        source=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt').read_text()
        self.assertIn('DEFAULT_BUDGET_MS=60_000L',source)
        self.assertIn('budgetMs:Long=DEFAULT_BUDGET_MS',source)
        harness=(ROOT/'tools/corpus/run_saved_evidence_replay.sh').read_text()
        self.assertIn('168 * 60 + 180',harness)
        production=(ROOT/'app/src/main/java/com/yourapp/wine/ocr/ScanDeadline.kt').read_text()
        self.assertIn('BUDGET_MS = 15_000L',production)
