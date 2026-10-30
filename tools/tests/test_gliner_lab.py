"""Structural guards for the GLiNER lane lab (PP-OCRv6 -> GLiNER-Small INT4 -> Lab v7 post-processing).

These checks do not run the model. They pin the architecture the screen claims: stage order,
the INT4 bundle contract, the documented ONNX input names, dataset reuse, offline-only behaviour
and the Settings entry point. A silent refactor that turns the lab back into "another OCR screen"
fails here.
"""
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
CORE = ROOT / "gliner-workflow/src/main/java/com/yourapp/wine/gliner"
ADAPTER = ROOT / "gliner-workflow/src/appIntegration/java"
class GlinerSourceDirectory:
    def __truediv__(self, name):
        core = CORE / name
        return core if core.exists() else ADAPTER / "com/yourapp/wine/gliner" / name
    def glob(self, pattern):
        return list(CORE.glob(pattern)) + list((ADAPTER / "com/yourapp/wine/gliner").glob(pattern))
GLINER = GlinerSourceDirectory()
ASSETS = APP / "assets/gliner-lab-v1"


class GlinerLabStructureTests(unittest.TestCase):
    def read(self, path):
        return pathlib.Path(path).read_text(encoding="utf-8")

    def test_bundle_manifest_pins_the_int4_contract(self):
        manifest = json.loads(self.read(ASSETS / "model-manifest.json"))
        names = [entry["name"] for entry in manifest["files"]]
        self.assertEqual(names, ["gliner_small_int4.onnx", "tokenizer.json", "gliner_config.json"])
        roles = {entry["role"] for entry in manifest["files"]}
        self.assertEqual(roles, {"model", "tokenizer", "config"})
        for entry in manifest["files"]:
            self.assertIsNone(entry["bytes"], "shipping repository must not claim a size it has not pinned")
            self.assertIsNone(entry["sha256"], "shipping repository must not claim a hash it has not pinned")
        self.assertIn("int4", manifest["model"]["quantization"])
        self.assertIn("MatMulNBits", manifest["model"]["quantization"])
        self.assertEqual(manifest["io_contract"]["inputs"],
                         ["input_ids", "attention_mask", "words_mask", "text_lengths", "span_idx", "span_mask"])
        self.assertEqual(manifest["io_contract"]["outputs"], ["logits"])
        self.assertIn("Apache License", self.read(ASSETS / "LICENSE-GLiNER.txt"))

    def test_lane_prompts_match_the_screen_constants(self):
        manifest = json.loads(self.read(ASSETS / "model-manifest.json"))
        prompts = [lane["prompt"] for lane in manifest["lanes"]]
        source = self.read(GLINER / "GlinerLanes.kt").replace(" ", "")
        for prompt in prompts:
            self.assertIn(f'"{prompt.replace(" ", "")}"', source,
                          f"manifest prompt {prompt!r} is not the one the app sends")
        self.assertEqual([lane["key"] for lane in manifest["lanes"]], ["producer", "wine_name", "appellation"])

    def test_stage_order_is_ocr_then_gliner_then_existing_post_processing(self):
        runner = self.read(GLINER / "GlinerLabRunner.kt")
        self.assertLess(runner.index("GlinerOcr.recognize"), runner.index("model.label"))
        self.assertLess(runner.index("model.label"), runner.index("postProcess("))
        # Stage 3 must be the existing contender, not a re-implementation.
        self.assertIn("TextLabSpatialPreview.append", runner)
        self.assertIn("TextLabFormPreview.append", runner)
        self.assertIn("ProducerGazetteer.openExpanded", runner)
        self.assertIn("ProducerResolver.Policy.singleReading", runner)
        self.assertIn("LabPostOcrModules.none.withIntegrated()", runner)
        self.assertIn('candidate="gliner_lane"', runner)
        self.assertIn("recordsOverride=records", runner)

    def test_spatial_pipeline_accepts_grouped_lane_records(self):
        spatial = self.read(APP / "java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt")
        self.assertIn("recordsOverride:List<com.yourapp.wine.ocr.TokenRecord>?=null", spatial)
        self.assertIn("val rawOriginal=recordsOverride ?: TextLabFormPreview.records(journal)", spatial)

    def test_only_three_lanes_are_asked_from_gliner(self):
        lanes = self.read(GLINER / "GlinerLanes.kt")
        self.assertEqual(len(re.findall(r'^\s{4}[A-Z_]+\(', lanes, flags=re.MULTILINE)), 3)
        for forbidden in ("country", "subregion", "vintage"):
            for match in re.findall(r'GlinerLane\.[A-Z_]+', lanes):
                self.assertNotIn(forbidden, match.lower())
        runner = self.read(GLINER / "GlinerLabRunner.kt")
        self.assertIn("PhoneCorpusMath.fields", runner)

    def test_dataset_is_the_lab_v7_sample_set(self):
        corpus = self.read(GLINER / "GlinerLabCorpus.kt")
        for filename in ("inputs.json", "scoring.json", "evaluation-set.json"):
            self.assertIn(filename, corpus)
        self.assertIn('const val ASSET_ROOT="wine-app-labels-v1"', corpus)
        self.assertEqual((APP / "assets/wine-app-labels-v1/images").is_dir(), True)

    def test_screen_says_both_engines_read_the_label(self):
        # The page carries no architecture prose any more; the entry point still says both engines.
        profile = self.read(APP / "java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt")
        self.assertIn("GLiNER lane lab — ML Kit + PP-OCRv6", profile)

    def test_lab_is_offline_and_reads_no_http(self):
        for path in GLINER.glob("*.kt"):
            source = self.read(path)
            for symbol in ("HttpURLConnection", "OkHttp", "java.net.URL", "URLConnection", "https://api"):
                self.assertNotIn(symbol, source, f"{path.name} must not reach the network at run time")
        manifest = json.loads(self.read(ASSETS / "model-manifest.json"))
        self.assertIn("device_time", manifest["provisioning"])

    def test_int4_graph_is_verified_before_use(self):
        bundle = self.read(GLINER / "GlinerBundle.kt")
        self.assertIn("MatMulNBits", bundle)
        self.assertIn("fun isInt4", bundle)
        screen = self.read(ADAPTER / "com/n8rtz/winetracker/ui/screens/GlinerLabScreen.kt")
        self.assertIn('INT4 "+(if(state.bundle.int4)', screen)
        model = self.read(GLINER / "GlinerOnnxModel.kt")
        self.assertIn("int4=status.int4", model)

    def test_onnx_inputs_are_the_documented_names(self):
        model = self.read(GLINER / "GlinerOnnxModel.kt")
        for name in ("input_ids", "attention_mask", "words_mask", "text_lengths", "span_idx", "span_mask"):
            self.assertIn(f'"{name}"', model)
        self.assertIn("Unsupported GLiNER graph input", model)
        self.assertIn("Unrecognized GLiNER output shape", model)
        self.assertIn("text_lengths", model)

    def test_span_enumeration_is_the_rectangular_clipped_grid(self):
        # gliner 0.2.29 reshapes the grid to (words, maxWidth, ...): every start with every width,
        # the end clipped at the last word, so the list is exactly words*maxWidth long.
        model = self.read(GLINER / "GlinerOnnxModel.kt")
        block = model.split("private fun spanIndex", 1)[1].split("private data class Candidate", 1)[0]
        self.assertIn("for(start in 0 until words)", block)
        self.assertIn("for(offset in 0 until maxWidth)", block)
        self.assertIn("spans += start+offset", block)
        self.assertNotIn("minOf(start+width-1,words-1)", block)

    def test_prompt_markers_use_the_ent_and_sep_ids(self):
        # The exported graph counts the lane classes from the <<ENT>> marker ids; tokenizing the
        # marker as text makes it answer with [1, words, width, 0] (seen on device, 2026-10-30).
        model = self.read(GLINER / "GlinerOnnxModel.kt")
        self.assertIn("private fun promptTokens", model)
        self.assertIn("tokenizer.tokenId(config.entToken)", model)
        self.assertIn("zero lane classes", model)
        self.assertNotIn("encodePromptWord", model.split("private fun promptTokens", 1)[1].split("fun promptPreview", 1)[0])
        tokenizer = self.read(GLINER / "GlinerTokenizer.kt")
        self.assertIn("fun encodePromptWord", tokenizer)

    def test_real_exported_layout_is_decoded(self):
        # The exported graph answers with (1, words, maxWidth, lanes); the decoder must accept it
        # and skip the clipped spans instead of dropping them from the grid.
        model = self.read(GLINER / "GlinerOnnxModel.kt")
        self.assertIn("span logits [1, words, max_width, classes]", model)
        self.assertIn("if(end>=words) continue", model)

    def test_flat_lane_assignment_prevents_double_labelling(self):
        model = self.read(GLINER / "GlinerOnnxModel.kt")
        self.assertIn("if((candidate.start..candidate.end).any { used[it] })", model)
        self.assertIn("used[index]=true", model)

    def test_settings_entry_and_manifest_registration(self):
        profile = self.read(APP / "java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt")
        self.assertIn("GLiNER lane lab", profile)
        self.assertIn("com.yourapp.wine.gliner.GlinerLabActivity", profile)
        manifest = self.read(APP / "AndroidManifest.xml")
        self.assertIn('android:name="com.yourapp.wine.gliner.GlinerLabActivity"', manifest)
        self.assertIn('android:exported="false"', manifest)

    def test_build_time_provisioning_is_opt_in_and_pin_gated(self):
        gradle = self.read(ROOT / "app/build.gradle.kts")
        self.assertIn('gradleProperty("glinerLabAssets")', gradle)
        block = gradle.split("abstract class PrepareGlinerLabBundle", 1)[1].split("val prepareGlinerLab", 1)[0]
        self.assertIn("GLiNER bundle is not pinned", block)
        self.assertIn("checksum mismatch", block)
        self.assertIn("No placeholder model is allowed", block)

    def test_fetch_tool_quantizes_int4_and_pins_hashes(self):
        tool = self.read(ROOT / "tools/fetch-gliner-small-int4.py")
        self.assertIn("MatMul4BitsQuantizer", tool)
        self.assertIn("MatMulNBits", tool)
        self.assertIn("--pin", tool)
        self.assertIn("gliner_small_int4.onnx", tool)
        self.assertIn("refusing to pin a non-INT4 bundle", tool)

    def test_stage_one_runs_mlkit_and_paddle_over_the_same_label(self):
        ocr = self.read(GLINER / "GlinerOcr.kt")
        self.assertIn("MlKitOcrEngine(WineScannerServices.nativeGate)", ocr,
                      "stage 1 must use the same bundled ML Kit client and gate as the production ensemble")
        self.assertIn("const val MLKIT_ID=GlinerEngines.MLKIT", ocr)
        self.assertIn("const val PADDLE_ID=GlinerEngines.PADDLE", ocr)
        self.assertIn("PaddleTextDetector.detect", ocr)
        self.assertIn("PaddleTextRecognizer.recognize", ocr)
        # Both reads are started together and awaited as a pair, on one decoded bitmap.
        self.assertIn("coroutineScope {", ocr)
        self.assertIn("async(Dispatchers.Default) { readMlKit(", ocr)
        self.assertIn("async(Dispatchers.Default) { readPaddle(", ocr)
        self.assertIn("mlkit.await() to paddle.await()", ocr)
        self.assertIn("GlinerFusion.fuse(mlKitReadings+paddleReadings)", ocr)
        self.assertIn("CaptureDecoder().decode", ocr)

    def test_fusion_keeps_each_engine_score_and_does_not_pre_filter(self):
        fusion = self.read(GLINER / "GlinerFusion.kt")
        self.assertIn("data class Reading", fusion)
        self.assertIn("val engines=members.groupBy { it.engineId }", fusion)
        self.assertIn("mapValues { (_,reads) -> reads.maxOf { it.score }", fusion)
        # No score floor here: Garbled fragments are the evidence GLiNER is asked to resolve.
        for forbidden in ("score <=", "score>=", "threshold"):
            self.assertNotIn(forbidden, fusion)
        document = self.read(GLINER / "GlinerDocument.kt")
        self.assertIn("val engines:Map<String,Float> = emptyMap()", document)

    def test_engine_ids_match_the_production_engines(self):
        ocr = self.read(GLINER / "GlinerOcr.kt")
        self.assertIn("const val MLKIT_ID=GlinerEngines.MLKIT", ocr)
        self.assertIn("const val PADDLE_ID=GlinerEngines.PADDLE", ocr)
        mlkit = self.read(APP / "java/com/yourapp/wine/ocr/MlKitOcrEngine.kt")
        self.assertIn('override val id = "mlkit"', mlkit)
        paddle = self.read(APP / "java/com/yourapp/wine/textlab/PaddleMediumOcrEngine.kt")
        self.assertIn('override val id="paddle-v6-medium"', paddle)
        # Existing rules branch on the "paddle" prefix and on engine equality; these ids must not drift.
        self.assertIn('startsWith("paddle")', self.read(APP / "java/com/yourapp/wine/ocr/Tokens.kt"))

    def test_original_engine_readings_are_preserved_without_fabricated_votes(self):
        records = self.read(GLINER / "GlinerLaneRecords.kt")
        runner = self.read(GLINER / "GlinerLabRunner.kt")
        block = records.split("fun originalRecords", 1)[1].split("fun records(", 1)[0]
        for item in ("reading.text", "reading.engineId", "reading.variantId", "reading.scoreIsEstimated"):
            self.assertIn(item, block)
        self.assertIn("GlinerLaneRecords.originalRecords(ocr.readings)", runner)
        self.assertNotIn("GlinerLaneRecords.records(labelled)", runner)
        self.assertIn("GlinerFieldDecisionPolicy.apply", runner)

    def test_workflow_is_a_separate_gradle_module(self):
        settings = self.read(ROOT / "settings.gradle.kts")
        gradle = self.read(ROOT / "app/build.gradle.kts")
        self.assertIn('":gliner-workflow"', settings)
        self.assertIn('implementation(project(":gliner-workflow"))', gradle)
        self.assertIn('gliner-workflow/src/appIntegration/java', gradle)
        for name in ("GlinerOnnxModel.kt", "GlinerTokenizer.kt", "GlinerFieldDecision.kt"):
            self.assertTrue((CORE / name).exists())
        for source in CORE.glob("*.kt"):
            self.assertNotIn("import com.yourapp.wine.textlab", self.read(source))

    def test_reference_targets_never_reach_the_runner(self):
        runner = self.read(GLINER / "GlinerLabRunner.kt")
        post = runner.split("private fun postProcess", 1)[1].split("fun failedFor", 1)[0]
        self.assertNotIn("sample.target", post)
        self.assertNotIn("expected=", post)
        self.assertNotIn("scoring", post)
        self.assertLess(runner.index("postProcess("), runner.index("PhoneCorpusMath.outcome"))


