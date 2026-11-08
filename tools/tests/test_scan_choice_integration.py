import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
MAIN=ROOT/'app/src/main/java'

class ScanChoiceIntegrationTests(unittest.TestCase):
    def test_ranked_alternatives_only_after_explicit_clear(self):
        policy=(MAIN/'com/yourapp/wine/ui/ScanChoicePolicy.kt').read_text()
        self.assertIn('result.fallbackCandidates[field]',policy)
        self.assertIn('result.alternatives[field]',policy)
        self.assertIn('val minimum=if(afterExplicitClear) 1 else 2',policy)
        self.assertIn('candidates.take(3)',policy)
        self.assertIn('result.intentionalBlankFields',policy)
        selector=(MAIN/'com/yourapp/wine/extract/IdentityAssignment.kt').read_text()
        self.assertIn('if(options.size>1) fallback[field]=options',selector)
        self.assertIn('state=UiState.YELLOW',selector)
        self.assertIn('top-ranked match shown despite',selector)
        self.assertIn('fallbackCandidates=extracted.fallbackCandidates',(MAIN/'com/yourapp/wine/fill/FillEngine.kt').read_text())
    def test_ui_wiring_and_review_state_safety(self):
        ui=(MAIN/'com/yourapp/wine/ui/ScanFieldStatus.kt').read_text()
        choices=(MAIN/'com/yourapp/wine/ui/ScanFieldChoices.kt').read_text()
        self.assertIn('showChoices:Boolean=true',ui)
        self.assertIn('AssistChip(',choices)
        self.assertIn('field.clearedByUser',choices)
        self.assertIn('None — leave blank / type below',choices)
        screen=(MAIN/'com/n8rtz/winetracker/ui/screens/AddWineScreen.kt').read_text()
        # The choice policy remains available to testing/legacy flows, but production Add Wine
        # uses ordinary editable fields without the diagnostic review UI or confirmation gate.
        self.assertNotIn('ScanReviewHeader(',screen)
        self.assertNotIn('ScanFieldStatus(',screen)
        self.assertNotIn('ScanFieldChoices(',screen)
        self.assertNotIn('scanForm.hasPendingConfirmation',screen)
        self.assertIn('scanForm.clearSuggestion(field, lastScanReport?.fill',screen)
        self.assertEqual(screen.count('clearIcon(FieldId.'),6)
        self.assertIn('enabled = !labelScanState.isBusy',screen)
        model=(MAIN/'com/yourapp/wine/ui/ScanFormState.kt').read_text()
        self.assertIn('User selected OCR alternative',model)
        self.assertIn('current.choices.none { it.value==value }',model)
    def test_real_kotlin_policy_checks_cover_autofill_and_correct_blanks(self):
        checks=(ROOT/'tools/logic-checks/ScanChoiceChecks.kt').read_text()
        for token in ['UiState.GREEN,UiState.YELLOW','CellarFieldPresentation.apply','IdentityAssignment.select','ScanFormState','Imported by Importer Name','Napa Valley','intentionalBlankFields','FillEngine(FixtureKnowledgeBase())']:
            self.assertIn(token,checks)
