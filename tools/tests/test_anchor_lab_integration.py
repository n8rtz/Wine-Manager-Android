"""Routing guardrails; behavior is executed by AnchorLabValidationTest on Android."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
LAB=ROOT/'app/src/main/java/com/yourapp/wine/textlab'
class AnchorLabIntegrationTests(unittest.TestCase):
    def test_default_off_explicit_mode_and_no_second_baseline(self):
        state=(LAB/'TextLabViewModel.kt').read_text();batch=(LAB/'TextLabBatch.kt').read_text()
        self.assertIn('anchorHybrid:Boolean=false',state)
        self.assertIn('anchorHybrid:Boolean=false',batch)
        self.assertIn('compareEvidence && anchorHybrid',batch)
        preview=(LAB/'TextLabEvidencePreview.kt').read_text()
        self.assertNotIn('FillEngine',preview)
        self.assertNotIn('TextLabFormPreview.append',preview)
        self.assertIn('existing successful baseline summary',preview)
        self.assertIn('EvidenceAnchorHybrid.Config(true)',preview)
        self.assertIn('cuveeRole=useAnchors',preview)
        self.assertIn('"RED" else "YELLOW"',preview)

    def test_validation_is_synthetic_not_another_corpus_run(self):
        workflow=(ROOT/'.github/workflows/anchor-lab-validation.yml').read_text()
        self.assertIn('run_anchor_lab_validation.sh',workflow)
        self.assertNotIn('anchor_iteration.py',workflow)
        self.assertNotIn('appellation_iteration.py',workflow)
        self.assertIn('emulator-boot-timeout: 600',workflow)
        self.assertIn('set -euo pipefail',workflow)
        script=(ROOT/'tools/corpus/run_anchor_lab_validation.sh').read_text()
        self.assertIn('anchorLabValidation true',script)
        self.assertNotIn('input.json',script)
