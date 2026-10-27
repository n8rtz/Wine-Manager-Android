"""Lab-only bounded appellation repair: policy mirror plus wiring contract.

The Kotlin side cannot run on the host, so this mirrors the exact arithmetic of
`BoundedSpelling.distance` / `PlaceRepair.resolve` over the real catalog and separately
asserts the wiring (off by default, exact-match precedence, two-engine gate, telemetry).
"""
import csv
import re
import unicodedata
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'data/wine-knowledge'
EVIDENCE = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt'
REPAIR = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/evidence/PlaceRepair.kt'
BATCH = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabBatch.kt'
RUNNER = ROOT / 'app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt'
LAB_SCREEN = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/OcrTextLabActivity.kt'


def normalize(text):
    """TextNorm.normalize: NFKD, drop marks, collapse whitespace, trim, lowercase."""
    value = unicodedata.normalize('NFKD', text)
    value = ''.join(ch for ch in value if unicodedata.category(ch) != 'Mn')
    return ' '.join(value.split()).strip().lower()


def key(text):
    """BoundedSpelling.key."""
    return re.sub(r'[^\w]+', ' ', normalize(text)).strip()


def bounded_distance(observed, form):
    """Mirror of BoundedSpelling.distance: None means outside the policy."""
    a, b = key(observed), key(form)
    if a == b:
        return 0
    if not (5 <= len(a) <= 64) or not (5 <= len(b) <= 64):
        return None
    if re.search(r'\d', a) or re.search(r'\d', b):
        return None
    if len(a.split()) > 6 or abs(len(a.split()) - len(b.split())) > 1:
        return None
    limit = 2 if min(len(a), len(b)) >= 10 else 1
    if abs(len(a) - len(b)) > limit:
        return None
    distance = levenshtein(a, b, limit)
    if distance is None:
        return None
    return distance if distance / max(len(a), len(b)) <= 0.2 else None


def levenshtein(a, b, bound):
    if abs(len(a) - len(b)) > bound:
        return None
    previous = [j for j in range(len(b) + 1)]
    for i in range(len(a)):
        current = [bound + 1] * (len(b) + 1)
        current[0] = i + 1
        for j in range(max(1, i + 1 - bound), min(len(b), i + 1 + bound) + 1):
            current[j] = min(current[j - 1] + 1, previous[j] + 1,
                             previous[j - 1] + (0 if a[i] == b[j - 1] else 1))
        previous = current
        if min(previous) > bound:
            return None
    return previous[len(b)] if previous[len(b)] <= bound else None


def catalog_forms(rows=None):
    """Every non-country catalog name and alias, as (place_name, form)."""
    if rows is None:
        with (DATA / 'origins.csv').open(newline='') as handle:
            rows = list(csv.DictReader(handle))
    forms = []
    for row in rows:
        if row['name_role'] == 'country':
            continue
        forms.append((row['name'], row['name']))
        for alias in [a.strip() for a in row['aliases'].split(';') if a.strip()]:
            forms.append((row['name'], alias))
    return forms


def resolve(observed, forms, anchors=('France',), engine_readings=('mlkit', 'paddle')):
    """Mirror of PlaceRepair.resolve: same winner, ambiguity, anchor-count and two-engine rules.

    Anchor *compatibility* (country/ancestor agreement) needs the place hierarchy and is
    covered by the JVM check `ReferenceCatalogChecks.placeRepairIsBoundedAnchoredAndTwoEngineGated`.
    """
    query = key(observed)
    if not (5 <= len(query) <= 64) or len(query.split()) > 6:
        return None
    if not anchors or len(anchors) != 1:
        return None
    best, winners = None, []
    for place, form in forms:
        if key(form) == query:
            continue
        distance = bounded_distance(observed, form)
        if distance is None:
            continue
        if best is None or distance < best:
            best, winners = distance, [(place, form)]
        elif distance == best:
            winners.append((place, form))
    places = {place for place, _ in winners}
    if len(places) != 1:
        return None
    place = places.pop()
    if len(set(engine_readings)) < 2:
        return None
    return place, winners[0][1], best


class PlaceRepairPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.forms = catalog_forms()

    def test_one_edit_typo_resolves_to_the_single_known_appellation(self):
        resolved = resolve('Pauilac', self.forms)
        self.assertIsNotNone(resolved)
        self.assertEqual(resolved[0], 'Pauillac')
        self.assertEqual(resolved[2], 1)
        self.assertEqual(resolve('Bordeux', self.forms)[0], 'Bordeaux')
        self.assertEqual(resolve('Chiantii', self.forms)[0], 'Chianti')

    def test_two_edits_are_allowed_only_for_long_names(self):
        # Ten characters or more may lose two characters; shorter names may lose one.
        self.assertEqual(resolve('Barbaresko', self.forms)[0], 'Barbaresco')
        self.assertIsNone(resolve('Barloo', self.forms))

    def test_short_style_words_and_fragments_are_never_repaired(self):
        for query in ('Pom', 'Dao', 'Ahr', 'Vino'):
            self.assertIsNone(resolve(query, self.forms), query)

    def test_an_exact_reading_is_never_a_repair(self):
        # The trie owns exact text; a repair may only explain text it could not read.
        for query in ('Pauillac', 'Bordeaux', 'Chianti'):
            self.assertIsNone(resolve(query, self.forms), query)

    def test_competing_places_abstain_instead_of_picking_a_winner(self):
        # Two catalog names at the same distance from the reading must never be sorted by luck.
        synthetic = [('Alpha One', 'Alpha One'), ('Alpha Ono', 'Alpha Ono')]
        self.assertIsNone(resolve('Alpha On', synthetic))

    def test_the_anchor_and_two_engine_gates_are_required(self):
        self.assertIsNotNone(resolve('Pauilac', self.forms, anchors=('France',)))
        self.assertIsNone(resolve('Pauilac', self.forms, anchors=()))
        self.assertIsNone(resolve('Pauilac', self.forms, anchors=('France', 'Italy')))
        self.assertIsNone(resolve('Pauilac', self.forms, engine_readings=('mlkit',)))

    def test_unrelated_text_is_not_forced_onto_a_place(self):
        for query in ('Chateau Mouton', 'Grand Vin de Bordeaux', 'Mis en bouteille'):
            self.assertIsNone(resolve(query, self.forms), query)


class PlaceRepairWiringTests(unittest.TestCase):
    def test_repair_is_off_by_default_and_only_runs_on_unmatched_phrases(self):
        extractor = EVIDENCE.read_text()
        repair = REPAIR.read_text()
        for fragment in ('private val placeRepair:PlaceRepair.Config=PlaceRepair.Config.off',
                         'if(placeRepair.enabled && exact.isEmpty()'):
            self.assertTrue(fragment in extractor, fragment)
        self.assertNotIn('placeRepair.enabled || exact.isEmpty()', extractor)
        for fragment in ('val off=Config()', 'val experimental=Config(enabled=true)'):
            self.assertTrue(fragment in repair, fragment)

    def test_two_engines_and_a_separate_origin_anchor_are_enforced(self):
        repair = REPAIR.read_text()
        for fragment in ('config.minimumEngines', 'corroborating.size<config.minimumEngines',
                         'anchors.map { it.countryCode }.distinct().size!=1',
                         'anchors.none { view.compatible(place,it) }'):
            self.assertTrue(fragment in repair, fragment)
        extractor = EVIDENCE.read_text()
        for fragment in ('IdentityRoles.originOnly(otherText,listOf(place))',
                         'place.type in setOf("country","region")'):
            self.assertTrue(fragment in extractor, fragment)

    def test_a_repair_cannot_outrank_exact_evidence_or_name_the_wine(self):
        extractor = EVIDENCE.read_text()
        for fragment in ('placeRepair.rankPenalty',
                         'if(repair==null && places.any { TextNorm.normalize(it.name)==norm }',
                         'val unresolvedSuffix=repair==null && place.type=="appellation"'):
            self.assertTrue(fragment in extractor, fragment)
        self.assertNotIn('UiState.GREEN', extractor)

    def test_the_switch_is_recorded_and_marked_lab_only(self):
        runner = RUNNER.read_text()
        for fragment in ('internal const val REPAIR_PLACES_IN_BENCHMARK=false',
                         'repairPlaces=REPAIR_PLACES_IN_BENCHMARK'):
            self.assertTrue(fragment in runner, fragment)
        self.assertTrue('"place_repair_enabled" to repairPlaces.toString()' in BATCH.read_text())
        self.assertTrue('Repair appellations with bounded spelling (experimental)' in LAB_SCREEN.read_text())
        self.assertTrue('Lab-only' in REPAIR.read_text())


if __name__ == '__main__':
    unittest.main()
