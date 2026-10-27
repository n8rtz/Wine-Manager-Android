"""Lab v4 selection rules: designation bases, bare designations, fragments, level pairs.

The extractor is Kotlin, so this pins the rules by mirroring the one deterministic part over the
real catalog (`WineDesignations.split` + `classificationBase`) and by asserting the wiring of the
rules that cannot be executed on the host: bare-designation rejection, single-word fragment
rejection, one-name-per-level, and the equivalent-name projection.
"""
import csv
import re
import unittest
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'data/wine-knowledge'
EVIDENCE = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt'
DESIGNATIONS = ROOT / 'app/src/main/java/com/yourapp/wine/extract/WineDesignations.kt'
PREVIEW = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt'
RUNNER = ROOT / 'app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt'

# Mirrors WineDesignations.classificationSuffixes (keys, letters and digits only).
CLASSIFICATION = {'grandcru', 'grandcruclasse', 'premiergrandcruclasse',
                  'superieur', 'superieure', 'superieures'}


def key(text):
    value = unicodedata.normalize('NFKD', text.lower())
    value = ''.join(ch for ch in value if unicodedata.category(ch) != 'Mn')
    return ''.join(ch for ch in re.sub(r'[^a-z0-9]+', ' ', value) if ch.isalnum())


def suffix_terms():
    with (DATA / 'label-terms.csv').open(newline='') as handle:
        return [row['term'] for row in csv.DictReader(handle) if row['role'] == 'suffix']


def split(text, terms=None):
    """Mirror of WineDesignations.split: longest suffix first, trailing match before leading."""
    clean = text.strip()
    for name in sorted(terms or suffix_terms(), key=len, reverse=True):
        pattern = r'\s+'.join(re.escape(word) for word in name.split(' '))
        end = re.search(r'\s+(' + pattern + r')$', clean, re.IGNORECASE)
        if end:
            return clean[:end.start()].strip(), name
        start = re.search(r'^(' + pattern + r')\s+', clean, re.IGNORECASE)
        if start:
            return clean[start.end():].strip(), name
    return clean, None


def classification_base(text, terms=None):
    base, suffix = split(text, terms)
    if suffix is None or key(suffix) not in CLASSIFICATION:
        return None
    return base if sum(ch.isalpha() for ch in base) >= 3 else None


def catalog_names():
    with (DATA / 'origins.csv').open(newline='') as handle:
        return {row['name']: row for row in csv.DictReader(handle)}


class ClassificationBaseTests(unittest.TestCase):
    def test_the_reviewer_cases_keep_their_expected_level(self):
        # The benchmark expects these to resolve to the base place, with the printed form kept
        # as the wine name.
        self.assertEqual(classification_base('Saint-Émilion Grand Cru'), 'Saint-Émilion')
        self.assertEqual(classification_base('Bordeaux Supérieur'), 'Bordeaux')
        self.assertEqual(classification_base('Médoc Supérieur'), 'Médoc')
        self.assertEqual(classification_base('Graves Supérieures'), 'Graves')
        # Zone-forming suffixes name a different place and must never be collapsed.
        for text in ('Chianti Classico', 'Soave Superiore', 'Beaujolais Villages',
                     'Barolo Riserva', 'Amarone della Valpolicella'):
            self.assertIsNone(classification_base(text), text)

    def test_every_classification_qualified_catalog_name_has_a_base_in_the_catalog(self):
        catalog = catalog_names()
        bases = {name for name in catalog}
        qualified = [(name, classification_base(name)) for name in catalog
                     if classification_base(name) is not None]
        self.assertTrue(qualified, 'the catalog should contain classification-qualified names')
        missing = [(name, base) for name, base in qualified if base not in bases]
        self.assertEqual(missing, [], f'base missing from catalog: {missing}')
        # Known representatives, so a silent catalog change cannot empty this test.
        names = {name for name, _ in qualified}
        for name in ('Saint-Émilion Grand Cru', 'Bordeaux Supérieur'):
            self.assertIn(name, names)


class EvidenceSelectionWiringTests(unittest.TestCase):
    def test_a_bare_designation_is_never_a_wine_name(self):
        source = EVIDENCE.read_text()
        self.assertIn('if(WineDesignations.isDesignation(text)) { trace.add("bare_designation"', source)
        self.assertIn('if(roles.isWineDescription(text)) {\n                offer(mapOf(FieldId.WINE_NAME to '
                      '(WineDesignations.display(text) ?: text)),75.0,"observed varietal")', source)
        # The combined offer that promoted GRAND CRU / DOCG to a wine name must be gone.
        self.assertNotIn('"observed varietal or designation"', source)

    def test_one_word_fragments_cannot_become_an_identity(self):
        source = EVIDENCE.read_text()
        self.assertIn("val fragmentOnly=norm.split(' ').size==1 && incomplete>0", source)
        self.assertIn('if(fragmentOnly && values.keys.all { it==FieldId.PRODUCER || it==FieldId.WINE_NAME })', source)
        self.assertIn('trace.add("fragment_identity"', source)

    def test_classification_names_resolve_to_the_base_place(self):
        source = EVIDENCE.read_text()
        self.assertIn('val printedBase=WineDesignations.classificationBase(text)', source)
        self.assertIn('WineDesignations.classificationBase(place.name)', source)
        self.assertIn('printed classification qualifies the matched appellation', source)
        # The base suppresses the unresolved-suffix rule, which would otherwise withhold the subregion.
        self.assertIn('val unresolvedSuffix=repair==null && !classificationName', source)

    def test_one_name_never_fills_two_levels(self):
        source = EVIDENCE.read_text()
        self.assertIn('!unresolvedSuffix && values[FieldId.REGION]!=place.name)', source)

    def test_equivalent_names_and_the_measurement_switches(self):
        self.assertIn('EvidenceRankingConfig(equivalentNames=true', PREVIEW.read_text())
        runner = RUNNER.read_text()
        self.assertIn('internal const val REPAIR_PLACES_IN_BENCHMARK=true', runner)
        self.assertIn('internal const val SINGLE_READING_RESOLVERS_IN_BENCHMARK=true', runner)
        self.assertIn('evidence-lab-v4-designation-base', EVIDENCE.read_text())


if __name__ == '__main__':
    unittest.main()
