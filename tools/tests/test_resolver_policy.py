"""Lab v4 single-reading resolver policy: Prod untouched, Lab v4 opt-in, every other gate kept.

The resolvers are Kotlin, so this asserts the wiring contract and the exact Prod wording that
host replays and stored CSVs already depend on. The behaviour itself is exercised by the Kotlin
host checks `ProducerResolverChecks` and `LinkedWineResolverChecks`.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRODUCER = ROOT / 'app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt'
LINKED = ROOT / 'app/src/main/java/com/yourapp/wine/gazetteer/LinkedWineResolver.kt'
SPATIAL = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt'
EXTRACTION = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabWineExtraction.kt'
BATCH = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabBatch.kt'
RUNNER = ROOT / 'app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt'
LAB_SCREEN = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/OcrTextLabActivity.kt'
FUNNEL = ROOT / 'app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt'


class ResolverPolicyWiringTests(unittest.TestCase):
    def test_prod_keeps_the_two_engine_default(self):
        producer = PRODUCER.read_text()
        for fragment in ('data class Policy(val minimumIndependentEngines:Int=2)',
                         'fun resolve(existing:String,spans:List<Span>,checkBudget:()->Unit={},policy:Policy=Policy.prod)',
                         'val prod=Policy()',
                         'val singleReading=Policy(1)'):
            self.assertTrue(fragment in producer, fragment)
        self.assertTrue('policy:ProducerResolver.Policy=ProducerResolver.Policy.prod' in LINKED.read_text())
        # The Lab switch (on for the 2026-10-27 measurement run) is the only path to the lab policy;
        # the Prod v3.0 contender still never receives it.
        self.assertTrue('internal const val SINGLE_READING_RESOLVERS_IN_BENCHMARK=true' in RUNNER.read_text())

    def test_every_independence_check_follows_the_policy(self):
        linked = LINKED.read_text()
        for fragment in ('filterValues { rows -> policy.supports(rows) }',
                         'distinct().size>=policy.minimumIndependentEngines',
                         'policy.minimumIndependentEngines<2 || rows.any'):
            self.assertTrue(fragment in linked, fragment)
        # No second, hard-coded two-engine rule may survive next to the policy.
        self.assertNotIn('a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() }\n        trace.visit("independent"',
                         PRODUCER.read_text())

    def test_the_lab_policy_reaches_lab_v4_only(self):
        extraction = EXTRACTION.read_text()
        self.assertIn('val policy=if(expanded && singleReading)', extraction)
        self.assertIn('Policy.singleReading', extraction)
        self.assertIn('expanded=true,singleReading=batch.resolverSingleReading', extraction)
        # The Prod v3.0 contender must never receive the flag.
        self.assertIn('appendSpatialContender(context,it,experimental,hybrid,repair,skip,expanded=false) }', extraction)
        self.assertNotIn('expanded=false,singleReading', extraction)
        self.assertIn(',policy=policy)', extraction)
        self.assertIn('policy:ProducerResolver.Policy=ProducerResolver.Policy.prod', SPATIAL.read_text())

    def test_prod_wording_and_switch_telemetry_are_unchanged(self):
        producer = PRODUCER.read_text()
        self.assertIn('"two engines" else "one engine reading under the lab single-reading policy"', producer)
        self.assertIn('"independent producer and wine OCR agree with primary LWIN relationship; confirm manually"',
                      LINKED.read_text())
        self.assertIn('"resolver_single_reading_enabled" to resolverSingleReading.toString()', BATCH.read_text())
        self.assertIn('Accept one engine reading for producer and linked wine (experimental)', LAB_SCREEN.read_text())
        self.assertIn('Lab v4 only; Prod v3.0 keeps the two-engine rule.', LAB_SCREEN.read_text())
        # The table must not keep claiming a two-engine quorum the lab run may not have used.
        funnel = FUNNEL.read_text()
        self.assertIn('"Independent-support agreement"', funnel)
        self.assertNotIn('"Two-engine + disjoint agreement"', funnel)

    def test_span_fuzzy_supplemental_guard_and_final_value_pass_are_separate(self):
        producer = PRODUCER.read_text()
        self.assertIn('MAX_PRODUCER_FUZZY_CANDIDATES=256', producer)
        self.assertIn('MAX_FINAL_NEAR_MATCH_DISTANCE=2', producer)
        self.assertIn('fun finalNearMatch(value:String', producer)
        self.assertIn('finalNearMatch(finalProducer,checkProducerBudget)', SPATIAL.read_text())
        self.assertIn('rows.none { it.distance==0 }', producer)
        self.assertIn('rows.none { "lwin" in it.entity.support }', producer)
        self.assertIn('!independent(rows)', producer)
        self.assertIn('single-engine fuzzy supplemental candidate needs LWIN or independent engines', producer)
        final_start=producer.index('fun finalNearMatch(')
        resolve_start=producer.index('fun resolve(',final_start)
        final_pass=producer[final_start:resolve_start]
        self.assertIn('index.lookup(query,MAX_PRODUCER_FUZZY_CANDIDATES',final_pass)
        self.assertNotIn('policy.',final_pass)  # terminal pass is independent of span acceptance
        self.assertGreater(SPATIAL.read_text().index('val finalProducer='),SPATIAL.read_text().index('if(backfilled)'))
        replay=(ROOT/'tools/host-checks/producer_resolver_replay.py').read_text()
        self.assertIn('MAX_PRODUCER_FUZZY_CANDIDATES = 256',replay)
        self.assertIn('candidate_limit=MAX_PRODUCER_FUZZY_CANDIDATES',replay)

    def test_exact_match_fast_path_is_lab_only(self):
        producer = PRODUCER.read_text()
        linked = LINKED.read_text()
        spatial = SPATIAL.read_text()
        self.assertIn('var exactMatchFastPath:Boolean=false', producer)
        self.assertIn('fun withExactMatchFastPath(', producer)
        self.assertIn('if(policy.exactMatchFastPath)', producer)
        self.assertIn('if(policy.exactMatchFastPath)', linked)
        self.assertIn('.withExactMatchFastPath { term -> engine?.isNonProducerCatalogTerm(term)==true }', spatial)

    def test_exact_geography_fast_path_is_lab_only_and_feeds_context(self):
        evidence = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt').read_text()
        label_ctx = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/evidence/LabelContext.kt').read_text()
        preview = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt').read_text()
        spatial = SPATIAL.read_text()
        # Default stays false so Prod v3.0 and SavedEvidenceReplayTest stay untouched.
        self.assertIn('fun extract(records:List<TokenRecord>,year:Int,budgetMs:Long=DEFAULT_BUDGET_MS,exactGeographyFastPath:Boolean=false):EvidenceResult', evidence)
        self.assertIn('exactGeographyFastPath: Boolean = false,', label_ctx)
        self.assertIn('exactGeographyFastPath:Boolean=false', preview)
        self.assertIn('exactGeographyFastPath=policy.minimumIndependentEngines<2', spatial)
        # Exact d=0 geography fast-path locks coherent chains and feeds LabelContext + ProducerResolver country context.
        self.assertIn('trace.add("exact_geography_fast_path"', evidence)
        self.assertIn('d=0 exact geography fast-path', evidence)
        self.assertIn('LabelContext.collect(graph,gazetteer,view,kb,typedIdentity,true)', evidence)
        self.assertIn('policy.withCountryContext(row["country"].orEmpty())', spatial)


if __name__ == '__main__':
    unittest.main()
