"""Tests for Lab v5 LabelContext, context-clue modules, Prod subset scoping, and accent scrubbing."""
from __future__ import annotations

import re
import sqlite3
import unicodedata
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WINE = ROOT / "app/src/main/java/com/yourapp/wine"

APOSTROPHE_ACUTE_RE = re.compile(r"\b([dlcnst]|qu)\u00b4(?=[aeiouyh])", re.IGNORECASE)
SPACING_ACCENTS_RE = re.compile(r"[\u0060\u00b4\u00a8\u00b8\u00af\u02c6\u02dc\u02d8-\u02dd]")
COMBINING_MARKS_RE = re.compile(r"[\u0300-\u036f\u1ab0-\u1aff\u1dc0-\u1dff\u20d0-\u20ff\ufe20-\ufe2f]+")
LETTER_FOLDS = {
    "\u00df": "ss", "\u1e9e": "SS", "\u00e6": "ae", "\u00c6": "AE",
    "\u0153": "oe", "\u0152": "OE", "\u00f8": "o", "\u00d8": "O",
    "\u0142": "l", "\u0141": "L", "\u0111": "d", "\u0110": "D",
    "\u00f0": "d", "\u00d0": "D", "\u00fe": "th", "\u00de": "TH",
    "\u0131": "i", "\u0130": "I", "\u0133": "ij", "\u0132": "IJ",
}


def scrub_accents(value: str) -> str:
    if not value or value.isascii():
        return value.replace("`", "")
    step1 = APOSTROPHE_ACUTE_RE.sub(r"\1'", value)
    step2 = SPACING_ACCENTS_RE.sub("", step1)
    folded = "".join(LETTER_FOLDS.get(ch, ch) for ch in step2)
    decomposed = unicodedata.normalize("NFD", folded)
    stripped = COMBINING_MARKS_RE.sub("", decomposed)
    return unicodedata.normalize("NFC", stripped)


FRAMED_PATTERNS = [
    re.compile(
        r"^(?:appellation(?:\s+d['\u2019\s]?origine)?)\s+(.+?)\s+(?:controlee|protegee)(?:\s+(?:aoc|aop))?$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^denominazione\s+di\s+origine\s+controllata(?:\s+e\s+garantita)?\s+(.+)$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^(.+?)\s+denominazione\s+di\s+origine\s+controllata(?:\s+e\s+garantita)?$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^indicazione\s+geografica\s+(?:tipica|protetta)\s+(.+)$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^(.+?)\s+indicazione\s+geografica\s+(?:tipica|protetta)$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^denominacion\s+de\s+origen(?:\s+(?:calificada|protegida))?\s+(.+)$",
        re.IGNORECASE,
    ),
    re.compile(
        r"^(.+?)\s+denominacion\s+de\s+origen(?:\s+(?:calificada|protegida))?$",
        re.IGNORECASE,
    ),
    re.compile(r"^wine\s+of\s+origin\s+(.+)$", re.IGNORECASE),
    re.compile(r"^(.+?)\s+american\s+viticultural\s+area$", re.IGNORECASE),
]


def framed_appellation(text: str) -> str | None:
    clean = re.sub(r"\s+", " ", scrub_accents(text)).strip(" -.,;:")
    if len(clean) < 8:
        return None
    for pattern in FRAMED_PATTERNS:
        m = pattern.match(clean)
        if not m:
            continue
        inner = m.group(1).strip(" -.,;:")
        norm = inner.lower()
        if norm in {"d'origine", "d origine", "origine", "origen"}:
            return None
        letters = sum(ch.isalpha() for ch in inner)
        if letters >= 3 and len(inner.split(" ")) <= 6 and not any(ch.isdigit() for ch in inner):
            return inner
    return None


