"""Guard storage-free defaults without adding a YAML dependency to host tests."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class CiStoragePolicyTests(unittest.TestCase):
    def blocks(self):
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            for block in re.split(r'(?=^      - )', path.read_text(), flags=re.M):
                yield path.name, block

    def test_all_artifact_uploads_are_explicit_opt_in(self):
        count = 0
        for name, block in self.blocks():
            if 'uses: actions/upload-artifact@' not in block:
                continue
            count += 1
            with self.subTest(workflow=name):
                self.assertIn("if: always() && vars.WINE_UPLOAD_ACTIONS_ARTIFACTS == 'true'", block)
                self.assertIn('retention-days: 1', block)
                self.assertNotIn('continue-on-error:', block)
        self.assertGreater(count, 0)

    def test_cache_writes_are_explicit_opt_in(self):
        for name, block in self.blocks():
            with self.subTest(workflow=name):
                if 'uses: gradle/actions/setup-gradle@' in block:
                    self.assertIn("cache-read-only: ${{ vars.WINE_ENABLE_CI_CACHE_WRITES != 'true' }}", block)
                if 'uses: actions/cache/save@' in block:
                    self.assertIn("if: vars.WINE_ENABLE_CI_CACHE_WRITES == 'true' &&", block)

    def test_producer_results_and_failure_diagnostics_do_not_need_artifacts(self):
        for name in ['producer-iteration.yml', 'producer-refinement.yml', 'producer-lab-validation.yml']:
            text = (ROOT / '.github/workflows' / name).read_text()
            self.assertLess(text.index('git push origin HEAD:'), text.index('uses: actions/upload-artifact@'))
            self.assertIn('if: always()\n        uses: actions/github-script@', text)
            self.assertIn('github.rest.checks.create', text)
