"""Source contracts complement executable Kotlin SpanSafetyChecks; no OCR accuracy assertions."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAB = ROOT / 'app/src/main/java/com/yourapp/wine/textlab'


class SpanSafetyContracts(unittest.TestCase):
    def test_vintage_eligibility_uses_original_context_at_discovery_and_selection(self):
        source = (LAB/'evidence/EvidenceExtractor.kt').read_text()
        self.assertIn('foundingYearCue.containsMatchIn(TextNorm.normalize(o.record.textRaw))', source)
        self.assertIn('val foundingSource=sources.any', source)
        self.assertIn('!administrativeSource && !foundingSource', source)
        self.assertIn('vintage_context_rejected', source)
        self.assertIn('sharesVintageSource=sharesVintageContext(standaloneYear)', source)
        self.assertIn('discoverSpans(it,year,::checkBudget,trace)', source)

    def test_ownership_is_structured_and_exported_not_reason_text(self):
        solver = (LAB/'evidence/EvidenceSolver.kt').read_text()
        self.assertIn('next.sharesVintageSource', solver)
        self.assertNotIn('next.reason.startsWith(', solver)
        self.assertIn('Triple(it.values,it.phrase.observations,it.sharesVintageSource)', solver)
        preview = (LAB/'TextLabEvidencePreview.kt').read_text()
        self.assertEqual(2, preview.count('"shares_vintage_source" to h.sharesVintageSource.toString()'))

    def test_terminal_guard_uses_earlier_evidence_and_rejects_identity_ties(self):
        preview = (LAB/'TextLabSpatialPreview.kt').read_text()
        self.assertIn('supportingMatches=decision.matches', preview)
        self.assertIn('onRejected={ reason -> nearMatchError=reason }', preview)
        resolver = (ROOT/'app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt').read_text()
        terminal = resolver.split('fun finalNearMatch(', 1)[1].split('    fun resolve(', 1)[0]
        self.assertIn('rows.any { it.distance==0 } || independent', terminal)
        self.assertIn('family(a.engine)!=family(b.engine)', terminal)
        self.assertIn('a.observationIds.intersect(b.observationIds).isEmpty()', terminal)
        self.assertIn('if(candidates.count { it.distance==bestDistance }!=1)', terminal)
        self.assertIn('return winner.takeIf { supported(it.entity) }', terminal)
        self.assertIn('insufficient_supplemental_support', terminal)
