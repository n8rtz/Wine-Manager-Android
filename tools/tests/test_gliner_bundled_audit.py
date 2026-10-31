import json
import re
import unicodedata
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / 'gliner-workflow/src/main/assets/gliner-personal-audit-v1/references.json'
FIELDS = ('producer', 'wine_name', 'country', 'region', 'subregion', 'vintage')


def norm(value):
    text = unicodedata.normalize('NFKD', value.lower().replace('&', ' et '))
    return ''.join(c for c in text if c.isalnum())


class BundledGlinerAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(ASSET.read_text())

    def test_reference_identity_and_minimized_baseline(self):
        refs = self.data['audit_references']
        predictions = self.data['custom_images']
        self.assertEqual((len(refs), len(predictions)), (26, 26))
        self.assertEqual(len({ref['sha256'] for ref in refs}), 26)
        for ref, prediction in zip(refs, predictions):
            self.assertRegex(ref['sha256'], r'^[0-9a-f]{64}$')
            self.assertEqual(ref['image'], f"custom-{ref['sha256']}.img")
            self.assertEqual((prediction['image'], prediction['sha256']), (ref['image'], ref['sha256']))
            self.assertEqual(set(ref['references']), set(FIELDS))
            self.assertEqual(set(prediction['fields']), set(FIELDS))
            self.assertEqual(prediction['status'], 'complete')
            self.assertNotRegex(ref['source_filename'], r'(?i)\.jpe?g$')
            self.assertFalse({'raw_readings', 'ocr_lines', 'residual', 'manual_review'} & set(prediction))

    def test_corrected_reference_comparison(self):
        refs, predictions = self.data['audit_references'], self.data['custom_images']
        self.assertEqual(refs[3]['references']['region']['expected'], 'Sonoma County')
        self.assertEqual(refs[17]['references']['subregion']['expected_status'], 'expected_blank')
        self.assertEqual(refs[22]['references']['region']['expected'], 'Lodi')
        uco = refs[16]['references']['subregion']
        self.assertIn(norm('Uco Valley'), {norm(v) for v in uco['accepted']})
        full = correct = 0
        for ref, prediction in zip(refs, predictions):
            matches = []
            for field in FIELDS:
                target, actual = ref['references'][field], prediction['fields'][field]
                if target['expected_status'] == 'expected_blank':
                    matches.append(not actual.strip())
                else:
                    acceptable = [target['expected']] + target.get('accepted', [])
                    matches.append(bool(actual.strip()) and norm(actual) in {norm(x) for x in acceptable})
            correct += sum(matches)
            full += all(matches)
        self.assertEqual((full, correct), (12, 130))

    def test_audit_reuses_curated_score_and_failure_tables(self):
        screen = (ROOT / 'gliner-workflow/src/appIntegration/java/com/n8rtz/winetracker/ui/screens/GlinerLabScreen.kt').read_text()
        audit = screen.split('private fun AuditScoredResults(', 1)[1].split('@Composable', 1)[0]
        curated = screen.split('private fun ScoredResults(', 1)[1].split('@Composable', 1)[0]
        card = screen.split('private fun AuditResultCard(', 1)[1].split('@Composable', 1)[0]
        for function in ('ScoreSummaryTable(', 'ScoreFieldTable('):
            self.assertIn(function, audit)
            self.assertIn(function, curated)
        self.assertIn('FailedParametersTable(failed)', card)
        self.assertIn('mutableStateOf(false)', card)  # Raw OCR starts collapsed.
        self.assertNotIn('glinerLabBaselines', audit)  # Different datasets cannot be compared.

    def test_initialization_does_not_replace_saved_references(self):
        vm = (ROOT / 'gliner-workflow/src/appIntegration/java/com/n8rtz/winetracker/viewmodels/GlinerLabViewModel.kt').read_text()
        activity = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerLabActivity.kt').read_text()
        self.assertIn('GlinerAuditStore.loadReferences(app).ifEmpty', vm)
        self.assertIn('installBundledAudit(saveBaseline=GlinerAuditStore.loadLatest(app)==null)', vm)
        self.assertIn('session.auditInitMutex.withLock', vm)
        self.assertIn('onUseBundledAudit=vm::useBundledAudit', activity)


if __name__ == '__main__':
    unittest.main()
