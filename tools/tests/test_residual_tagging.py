import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
LAB=ROOT/'app/src/main/java/com/yourapp/wine/textlab'

class ResidualTaggingIntegrationTests(unittest.TestCase):
    def test_defaults_and_shared_resolver_are_opt_in(self):
        for f in ['TextLabBatch.kt','TextLabViewModel.kt']:
            self.assertIn('residualCandidates:Boolean=false',(LAB/f).read_text())
        self.assertIn('batch.compareGazetteer || batch.residualCandidates',(LAB/'TextLabWineExtraction.kt').read_text())
        self.assertIn('if(batch.residualCandidates)',(LAB/'TextLabWineExtraction.kt').read_text())
        self.assertIn('TextLabResidualPreview.text(journals)',(LAB/'TextLabBatch.kt').read_text())
    def test_discovery_never_assigns_form_fields_or_mutates_raw_readings(self):
        s=(LAB/'TextLabResidualPreview.kt').read_text()
        for forbidden in ['FillEngine(','.fill(','.extract(','.rows.remove','UiState.GREEN']:
            self.assertNotIn(forbidden,s)
        for required in ['"unassigned"','"UTF-16; end exclusive"','"whole source observation; NOT a derived character box"','cache.size<256','records.size<=128','catch(cancel:CancellationException)','"residual_summary","failed"']:
            self.assertIn(required,s)
        production_roots=[ROOT/'app/src/main/java/com/yourapp/wine/capture',ROOT/'app/src/main/java/com/yourapp/wine/fill']
        for root in production_roots:
            for p in root.rglob('*.kt'):
                self.assertNotIn('ResidualTagger',p.read_text())
    def test_real_kotlin_harness_and_contrastive_cases_exist(self):
        script=(ROOT/'tools/run-residual-checks.sh').read_text()
        self.assertIn('kotlinc',script);self.assertIn('java" -jar',script)
        self.assertNotIn('gh ',script);self.assertNotIn('adb ',script)
        test=(ROOT/'tools/logic-checks/ResidualTaggerCheck.kt').read_text()
        for example in ['Domaine du Riesling','Domaine de la Réserve','La Réserve','Founded 2019','Pauilac','Imported by Acme','19 Crimes','BundledGrapes.terms']:
            self.assertIn(example,test)
