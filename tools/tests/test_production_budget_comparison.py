"""Comparison mechanics using baseline fixtures; does not simulate a new OCR run."""
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/corpus'))
from compare_production_budgets import compare

class BudgetComparisonTests(unittest.TestCase):
    def test_identical_fixture_has_no_changed_outcomes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);run=root/'run'
            shutil.copytree(ROOT/'tools/evaluation/public-corpus-run-39716483937',run)
            (run/'budget-build.json').write_text(json.dumps(dict(production_budget_ms=60000,baseline_budget_ms=15000,fixture_only=True)))
            compare(run,root/'output')
            data=json.loads((root/'output/comparison.json').read_text())
            self.assertEqual(data['baseline_15s'],data['comparison_60s'])
            self.assertEqual(data['field_transitions']['correct -> correct'],480)
            self.assertEqual(sum(data['field_transitions'].values()),1008)

    def test_normal_app_constant_stays_15_seconds(self):
        source=(ROOT/'app/src/main/java/com/yourapp/wine/ocr/ScanDeadline.kt').read_text()
        self.assertIn('const val BUDGET_MS = 15_000L',source)
        workflow=(ROOT/'.github/workflows/production-budget-comparison.yml').read_text()
        self.assertIn("assert not diff",workflow)
        self.assertIn('budget-build.patch',workflow)
        self.assertIn('fetch-depth: 0',workflow)
        self.assertIn('budget-setup.txt',workflow)
        self.assertIn('compare_production_budgets.py',workflow)
        self.assertIn('git push origin HEAD:arena/fd8dc3be-wine-manager-android',workflow)
