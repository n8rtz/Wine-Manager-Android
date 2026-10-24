import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
MAIN=ROOT/'app/src/main/java'

class ScanChoiceIntegrationTests(unittest.TestCase):
    def test_explicit_ambiguity_channel_not_all_alternatives(self):
        policy=(MAIN/'com/yourapp/wine/ui/ScanChoicePolicy.kt').read_text()
        self.assertIn('result.fallbackCandidates[field]',policy)
        self.assertNotIn('result.alternatives',policy)
        self.assertIn('candidates.size<2',policy)
        self.assertIn('candidates.take(3)',policy)
        self.assertIn('result.intentionalBlankFields',policy)
        selector=(MAIN/'com/yourapp/wine/extract/IdentityAssignment.kt').read_text()
        self.assertIn('if(rivals.isNotEmpty()) fallback[field]',selector)
        self.assertIn('fallbackCandidates=extracted.fallbackCandidates',(MAIN/'com/yourapp/wine/fill/FillEngine.kt').read_text())
    def test_ui_wiring_and_review_state_safety(self):
        ui=(MAIN/'com/yourapp/wine/ui/ScanFieldStatus.kt').read_text()
        self.assertIn('AssistChip(',ui)
        self.assertIn('field.value.isBlank() && !field.edited',ui)
        self.assertIn('None — leave blank / type below',ui)
        screen=(MAIN/'com/n8rtz/winetracker/ui/screens/AddWineScreen.kt').read_text()
        self.assertIn('scanForm.selectChoice(field,value)',screen)
        self.assertIn('scanForm.dismissChoices(field)',screen)
        self.assertIn('enabled = !labelScanState.isBusy',screen)
        model=(MAIN/'com/yourapp/wine/ui/ScanFormState.kt').read_text()
        self.assertIn('User selected OCR alternative',model)
        self.assertIn('current.choices.none { it.value==value }',model)
    def test_real_kotlin_policy_checks_cover_autofill_and_correct_blanks(self):
        checks=(ROOT/'tools/logic-checks/ScanChoiceChecks.kt').read_text()
        for token in ['UiState.GREEN,UiState.YELLOW','CellarFieldPresentation.apply','IdentityAssignment.select','ScanFormState','Imported by Importer Name','Napa Valley','intentionalBlankFields','FillEngine(FixtureKnowledgeBase())']:
            self.assertIn(token,checks)
