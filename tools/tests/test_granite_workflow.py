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

    def test_decoupled_from_production_repositories_and_reuses_existing_ocr_files(self):
        kt_files = sorted(PKG.glob("*.kt"))
        self.assertGreaterEqual(len(kt_files), 8)
        forbidden_prefixes = (
            "com.n8rtz.winetracker.data.",
            "com.n8rtz.winetracker.scanner.",
            "com.n8rtz.winetracker.network.",
            "com.n8rtz.winetracker.image.",
            "com.n8rtz.winetracker.viewmodels.",
            "com.yourapp.wine.gliner.",
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
        # Verify DualOcrEngine reuses the existing MlKitOcrEngine and PaddleTextDetector/Recognizer
        ocr = self.read(PKG / "DualOcrEngine.kt")
        self.assertIn("MlKitOcrEngine", ocr)
        self.assertIn("PaddleTextDetector.detect", ocr)
        self.assertIn("PaddleTextRecognizer.recognize", ocr)
        self.assertIn("PaddleCandidates", ocr)

    def test_shares_same_databases_as_gliner_via_candidate_retrieval_rag(self):
        rag = self.read(PKG / "GraniteDatabaseRagRetriever.kt")
        extractor = self.read(PKG / "GraniteLabelExtractor.kt")
        storage = self.read(PKG / "GraniteModelStorage.kt")
        screen = self.read(PKG / "GraniteWorkflowScreen.kt")
        self.assertIn("ProducerGazetteer.openExpanded", rag)
        self.assertIn("ProducerResolver.producerDistance", rag)
        self.assertIn("LinkedWineResolver.eligible", rag)
        self.assertIn("gazetteer.lookup", rag)
        self.assertIn("gazetteer.lookupWines", rag)
        self.assertIn("TextLabWineExtraction.WarmSession", rag)
        self.assertIn("TextLabSpatialPreview.append", rag)
        self.assertIn("TextLabFormPreview.append", rag)
        self.assertIn("ReferenceWineCatalog.origins", rag)
        self.assertIn("BundledOrigins.terms", rag)
        self.assertIn("BundledGrapes.terms", rag)
        self.assertIn("BundledWineStyles.terms", rag)
        self.assertIn("Retrieved Offline Wine Database Candidates (RAG Context", extractor)
        self.assertIn("ragContext.toPromptRagSection()", extractor)
        self.assertIn("Database Candidate Grounding (RAG)", extractor)
        self.assertIn('PRODUCER_EXPANDED_DB_FILENAME = "producers-expanded.sqlite"', storage)
        self.assertIn('LINKED_WINES_DB_FILENAME = "wines.sqlite"', storage)
        self.assertIn("DB RAG ", screen)
        self.assertIn("Database RAG Candidates (", screen)

    def test_shares_gliner_wine_app_labels_dataset(self):
        corpus = self.read(PKG / "GraniteLabCorpus.kt")
        screen = self.read(PKG / "GraniteWorkflowScreen.kt")
        self.assertIn('const val ASSET_ROOT = "wine-app-labels-v1"', corpus)
        self.assertIn("inputs.json", corpus)
        self.assertIn("scoring.json", corpus)
        self.assertIn("evaluation-set.json", corpus)
        self.assertIn("PhoneCorpusMath", corpus)
        self.assertIn("Use GLiNER dataset", screen)
        self.assertIn("Run GLiNER set", screen)
        self.assertIn("Incomplete labels", screen)
        self.assertIn("All labels", screen)

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
        self.assertIn('const val SHARED_PADDLE_FOLDER = "text-lab-paddle-v1"', storage)
        self.assertIn("const val DEFAULT_THINK_MODE_ENABLED = false", extractor)
        self.assertIn("WINE_LABEL_JSON_GBNF", extractor)
        for key in (
            "ocr_discrepancies_resolved",
            "confidence_notes",
            "country",
            "region",
            "subregion",
            "appellation_or_region",
            "varietals",
            "classification",
            "vintage",
            "is_non_vintage",
            "abv_percentage",
            "bottle_volume_ml",
            "producer_or_winery",
            "wine_name_or_cuvee",
        ):
            self.assertIn(f'"{key}"', extractor)
        # Verify Reason-Then-Context-Then-Entity causal key ordering in GBNF
        gbnf_start = extractor.index("val WINE_LABEL_JSON_GBNF")
        gbnf_block = extractor[gbnf_start : extractor.index("val WINE_LABEL_THINK_PLUS_JSON_GBNF")]
        self.assertLess(
            gbnf_block.index('\\"ocr_discrepancies_resolved\\"'),
            gbnf_block.index('\\"country\\"'),
        )
        self.assertLess(
            gbnf_block.index('\\"subregion\\"'),
            gbnf_block.index('\\"producer_or_winery\\"'),
        )
        self.assertIn("Spatially Aligned Cross-Engine Reading Lines", extractor)
        self.assertIn("buildAlignedReadingLinesSection", extractor)

    def test_four_section_results_inspector_and_file_pickers(self):
        screen = self.read(PKG / "GraniteWorkflowScreen.kt")
        activity = self.read(PKG / "GraniteWorkflowActivity.kt")
        self.assertIn("ActivityResultContracts.OpenDocument()", activity)
        self.assertIn("Import Model Files to Run", screen)
        self.assertIn("1. Preprocessed Image + Dual-OCR Bounding Box Overlay", screen)
        self.assertIn("2. Side-by-Side Raw OCR Comparison", screen)
        self.assertIn("3. Granite 4.2 Reconciled Output", screen)
        self.assertIn("4. Copy & Share Actions", screen)
        self.assertIn("Preprocess ms", screen)
        self.assertIn("ML Kit ms", screen)
        self.assertIn("PP-OCRv6 ms", screen)
        self.assertIn("Granite 4.2 ms", screen)
        self.assertIn("Total ms", screen)
        self.assertIn("Peak Heap/Native RAM MB", screen)

    def test_prebuilt_granite_llama_jni_so_present_and_valid(self):
        so_path = ROOT / "app/src/main/jniLibs/arm64-v8a/libgranite_llama_jni.so"
        cpp_path = ROOT / "app/src/main/cpp/graniteworkflow/granite_llama_jni.cpp"
        rag_path = PKG / "GraniteDatabaseRagRetriever.kt"
        self.assertTrue(so_path.is_file(), f"Missing {so_path}")
        data = so_path.read_bytes()
        self.assertGreater(len(data), 1_000_000)
        self.assertEqual(data[:4], b"\x7fELF")
        self.assertIn(b"Java_com_n8rtz_winetracker_debug_graniteworkflow_GraniteLlamaBridge_nativeInitModel", data)
        self.assertIn(b"Java_com_n8rtz_winetracker_debug_graniteworkflow_GraniteLlamaBridge_nativeComplete", data)
        self.assertIn(b"Java_com_n8rtz_winetracker_debug_graniteworkflow_GraniteLlamaBridge_nativeFreeModel", data)
        self.assertIn(b"Java_com_n8rtz_winetracker_debug_graniteworkflow_GraniteLlamaBridge_nativeGetLastError", data)
        cpp = self.read(cpp_path)
        self.assertIn("mparams.use_extra_bufts = false;", cpp)
        self.assertIn("mparams.load_mode = LLAMA_LOAD_MODE_MMAP;", cpp)
        self.assertIn("cached_prefix_tokens", cpp)
        self.assertIn("cached_prefix_state", cpp)
        self.assertIn("llama_memory_seq_rm", cpp)
        self.assertIn("llama_state_seq_set_data", cpp)
        rag = self.read(rag_path)
        self.assertIn("minSubspanEditDistance", rag)
        self.assertIn("reverseLookupWinesToProducers", rag)
        self.assertIn("CORROBORATED_BY_LINKED_WINE", rag)
        self.assertNotIn("5. Spatial + KnowledgeBase Contender Hints", rag)
        self.assertNotIn("leadingProducerArticles", rag)
        extractor = self.read(PKG / "GraniteLabelExtractor.kt")
        self.assertIn("Dynamic Reconstruction of Incomplete or Misread OCR", extractor)
        self.assertIn("JNI library load failed:", extractor)

        proguard = self.read(ROOT / "app/proguard-rules.pro")
        self.assertIn("-keep class com.n8rtz.winetracker.debug.graniteworkflow.** { *; }", proguard)

        import shutil
        import subprocess
        if shutil.which("readelf"):
            phdrs = subprocess.check_output(["readelf", "-l", "-W", str(so_path)], text=True)
            self.assertNotIn("TLS", phdrs, "Orphaned PT_TLS breaks Android Bionic linker64 dlopen")
            relocs = subprocess.check_output(["readelf", "-r", "-W", str(so_path)], text=True)
            self.assertNotIn("TLS", relocs, "TLSDESC relocations break Android Bionic without PT_TLS")
            dynsyms = subprocess.check_output(["readelf", "--dyn-syms", "-W", str(so_path)], text=True)
            und_notype = [
                line for line in dynsyms.splitlines()
                if "UND" in line and "NOTYPE" in line and not line.strip().startswith("0:")
            ]
            self.assertEqual(und_notype, [], f"Unexpected NOTYPE UND symbols: {und_notype}")


if __name__ == "__main__":
    unittest.main()