if __name__ == "__main__":
    unittest.main()


try:  # optional desktop dependencies; the phone build never needs them
    import numpy as np
    import onnx
    from onnx import TensorProto, helper, numpy_helper
    from tokenizers import Tokenizer, models, normalizers, pre_tokenizers, processors

    HAVE_ONNX = True
except Exception:  # noqa: BLE001 - any import failure means "skip the model checks"
    HAVE_ONNX = False


class GlinerLabScreenAndRunTests(unittest.TestCase):
    """The lab screen is a results page, not a wall of prose, and a run survives leaving it."""

    def read(self, path):
        return pathlib.Path(path).read_text(encoding="utf-8")

    SCREEN = ADAPTER / "com/n8rtz/winetracker/ui/screens/GlinerLabScreen.kt"
    VM = ADAPTER / "com/n8rtz/winetracker/viewmodels/GlinerLabViewModel.kt"
    SERVICE = GLINER / "GlinerLabRunService.kt"

    def test_every_result_is_selectable_and_copyable(self):
        screen = self.read(self.SCREEN)
        self.assertIn("SelectionContainer", screen)
        self.assertIn("Copy all results", screen)
        self.assertIn("onReport()", screen)
        self.assertIn("ClipboardManager", screen)

    def test_json_export_matches_the_lab_v7_export_buttons(self):
        screen = self.read(self.SCREEN)
        self.assertIn("Save JSON", screen)
        self.assertIn("Save CSV", screen)
        view_model = self.read(self.VM)
        self.assertIn("fun saveJson(", view_model)
        self.assertIn("JSONObject", view_model)
        self.assertIn("CreateDocument(\"application/json\")",
                      self.read(ADAPTER / "com/yourapp/wine/gliner/GlinerLabActivity.kt"))

    def test_decoration_is_trimmed_from_the_screen(self):
        screen = self.read(self.SCREEN)
        for removed in ("ArchitectureCard", "Existing post-processing → evidence extractor",
                        "private fun DatasetRow"):
            self.assertNotIn(removed, screen)

    def test_layout_reuses_lab_v7_failed_cards_and_scoring(self):
        screen = self.read(self.SCREEN)
        for item in ("Scored results", "Incomplete labels", "FilterChip", "FailedParametersTable(failed)",
                     "LabelThumbnail(", "ZoomableLabelDialog(", "Raw OCR", "PhoneCorpusMath.correct"):
            self.assertIn(item, screen)
        self.assertIn("mutableStateOf(false)", screen)
        self.assertIn('target(it.field).status!="unresolved"', screen)
        view_model = self.read(self.VM)
        self.assertIn('aggregateGlinerRows(samples,stopped=false)', view_model)
        self.assertIn('row.sample.target(field).status!="unresolved"', view_model)

    def test_score_tables_are_always_visible(self):
        screen = self.read(self.SCREEN)
        self.assertIn("ScoredResults(state.aggregate ?: aggregateGlinerRows(state.samples,state.stopped),labState,", screen)
        self.assertNotIn("state.aggregate?.let", screen)

    def test_lab_and_prod_baselines_come_from_saved_lab_scoreboard(self):
        screen = self.read(self.SCREEN)
        baseline = self.read(ADAPTER / "com/yourapp/wine/phonecorpus/GlinerLabBaselines.kt")
        activity = self.read(ADAPTER / "com/yourapp/wine/gliner/GlinerLabActivity.kt")
        self.assertIn("glinerLabBaselines(labState,completedImages)", screen)
        self.assertIn("WineAppLabelsViewModel by viewModels()", activity)
        self.assertIn("Mode.LAB,completedImages", baseline)
        self.assertIn("Mode.PRODUCTION_V6,completedImages", baseline)
        self.assertIn("completedImages.mapNotNull", baseline)
        self.assertIn("comparisonFieldRows", baseline)
        self.assertIn("baseline.missing", screen)
        view_model = self.read(APP / "java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt")
        self.assertIn("baselines?.all(PhoneCorpusRunner.Mode.PRODUCTION_V6)", view_model)
        self.assertIn("fieldRows(labCsv)", view_model)

    def test_lab_evaluation_selection_is_enforced(self):
        corpus = self.read(GLINER / "GlinerLabCorpus.kt")
        self.assertIn('it.getString("image") in included', corpus)
        self.assertIn('require(sample.included)', corpus)
        view_model = self.read(self.VM)
        self.assertIn("it.sample.included && (only==null", view_model)
        self.assertIn("it.sample.included && it.result!=null", view_model)
        runner = self.read(GLINER / "GlinerLabRunner.kt")
        self.assertIn("eligible=PhoneCorpusMath.eligible", runner)

    def test_run_survives_leaving_the_screen(self):
        view_model = self.read(self.VM)
        self.assertIn("GlinerLabSession", view_model)
        self.assertIn("session.scope.launch", view_model)
        self.assertNotIn("viewModelScope.launch", view_model)
        self.assertIn("does not cancel the run", view_model)
        service = self.read(self.SERVICE)
        self.assertIn("startForeground", service)
        self.assertIn("PARTIAL_WAKE_LOCK", service)
        manifest = self.read(APP / "AndroidManifest.xml")
        self.assertIn('android:name="com.yourapp.wine.gliner.GlinerLabRunService"', manifest)
        self.assertIn("RunService.start(app", view_model)

    def test_threshold_rejections_are_visible(self):
        model = self.read(GLINER / "GlinerOnnxModel.kt")
        self.assertIn("val probes:List<String>", model)
        self.assertIn("scored += candidate", model)
        runner = self.read(GLINER / "GlinerLabRunner.kt")
        self.assertIn("probes=labelled.probes", runner)
        screen = self.read(self.SCREEN)
        self.assertIn("probes", screen)


