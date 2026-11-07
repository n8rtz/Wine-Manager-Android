"""Structural and decoupling tests for Dual OCR + Granite 4.2 4B Workflow."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
PKG = ROOT / "app/src/main/java/com/n8rtz/winetracker/debug/graniteworkflow"
PROFILE = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


class GraniteWorkflowStructureTests(unittest.TestCase):
    def read(self, path: Path) -> str:
        return path.read_text(encoding="utf-8")

    def test_settings_menu_entry_and_manifest_registration(self):
        profile = self.read(PROFILE)
        self.assertIn("Dual OCR + Granite 4.2 4B Workflow", profile)
        self.assertIn("GraniteWorkflowActivity::class.java", profile)
        manifest = self.read(MANIFEST)
        self.assertIn(
            'android:name="com.n8rtz.winetracker.debug.graniteworkflow.GraniteWorkflowActivity"',
            manifest,
        )
        self.assertIn('android:exported="false"', manifest)

    def test_complete_decoupling_from_production_domain_and_gliner(self):
        kt_files = sorted(PKG.glob("*.kt"))
        self.assertGreaterEqual(len(kt_files), 6)
        forbidden_prefixes = (
            "com.yourapp.wine.",
            "com.n8rtz.winetracker.data.",
            "com.n8rtz.winetracker.scanner.",
            "com.n8rtz.winetracker.network.",
            "com.n8rtz.winetracker.image.",
            "com.n8rtz.winetracker.viewmodels.",
        )
        for path in kt_files:
            source = self.read(path)
            imports = re.findall(r"^\s*import\s+([\w.*]+)", source, re.M)
            for imp in imports:
                for forbidden in forbidden_prefixes:
                    self.assertFalse(
                        imp.startswith(forbidden),
                        f"{path.name} imports coupled class {imp}",
                    )

    def test_preprocessor_constraints_and_clahe_toggle(self):
        prep = self.read(PKG / "GraniteWorkflowImagePreprocessor.kt")
        self.assertIn("MAX_LONGEST_EDGE_PX = 1280", prep)
        self.assertIn("MIN_LONGEST_EDGE_PX = 768", prep)
        self.assertIn("applyMildClaheAndGlareReduction", prep)

    def test_parallel_dual_ocr_engine_and_ablation_modes(self):
        ocr = self.read(PKG / "DualOcrEngine.kt")
        models = self.read(PKG / "GraniteWorkflowModels.kt")
        self.assertIn("async(Dispatchers.Default)", ocr)
        self.assertIn("awaitAll()", ocr)
        self.assertIn("TextRecognition.getClient", ocr)
        self.assertIn("OrtEnvironment.getEnvironment()", ocr)
        self.assertIn("Dual (ML Kit + PP-OCRv6)", models)
        self.assertIn("ML Kit Only", models)
        self.assertIn("PP-OCRv6 Only", models)
        self.assertIn("data class OcrBlock(", models)
        self.assertIn("val text: String", models)
        self.assertIn("val confidence: Float", models)
        self.assertIn("val normBbox: RectF", models)

    def test_granite_extractor_schema_gbnf_and_think_default_off(self):
        extractor = self.read(PKG / "GraniteLabelExtractor.kt")
        storage = self.read(PKG / "GraniteModelStorage.kt")
        self.assertIn('const val GRANITE_GGUF_FILENAME = "granite-4.2-3b-Q4_K_M.gguf"', storage)
        self.assertIn('const val GRANITE_SUBDIR = "models/granite"', storage)
        self.assertIn('const val PPOCR_SUBDIR = "models/ppocrv6"', storage)
        self.assertIn("const val DEFAULT_THINK_MODE_ENABLED = false", extractor)
        self.assertIn("WINE_LABEL_JSON_GBNF", extractor)
        for key in (
            "producer_or_winery",
            "wine_name_or_cuvee",
            "vintage",
            "varietals",
            "appellation_or_region",
            "country",
            "classification",
            "abv_percentage",
            "bottle_volume_ml",
            "is_non_vintage",
            "ocr_discrepancies_resolved",
            "confidence_notes",
        ):
            self.assertIn(f'"{key}"', extractor)

    def test_four_section_results_inspector_and_file_pickers(self):
        screen = self.read(PKG / "GraniteWorkflowScreen.kt")
        activity = self.read(PKG / "GraniteWorkflowActivity.kt")
        self.assertIn("ActivityResultContracts.OpenDocument()", activity)
        self.assertIn("Import Model Files to Run", screen)
        self.assertIn("1. Image + Bounding Box Overlay", screen)
        self.assertIn("2. Timing & Memory Strip", screen)
        self.assertIn("3. Final Structured JSON Card", screen)
        self.assertIn("4. Expandable Raw OCR Accordion", screen)
        self.assertIn("Preprocess ms", screen)
        self.assertIn("ML Kit ms", screen)
        self.assertIn("PP-OCRv6 ms", screen)
        self.assertIn("Granite 4.2 ms", screen)
        self.assertIn("Total ms", screen)
        self.assertIn("Peak Heap/Native RAM MB", screen)


if __name__ == "__main__":
    unittest.main()