class AccentScrubbingTests(unittest.TestCase):
    def test_spacing_accents_ligatures_and_diacritics_scrub_to_plain_letters(self):
        cases = {
            "Château d´Yquem": "Chateau d'Yquem",
            "PAUIL´LAC": "PAUILLAC",
            "CỚTES DU RHÔNE": "COTES DU RHONE",
            "Weingut Meßmer": "Weingut Messmer",
            "Chays d'Œuvre": "Chays d'OEuvre",
            "Clos Gaensbrœnnel": "Clos Gaensbroennel",
            "Winnica Łany": "Winnica Lany",
            "Kavaklıdere": "Kavaklidere",
            "Torræ del Sale": "Torrae del Sale",
            "Grüner Veltliner Spätlese": "Gruner Veltliner Spatlese",
        }
        for raw, expected in cases.items():
            with self.subTest(raw=raw):
                actual = scrub_accents(raw)
                self.assertEqual(actual, expected)
                self.assertTrue(actual.isascii())

    def test_ocr_and_gazetteer_boundaries_wire_textnorm_scrub(self):
        for rel in (
            "ocr/MlKitOcrEngine.kt",
            "textlab/PaddleCtc.kt",
            "geom/CoordMapper.kt",
            "merge/TokenMerger.kt",
            "textlab/TextLabFormPreview.kt",
            "textlab/TextLabWineExtraction.kt",
            "extract/FieldExtractor.kt",
            "extract/WineDesignations.kt",
            "kb/WineTerms.kt",
            "kb/SqliteWineKnowledgeBase.kt",
            "kb/ProducerCatalogDatabaseLookup.kt",
            "kb/WineIndexDatabaseLookup.kt",
            "kb/DatabaseNameLookup.kt",
            "gazetteer/ProducerGazetteer.kt",
            "gazetteer/ProducerResolver.kt",
            "gazetteer/LinkedWineResolver.kt",
        ):
            text = (WINE / rel).read_text(encoding="utf-8")
            self.assertIn("TextNorm.scrub", text, rel)
        self.assertNotIn("diacriticCount", (WINE / "merge/TokenMerger.kt").read_text(encoding="utf-8"))

    def test_sqlite_gazetteer_keys_with_latin_ligatures_fold_to_ascii_letters(self):
        for rel, sql in (
            ("app/src/main/assets/producer-gazetteer-v1/producers.sqlite", "SELECT name, key FROM producers"),
            ("app/src/main/assets/producer-gazetteer-expanded-v1/producers-expanded.sqlite", "SELECT name, key FROM producers"),
            ("app/src/main/assets/producer-gazetteer-v1/wines.sqlite", "SELECT name, key FROM wine_names"),
        ):
            db = ROOT / rel
            conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
            try:
                for name, key in conn.execute(sql):
                    scrubbed_key = scrub_accents(key)
                    # Every Latin letter in normalized keys must fold to plain ASCII a-z0-9.
                    for ch in scrubbed_key:
                        if ch.isalpha() and "LATIN" in unicodedata.name(ch, ""):
                            self.assertTrue(ch.isascii(), f"{rel}: unfolded Latin char {ch!r} in {name!r} / {key!r}")
            finally:
                conn.close()