class GlinerBundleInspectTests(unittest.TestCase):
    """End-to-end checks for tools/inspect-gliner-bundle.py using a synthetic graph.

    The real GLiNER weights are not in this repository, so these tests build a tiny ONNX graph with
    the documented inputs, one MatMulNBits node and a span-logit output. That is enough to prove the
    pre-flight tool accepts a contract-compatible bundle and refuses a graph the app would refuse.
    They are skipped where onnx/onnxruntime/tokenizers are not installed.
    """

    TOOL = ROOT / "tools/inspect-gliner-bundle.py"
    PROMPTS = ["wine producer or winery name", "wine name or cuvee", "wine appellation, region or country"]

    def run_tool(self, bundle):
        return subprocess.run([sys.executable, str(self.TOOL), str(bundle)],
                              capture_output=True, text=True, timeout=180)

    def test_help_works_without_the_optional_dependencies(self):
        result = subprocess.run([sys.executable, str(self.TOOL), "--help"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("MatMulNBits", result.stdout)

    def test_missing_bundle_names_the_file_to_provision(self):
        with tempfile.TemporaryDirectory() as folder:
            result = self.run_tool(folder)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing gliner_small_int4.onnx", result.stdout + result.stderr)
        self.assertIn("fetch-gliner-small-int4.py", result.stdout + result.stderr)

    def test_fetch_tool_defaults_to_the_smaller_embedding_quantized_bundle(self):
        result = subprocess.run([sys.executable, str(ROOT / "tools/fetch-gliner-small-int4.py"), "--help"],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--no-quantize-embeddings", result.stdout)
        self.assertIn("GatherBlockQuantized", result.stdout)

    @unittest.skipUnless(HAVE_ONNX, "onnx/onnxruntime/tokenizers are not installed")
    def test_synthetic_int4_bundle_is_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            build_synthetic_bundle(pathlib.Path(folder))
            result = self.run_tool(folder)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("MatMulNBits nodes: 1", result.stdout)
        self.assertIn("layout: span logits [1, spans, classes]", result.stdout)
        self.assertIn("bundle is compatible", result.stdout)

    @unittest.skipUnless(HAVE_ONNX, "onnx/onnxruntime/tokenizers are not installed")
    def test_graph_without_matmulnbits_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            build_synthetic_bundle(pathlib.Path(folder), int4=False)
            result = self.run_tool(folder)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not the INT4 bundle", result.stdout + result.stderr)

    @unittest.skipUnless(HAVE_ONNX, "onnx/onnxruntime/tokenizers are not installed")
    def test_class_count_that_does_not_match_the_three_lanes_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            build_synthetic_bundle(pathlib.Path(folder), classes=4)
            result = self.run_tool(folder)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not one of the app's accepted layouts", result.stdout + result.stderr)


def build_synthetic_bundle(folder, int4=True, classes=3):
    """Writes a contract-compatible toy bundle: six inputs, one float output, INT4 node optional."""
    folder.mkdir(parents=True, exist_ok=True)
    words = ["[UNK]", "[PAD]", "[CLS]", "[SEP]", "<<", ">>", "wine", "producer", "or", "winery",
             "name", "cuvee", "appellation", "region", "country"]
    vocab = {word: index for index, word in enumerate(words)}
    tokenizer = Tokenizer(models.WordPiece(vocab=vocab, unk_token="[UNK]"))
    tokenizer.normalizer = normalizers.Sequence([normalizers.NFD(), normalizers.Lowercase(), normalizers.StripAccents()])
    tokenizer.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
    tokenizer.post_processor = processors.TemplateProcessing(
        single="[CLS] $A [SEP]",
        special_tokens=[("[CLS]", vocab["[CLS]"]), ("[SEP]", vocab["[SEP]"])])
    tokenizer.save(str(folder / "tokenizer.json"))

    k, n, block, bits = 32, 4, 32, 4
    rng = np.random.default_rng(0)
    initializers = [
        numpy_helper.from_array(rng.integers(0, 255, size=(n, k // block, block * bits // 8), dtype=np.uint8), "B"),
        numpy_helper.from_array(np.ones((n, k // block), dtype=np.float32), "scales"),
        numpy_helper.from_array(np.zeros((1, k), dtype=np.float32), "A"),
        numpy_helper.from_array(np.array([1], dtype=np.int64), "one"),
        numpy_helper.from_array(np.array([classes], dtype=np.int64), "classes_vec"),
        numpy_helper.from_array(np.array([1], dtype=np.int64), "axis1"),
    ]
    nodes = []
    if int4:
        nodes.append(helper.make_node("MatMulNBits", ["A", "B", "scales"], ["mm"], domain="com.microsoft",
                                      K=k, N=n, bits=bits, block_size=block, accuracy_level=4))
    nodes += [
        helper.make_node("Shape", ["span_mask"], ["mask_shape"]),
        helper.make_node("Gather", ["mask_shape", "axis1"], ["span_count"], axis=0),
        helper.make_node("Concat", ["one", "span_count", "classes_vec"], ["out_shape"], axis=0),
        helper.make_node("ConstantOfShape", ["out_shape"], ["logits"],
                         value=numpy_helper.from_array(np.zeros((1,), dtype=np.float32))),
    ]
    inputs = [
        helper.make_tensor_value_info("input_ids", TensorProto.INT64, [1, "seq"]),
        helper.make_tensor_value_info("attention_mask", TensorProto.INT64, [1, "seq"]),
        helper.make_tensor_value_info("words_mask", TensorProto.INT64, [1, "seq"]),
        helper.make_tensor_value_info("text_lengths", TensorProto.INT64, [1, 1]),
        helper.make_tensor_value_info("span_idx", TensorProto.INT64, [1, "spans", 2]),
        helper.make_tensor_value_info("span_mask", TensorProto.BOOL, [1, "spans"]),
    ]
    graph = helper.make_graph(nodes, "synthetic-gliner", inputs,
                              [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, "spans", classes])],
                              initializers)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 19), helper.make_opsetid("com.microsoft", 1)])
    model.ir_version = 9
    onnx.save(model, str(folder / "gliner_small_int4.onnx"))
    config = {"model_type": "gliner", "ent_token": "<<", "sep_token": ">>", "max_width": 12,
              "max_len": 384, "span_mode": "markerV0", "threshold": 0.5}
    (folder / "gliner_config.json").write_text(json.dumps(config), encoding="utf-8")
