"""Wiring and generality checks for Lab v5 bottle-column/sticker filter and conditional zoom-crop."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
FILTER = ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabBottleFilter.kt"
SPATIAL = ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt"
RUNNER = ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabRunner.kt"
CORPUS_RUNNER = ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt"
OCR_CACHE = ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsOcrCache.kt"
SPATIAL_SCRIPT = ROOT / "tools/run-spatial-preview-checks.sh"


class BottleFilterAndZoomCropTests(unittest.TestCase):
    def test_spatial_filter_is_lab_only_and_works_with_cached_ocr(self):
        spatial = SPATIAL.read_text()
        # Filter runs exclusively when policy.minimumIndependentEngines < 2 (Lab v5), never Prod v4.0.
        self.assertIn(
            "val filterOutcome=if(policy.minimumIndependentEngines<2) TextLabBottleFilter.filterTokens(rawOriginal) else null",
            spatial,
        )
        # Recomputes form_summary on filtered readings when noise is dropped so EvidenceAnchorHybrid
        # cannot re-inject filtered producer tokens.
        self.assertIn("if(filterOutcome!=null && filterOutcome.dropped.isNotEmpty() && engine!=null)", spatial)
        self.assertIn("TextLabFormPreview.append(work,com.yourapp.wine.fill.FillEngine(engine.baseKb),year,recordsOverride=readings)", spatial)
        self.assertIn('"spatial_filter_dropped"', spatial)

    def test_conditional_zoom_crop_is_lab_only_padded_and_fail_safe(self):
        corpus_runner = CORPUS_RUNNER.read_text()
        self.assertEqual(corpus_runner.count("TextLabRunner.recognize("), 1)
        self.assertIn("journal.autoZoomTinyLabel=(labModes==listOf(Mode.LAB))", corpus_runner)

        runner = RUNNER.read_text()
        self.assertIn("if(journal.autoZoomTinyLabel && !enlarge && selection==TextCrop(0f,0f,1f,1f))", runner)
        self.assertIn("val zoomCrop=TextLabBottleFilter.proposeZoomCrop(journal)", runner)
        self.assertIn("recognizeCrop(context,photo,zoomCrop,true,zoomOutput,zoomJournal,engines,checkpoint)", runner)
        self.assertIn("if(TextLabBottleFilter.shouldAcceptZoomPass(journal,zoomJournal))", runner)
        self.assertIn("TextLabBottleFilter.promoteZoomPass(journal,zoomJournal,zoomCrop)", runner)

        cache = OCR_CACHE.read_text()
        self.assertIn('"auto_zoom_crop"', cache)
        self.assertIn('"auto_zoom_crop_skipped"', cache)

    def test_no_benchmark_memorized_strings_and_ci_script_includes_new_module(self):
        source = FILTER.read_text()
        lowered = source.lower()
        for forbidden in (
            "living the gourmet",
            "iterna",
            "les ormes",
            "cambras",
            "abelyce",
            "peyfaure",
            "kirkland",
            "lusac",
            "lussat",
            "dak",
        ):
            self.assertNotIn(forbidden, lowered, f"Corpus-memorized token {forbidden!r} must not appear in filter source")

        # Regex inside TextLabBottleFilter must compile cleanly under Python/PCRE/Java rules.
        self.assertIn("MAX_TINY_FOOTPRINT_AREA = 0.10f", source)
        self.assertIn("val padTop = maxOf(0.18f, 0.80f * h)", source)
        self.assertIn("val padBottom = maxOf(0.12f, 0.45f * h)", source)
        self.assertIn("val padX = maxOf(0.08f, 0.35f * w)", source)

        # Ensure standalone kotlinc CI script compiles TextLabBottleFilter + TextCropGeometry + Matrix3.
        script = SPATIAL_SCRIPT.read_text()
        self.assertIn("TextLabBottleFilter", script)
        self.assertIn("TextCropGeometry", script)
        self.assertIn("$root/geom/Matrix3.kt", script)

        # Guard against Kotlin `>=empty` lexer trap across edited files.
        for path in (FILTER, SPATIAL, RUNNER, CORPUS_RUNNER, OCR_CACHE):
            self.assertIsNone(re.search(r">=[A-Za-z]", path.read_text()), f"Lexer `>=` hazard in {path}")


if __name__ == "__main__":
    unittest.main()
