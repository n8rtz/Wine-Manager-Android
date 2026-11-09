"""Static contract checks for guarded Lab-only OCR vintage recovery and producer evidence routing."""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt"
GRAPH = ROOT / "app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt"
HYBRID = ROOT / "app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceAnchorHybrid.kt"
PREVIEW = ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt"
SPATIAL = ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt"
RESOLVER = ROOT / "app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt"
DEVICE_TEST = ROOT / "app/src/androidTest/java/com/yourapp/wine/textlab/VintageRecoveryDeviceTest.kt"


class VintageRecoveryTests(unittest.TestCase):
    def test_confused_and_spaced_years_are_normalized_in_a_plausible_context(self):
        source = EVIDENCE.read_text()
        self.assertIn('private fun normalizeVintageCandidate(raw:String,maxYear:Int)', source)
        self.assertIn("chars[0] in setOf('i','l') && chars[1]=='9'", source)
        self.assertIn("chars[0]=='2' && chars[1]=='o'", source)
        self.assertIn('private val yearTokenPattern', source)
        self.assertIn('private val canonicalYearPattern', source)
        self.assertIn('numeric in 1900..maxYear', source)
        self.assertIn('val standaloneYear=normalizeVintageCandidate(text,year)', source)

    def test_split_vintage_preserves_observations_and_requires_same_pass_symmetric_geometry(self):
        source = GRAPH.read_text()
        for fragment in (
            'same-engine, same-variant two-digit fragments',
            'it.observation.record.engineId to it.observation.record.variantId',
            'pairCenter-labelCenter)>.10f',
            'leftDistance-rightDistance)>.04f',
            'setOf(left.observation.id,right.observation.id)',
            '"split crest vintage"',
        ):
            self.assertIn(fragment, source)
        device = DEVICE_TEST.read_text()
        self.assertIn('I998', device)
        self.assertIn('l996', device)
        self.assertIn('2O20', device)
        self.assertIn('2 0 2 0', device)
        self.assertIn('crossEngine', device)
        self.assertIn('skewed', device)

    def test_hybrid_preserves_populated_baseline_and_validates_blank_fallback(self):
        source = HYBRID.read_text()
        self.assertIn('maxVintageYear:Int=Calendar.getInstance().get(Calendar.YEAR)', source)
        self.assertIn('if(savedVintage.isNotBlank()) savedVintage', source)
        self.assertIn('canonicalVintagePattern.matches(candidate)', source)
        self.assertIn('year in 1900..maxVintageYear', source)
        self.assertIn('assertEquals("2018"', DEVICE_TEST.read_text())
        preview = PREVIEW.read_text()
        self.assertIn('TextLabEvidencePreview.append', SPATIAL.read_text())
        self.assertIn('hybrid!!.combine(original,fill.fields.mapValues { it.value.value },records,EvidenceAnchorHybrid.Config(true),year)', preview)
        self.assertIn('Validated experimental vintage filled a blank baseline anchor', preview)

    def test_clean_graph_producer_hypotheses_reach_resolver_with_role_penalties(self):
        source = SPATIAL.read_text()
        self.assertIn('result.hypotheses.filter { com.yourapp.wine.fill.FieldId.PRODUCER in it.values }', source)
        self.assertIn('val engineIds=sources.map { it.record.engineId }.distinct()', source)
        self.assertIn('val variants=sources.map { it.record.variantId }.distinct()', source)
        self.assertIn('producerAnchorSpans(before,original,proposals)+evidenceSpans', source)
        self.assertIn('.withProducerPenalty { term -> engine?.producerCandidatePenalty(term) ?: 0 }', source)
        resolver = RESOLVER.read_text()
        self.assertIn('private val minorProducerParticles=setOf("de")', resolver)
        self.assertIn('Producer-name comparison only: omission of the minor French particle `de` costs zero.', resolver)
        self.assertIn('fun distance(a:String,b:String):Int=characterDistance(a,b)', resolver)
        self.assertIn('val d=producerDistance(q,e.key)+rolePenalty', resolver)
        evidence = EVIDENCE.read_text()
        self.assertIn('if(containsCatalogSequence(words,grapeCandidateKeys,grapeCandidateMaxWords)) return 3', evidence)
        self.assertIn('return if(containsCatalogSequence(words,geographicCandidateKeys,geographicCandidateMaxWords)) 1 else 0', evidence)

    def test_host_replay_uses_the_same_zero_cost_particle_rule(self):
        path = ROOT / "tools/host-checks/producer_resolver_replay.py"
        spec = importlib.util.spec_from_file_location("producer_resolver_replay", path)
        replay = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(replay)
        observed = replay.normalize("Les Ormes Cambras")
        indexed = replay.normalize("Les Ormes de Cambras")
        self.assertEqual(replay.distance(observed, indexed), 0)


if __name__ == "__main__":
    unittest.main()
