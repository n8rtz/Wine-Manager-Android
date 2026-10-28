import csv
import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("funnel_fixture", ROOT / "tools/logic-checks/generate-funnel-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class PipelineFunnelChecks(unittest.TestCase):
    def test_bundled_fixture_is_explicitly_saved_and_has_no_stage_evidence(self):
        result = fixture.prepare()
        self.assertEqual(len(result["predictions"]), 168)
        self.assertEqual(sum(len(p["pipelines"][0]["fields"]) for p in result["predictions"]), 1008)
        self.assertEqual(result["expected"]["complete"], "34/160")
        self.assertIn("NOT a measured latest Lab run", result["metadata"]["fixture_origin"])
        self.assertTrue(all("trace" not in p and "pipeline_funnel" not in p for p in result["predictions"]))

    def test_partial_scored_input_is_not_a_full_corpus_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "partial.csv"
            with source.open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=["row_type", "pipeline", "image", "field"])
                writer.writeheader()
                writer.writerow(dict(row_type="field", pipeline="lab-v4-expanded-producers", image="one", field="producer"))
            with self.assertRaisesRegex(ValueError, "168 distinct"):
                fixture.prepare(source, [])

    def test_same_aggregate_reaches_screen_scored_and_raw_exports(self):
        base = ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus"
        scoreboard = (base / "PhoneCorpusScoreboard.kt").read_text()
        self.assertIn("val funnel=F.aggregate(funnelLabels)", scoreboard)
        self.assertIn("csv.addAll(funnel.map { it.csv(mode.pipelineId) })", scoreboard)
        self.assertIn("board.matrix.forEach", (base / "WineAppLabelsActivity.kt").read_text())
        self.assertIn("csv.addAll(funnel.map { it.csv(mode.pipelineId) })", scoreboard)
        viewmodel = (base / "WineAppLabelsViewModel.kt").read_text()
        self.assertIn('"pipeline_funnel"', viewmodel)
        self.assertIn('scorer.build(frozen, mode).csv.filter { it["row_type"] == "pipeline_step" }', viewmodel)
        self.assertNotIn('remove("pipeline_funnel")', viewmodel)
        self.assertIn('result.remove("trace")', viewmodel)
        # Frozen baselines keep the compact funnel and drop only the bulk evidence.
        baselines = (base / "WineAppLabelsBaselines.kt").read_text()
        for dropped in ('record.remove("trace")', 'record.remove("tokens")', 'record.remove("diagnostics")'):
            self.assertIn(dropped, baselines)
        self.assertNotIn('record.remove("pipeline_funnel")', baselines)

    def test_field_matrix_keeps_six_columns_and_each_step_description_collapsed_by_default(self):
        # The 168-photo debug page that used to carry this table is retired; the live 125-label
        # page is the only bench, so this contract is asserted against it (and its condensed
        # step column) instead of the deleted file.
        source = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        table = source.split('Text("Pipeline bottleneck matrix"', 1)[1].split('Text("Database coverage"', 1)[0]
        # Columns are measured from their own text instead of being guessed from a fraction of
        # the screen, so headers, step labels and counts each stay on one line.
        self.assertIn("val matrixWidths = remember(board, labelStyle, cellStyle, passColor, failColor) {", table)
        self.assertIn("MatrixWidths(step = step, fields = widened)", table)
        self.assertIn("measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp()", table)
        self.assertIn("board.matrix.map { AnnotatedString(it.step.shortTitle) }", table)
        self.assertIn("Column(Modifier.horizontalScroll(rememberScrollState())) {", table)
        self.assertNotIn("maxWidth * 0.19f", table)
        self.assertIn("PhoneCorpusMath.fields.forEachIndexed { index, field ->", table)
        self.assertIn("board.matrix.forEach", table)
        self.assertIn('Text(if (expanded) "−" else "+")', table)
        # Collapsed rows show only the condensed short label; the full title, branch and
        # definition live in the expanded Column.
        self.assertIn("row.step.shortTitle,", table)
        self.assertIn("Modifier.weight(1f),", table)
        self.assertIn("Text(row.step.title, style = MaterialTheme.typography.titleSmall)", table)
        self.assertIn("Text(row.step.branch, style = MaterialTheme.typography.labelSmall", table)
        self.assertIn("Text(row.step.definition, style = MaterialTheme.typography.bodySmall)", table)
        self.assertNotIn("320.dp", table)
        funnel = (ROOT / "app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text()
        self.assertIn("val shortTitle: String get()", funnel)
        self.assertIn('"resolver.mlkit.eligible" to "ML eligible"', funnel)

    def test_labels_matrix_colors_pass_fail_and_measures_its_columns(self):
        source = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        table = source.split('Text("Pipeline bottleneck matrix"', 1)[1].split('Text("Database coverage"', 1)[0]
        self.assertIn("private fun passFailLabel", source)
        self.assertIn("passFailLabel(it.passed, it.failed, passColor, failColor, notReached = it.notReached", table)
        self.assertIn("passFailLabel(row.shared.passed, row.shared.failed, passColor, failColor, shared = true", table)
        self.assertIn("val passColor = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)", table)
        self.assertIn("val failColor = MaterialTheme.colorScheme.error", table)
        self.assertIn("Modifier.width(fieldWidths[index]).heightIn(min = 48.dp)", table)
        self.assertIn("Modifier.width(fieldWidths.fold(0.dp) { total, width -> total + width }).heightIn(min = 48.dp)", table)
        self.assertIn("row.step.shortTitle,", table)
        self.assertIn("Modifier.weight(1f),", table)
        # Single-line cells: no wrapping anywhere in the matrix (one measure + five Text cells).
        self.assertEqual(table.count("maxLines = 1"), 6)
        self.assertEqual(table.count("softWrap = false"), 6)
        self.assertIn("Text(row.step.title, style = MaterialTheme.typography.titleSmall)", table)
        self.assertIn("Text(row.step.definition, style = MaterialTheme.typography.bodySmall)", table)
        self.assertIn("Column(Modifier.horizontalScroll(rememberScrollState())) {", table)
        self.assertNotIn("320.dp", table)
        funnel = (ROOT / "app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text()
        self.assertIn("val shortTitle: String get()", funnel)
        self.assertIn('"resolver.mlkit.eligible" to "ML eligible"', funnel)

    def test_retired_corpus_page_is_gone_and_splitting_survives(self):
        base = ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus"
        for retired in ("PhoneCorpusActivity.kt", "PhoneCorpusViewModel.kt", "PhoneCorpusCsvTable.kt"):
            self.assertFalse((base / retired).exists(), retired)
        self.assertFalse((ROOT / "app/src/debug/assets/phone-corpus-v1").exists())
        manifest = (ROOT / "app/src/debug/AndroidManifest.xml").read_text()
        self.assertNotIn("PhoneCorpusActivity", manifest)
        # The upload splitter is shared with the live page and must not be retired with the page.
        splitter = (base / "CsvUploadParts.kt").read_text()
        self.assertIn("LIMIT_BYTES=25_000_000L", splitter)
        self.assertIn("CsvUploadParts.split(target, exportDir)", (base / "WineAppLabelsViewModel.kt").read_text())
        # No packaged path may still point at the deleted corpus.
        for path in base.glob("*.kt"):
            self.assertNotIn("phone-corpus-v1", path.read_text(), path.name)

    def test_matrix_uses_only_measured_field_gates_and_preserves_scalar_csv_rows(self):
        scoreboard = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusScoreboard.kt").read_text()
        funnel = (ROOT / "app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text()
        self.assertIn('id.startsWith("producer.") -> "producer"', funnel)
        self.assertIn('id.startsWith("wine.") -> "wine_name"', funnel)
        self.assertIn("val fieldScores=PhoneCorpusMath.fields.associate", scoreboard)
        self.assertIn("val cohort=if(prerequisite==null) labels else labels.filter", scoreboard)
        self.assertIn("F.State.NOT_RECORDED", scoreboard)
        self.assertIn('return "field.populated"', scoreboard)
        self.assertIn('return "field.correct"', scoreboard)
        self.assertIn('return "resolver.${step.id.removePrefix("producer.")}"', scoreboard)
        self.assertIn("check(steps.size==1)", scoreboard)
        self.assertIn('"matrix.field.populated"', (ROOT / "tools/logic-checks/PipelineFunnelChecks.kt").read_text())
        self.assertIn("csv.addAll(funnel.map { it.csv(mode.pipelineId) })", scoreboard)
        self.assertIn('"score.complete"', scoreboard)

    def test_selected_mode_filters_exclusive_steps_and_complete_row_is_bottom(self):
        source = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        scoreboard = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusScoreboard.kt").read_text()
        self.assertIn("(!step.labOnly || mode.usesSpatialEvidence)", scoreboard)
        self.assertIn("(!step.productionOnly || !mode.usesSpatialEvidence)", scoreboard)
        self.assertIn('it.id != "score.complete"', (ROOT / "app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text())
        self.assertIn("var expandedDiagnostics by remember(state.mode) { mutableStateOf(emptySet<String>()) }", source)
        self.assertIn("board.matrix.last().step.id == \"score.complete\"", (ROOT / "tools/logic-checks/PipelineFunnelChecks.kt").read_text())

    def test_telemetry_does_not_enter_normal_add_wine_or_supply_reference_answers(self):
        pure = (ROOT / "app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text()
        self.assertIn("latest attempt only", pure)
        self.assertIn("NOT_RECORDED", pure)
        self.assertTrue((ROOT / "app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").exists())
        for path in (ROOT / "app/src/main/java/com/yourapp/wine/capture").rglob("*.kt"):
            self.assertNotIn("PipelineFunnel", path.read_text())
        resolver = (ROOT / "app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt").read_text()
        self.assertIn("MAX_FUZZY_CANDIDATES=200", resolver)  # wine lookup cost bound remains unchanged
        self.assertIn("MAX_PRODUCER_FUZZY_CANDIDATES=256", resolver)
        self.assertIn("single-engine fuzzy supplemental candidate needs LWIN or independent engines", resolver)
        for name in ("ProducerResolver.kt", "LinkedWineResolver.kt"):
            source = (ROOT / "app/src/main/java/com/yourapp/wine/gazetteer" / name).read_text()
            self.assertNotIn("scoring.json", source)
            self.assertNotIn("saved-production-v2", source)

    def test_merged_build_metadata_has_one_commit_declaration_and_all_fields(self):
        source = (ROOT / "app/build.gradle.kts").read_text()
        self.assertEqual(len(re.findall(r"^val buildCommit =", source, flags=re.MULTILINE)), 1)
        self.assertIn('gitMetadata("rev-parse", "--short=12", "HEAD")', source)
        for name in ("BUILD_BRANCH", "BUILD_COMMIT", "BUILD_WORKING_TREE", "NATIVE_ABIS", "BUILD_DIRTY", "BUILD_TIME_MILLIS"):
            self.assertIn('"' + name + '"', source)
        self.assertIn("abiFilters += packagedNativeAbis", source)