class FramedAppellationAndLabelContextTests(unittest.TestCase):
    def test_framed_appellation_extracts_inner_place_and_rejects_generic_boilerplate(self):
        self.assertEqual(
            framed_appellation("APPELLATION SAINT-ÉMILION GRAND CRU CONTRÔLÉE"),
            "SAINT-EMILION GRAND CRU",
        )
        self.assertEqual(
            framed_appellation("Appellation d'Origine Pauillac Contrôlée"),
            "Pauillac",
        )
        self.assertEqual(
            framed_appellation("DENOMINAZIONE DI ORIGINE CONTROLLATA E GARANTITA BAROLO"),
            "BAROLO",
        )
        self.assertEqual(
            framed_appellation("Rioja Denominación de Origen Calificada"),
            "Rioja",
        )
        self.assertEqual(framed_appellation("Wine of Origin Stellenbosch"), "Stellenbosch")
        self.assertEqual(framed_appellation("Napa Valley American Viticultural Area"), "Napa Valley")
        self.assertIsNone(framed_appellation("APPELLATION D'ORIGINE CONTRÔLÉE"))
        self.assertIsNone(framed_appellation("MIS EN BOUTEILLE AU CHÂTEAU"))
        self.assertIsNone(framed_appellation("PRODUCE OF FRANCE"))

    def test_label_context_spatial_backfill_and_prod_subset_scoping_wiring(self):
        label_ctx = (WINE / "textlab/evidence/LabelContext.kt").read_text(encoding="utf-8")
        extractor = (WINE / "textlab/evidence/EvidenceExtractor.kt").read_text(encoding="utf-8")
        spatial = (WINE / "textlab/TextLabSpatialPreview.kt").read_text(encoding="utf-8")
        vm = (WINE / "phonecorpus/WineAppLabelsViewModel.kt").read_text(encoding="utf-8")

        self.assertIn("fun isNonOriginAdministrative", label_ctx)
        self.assertIn("fun contextCountriesForPhrase", label_ctx)
        self.assertIn("WineDesignations.framedAppellation(text)", extractor)
        self.assertIn("fun resolveLinkedWineGeography(", extractor)
        self.assertIn('if(clean.equals("Russian Federation",ignoreCase=true)) "Russia" else clean', extractor)
        self.assertIn("val isLabMode=policy.minimumIndependentEngines<2", spatial)
        self.assertIn("if(isLabMode && engine!=null)", spatial)
        self.assertIn("inferred from confirmed LWIN producer+wine pair", spatial)
        self.assertIn("unambiguous LWIN producer country", spatial)
        self.assertIn("val attempted: Set<String> = lab.mapNotNullTo(linkedSetOf())", vm)
        self.assertIn("if (attempted.isEmpty()) frozen.values.toList() else attempted.mapNotNull(frozen::get)", vm)

    def test_five_benchmark_issues_resolved_and_kotlin_generic_lexer_safe(self):
        import csv as _csv

        # Issue 1: lazy lookupDeadline + pre-normalized BoundedSpelling distance.
        extraction = (WINE / "textlab/TextLabWineExtraction.kt").read_text(encoding="utf-8")
        self.assertIn("var lookupDeadline=0L", extraction)
        self.assertIn(
            "if(lookupDeadline==0L) lookupDeadline=android.os.SystemClock.elapsedRealtime()+TextLabBudgets.GAZETTEER_LOOKUP_MS",
            extraction,
        )
        spelling = (WINE / "extract/BoundedSpelling.kt").read_text(encoding="utf-8")
        self.assertIn("private val nonAlnum = Regex(", spelling)
        self.assertIn("fun distanceNormalized(", spelling)
        place_repair = (WINE / "textlab/evidence/PlaceRepair.kt").read_text(encoding="utf-8")
        self.assertIn("BoundedSpelling.distanceNormalized(query,form.key,queryWords,form.words)", place_repair)

        # Issue 2: year-attached classification/appellation lines and embedded harvest vintages.
        extractor = (WINE / "textlab/evidence/EvidenceExtractor.kt").read_text(encoding="utf-8")
        solver = (WINE / "textlab/evidence/EvidenceSolver.kt").read_text(encoding="utf-8")
        self.assertIn("detachEdgeVintage(text,year)", extractor)
        self.assertIn('offer(mapOf(FieldId.VINTAGE to embeddedYear),52.0,"embedded observed vintage")', extractor)
        self.assertIn("!compatibleEmbeddedVintage(old,next)", solver)

        # Issue 3: bare context_only legal classifications (`DOCG`, `AVA`, `AOC`) blocked from WINE_NAME.
        designations = (WINE / "extract/WineDesignations.kt").read_text(encoding="utf-8")
        self.assertIn("fun isBareClassification(text: String): Boolean", designations)
        self.assertIn('if(WineDesignations.isBareClassification(text)) { trace.add("bare_classification"', extractor)

        # Issue 4 & 5: catalog policies, AVA containment, French headquarters filter, region-wide indications.
        with (ROOT / "data/wine-knowledge/origins.csv").open(encoding="utf-8", newline="") as f:
            origins = {(r["country_code"], r["name"]): r for r in _csv.DictReader(f)}
        self.assertEqual(origins[("IT", "Sicilia")]["match_policy"], "context_required")
        self.assertEqual(origins[("FR", "Bourgogne Hautes-Cotes de Beaune")]["path"], "Burgundy/Cote de Beaune")
        self.assertIn("Hautes-Cotes de Beaune", origins[("FR", "Bourgogne Hautes-Cotes de Beaune")]["aliases"].split(";"))
        self.assertEqual(origins[("FR", "Cotes de Gascogne")]["regions"], "South West France")
        self.assertEqual(origins[("FR", "Pays d'Herault")]["regions"], "Languedoc")
        self.assertEqual(origins[("FR", "Cotes du Tarn")]["regions"], "South West France")

        generated = (WINE / "kb/GeneratedWineKnowledge.kt").read_text(encoding="utf-8")
        self.assertIn('"St. Helena" to "Napa Valley"', generated)
        self.assertIn('"Stags Leap District" to "Napa Valley"', generated)
        supplemented = (WINE / "kb/SupplementedWineKnowledgeBase.kt").read_text(encoding="utf-8")
        self.assertIn("GeneratedWineKnowledge.avaContainment", supplemented)

        label_ctx = (WINE / "textlab/evidence/LabelContext.kt").read_text(encoding="utf-8")
        self.assertIn("fun isFrenchHeadquartersAddress(norm: String): Boolean", label_ctx)
        catalog = (WINE / "kb/ReferenceWineCatalog.kt").read_text(encoding="utf-8")
        self.assertIn("fun isRegionWideIndication(place: Place): Boolean", catalog)
        self.assertIn("val regionWide=ReferenceWineCatalog.isRegionWideIndication(place)", extractor)

        # Kotlin lexer guard: nested generic `>>=` without whitespace is lexed as RSHIFT+EQ or GTEQ.
        bad_generic_eq = re.compile(r"<[^>\n]+<[^>\n]+>>=")
        for kt in list((ROOT / "app").rglob("*.kt")) + list((ROOT / "tools").rglob("*.kt")):
            src = kt.read_text(encoding="utf-8")
            self.assertIsNone(bad_generic_eq.search(src), f"Unspaced nested generic `>>=` in {kt}")


if __name__ == "__main__":
    unittest.main()
