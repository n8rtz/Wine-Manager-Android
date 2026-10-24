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
                writer.writerow(dict(row_type="field", pipeline="lab-linked-wine-v1", image="one", field="producer"))
            with self.assertRaisesRegex(ValueError, "168 distinct"):
                fixture.prepare(source, [])

    def test_same_aggregate_reaches_screen_scored_and_raw_exports(self):
        base = ROOT / "app/src/debug/java/com/yourapp/wine/phonecorpus"
        scoreboard = (base / "PhoneCorpusScoreboard.kt").read_text()
        self.assertIn("val funnel=F.aggregate(funnelLabels)", scoreboard)
        self.assertIn("csv.addAll(funnel.map { it.csv(mode.pipelineId) })", scoreboard)
        self.assertIn("board.matrix.forEach", (base / "PhoneCorpusActivity.kt").read_text())
        self.assertIn("csv.addAll(funnel.map { it.csv(mode.pipelineId) })", scoreboard)
        viewmodel = (base / "PhoneCorpusViewModel.kt").read_text()
        self.assertIn('"pipeline_funnel"', viewmodel)
        self.assertIn("for(row in funnelRows)", viewmodel)
        self.assertNotIn('remove("pipeline_funnel")', viewmodel)
        self.assertIn('result.remove("trace")', viewmodel)

    def test_field_matrix_keeps_six_columns_and_each_step_description_collapsed_by_default(self):
        source = (ROOT / "app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusActivity.kt").read_text()
        table = source.split('Text("Pipeline bottleneck matrix"', 1)[1].split('TextButton(onClick={importCsv.launch', 1)[0]
        self.assertIn("val stepWidth=220.dp", table)
        self.assertIn("val fieldWidth=88.dp", table)
        self.assertIn("PhoneCorpusMath.fields.forEach", table)
        self.assertIn("board.matrix.forEach", table)
        self.assertIn('Text(if(expanded) "−" else "+")', table)
        self.assertIn("if(expanded) Text(row.step.definition", table)
        self.assertIn("maxLines=1,overflow=TextOverflow.Ellipsis", table)
        self.assertIn("passFailLabel(it.passed,it.failed,passColor,failColor)", table)
        self.assertIn("if(row.shared!=null)", table)
        self.assertIn("Modifier.width(viewportWidth).border(1.dp,gridColor)", table)
        self.assertIn("Modifier.width(stepWidth).heightIn(min=48.dp).border(1.dp,gridColor)", table)
        self.assertIn("outlineVariant", table)
        self.assertIn("passColor=if(isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)", table)
        self.assertIn("failColor=MaterialTheme.colorScheme.error", table)
        self.assertIn("passFailLabel(row.shared.passed,row.shared.failed,passColor,failColor,shared=true)", table)
        self.assertIn("passFailLabel(it.passed,it.failed,passColor,failColor)", table)
        self.assertIn("Modifier.horizontalScroll(matrixScroll)", table)
        self.assertIn("rememberSaveable(state.mode)", source)
        self.assertNotIn("showFunnelDetails", source)
        self.assertNotIn("fontSize", table)

    def test_matrix_uses_only_measured_field_gates_and_preserves_scalar_csv_rows(self):
        scoreboard = (ROOT / "app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusScoreboard.kt").read_text()
        funnel = (ROOT / "app/src/debug/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text()
        self.assertIn('id.startsWith("producer.") -> "producer"', funnel)
        self.assertIn('id.startsWith("wine.") -> "wine_name"', funnel)
        self.assertIn("val fieldScores=PhoneCorpusMath.fields.associate", scoreboard)
        self.assertIn("val passed=gates.count { it.state==F.State.PASSED }", scoreboard)
        self.assertIn("val failed=gates.count { it.state==F.State.BLOCKED }", scoreboard)
        self.assertIn('return "field.populated"', scoreboard)
        self.assertIn('return "field.correct"', scoreboard)
        self.assertIn('return "resolver.${step.id.removePrefix("producer.")}"', scoreboard)
        self.assertIn("check(steps.size==1)", scoreboard)
        self.assertIn('"matrix.field.populated"', (ROOT / "tools/logic-checks/PipelineFunnelChecks.kt").read_text())
        self.assertIn("csv.addAll(funnel.map { it.csv(mode.pipelineId) })", scoreboard)
        self.assertIn('"score.complete"', scoreboard)

    def test_selected_mode_filters_exclusive_steps_and_complete_row_is_bottom(self):
        source = (ROOT / "app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusActivity.kt").read_text()
        scoreboard = (ROOT / "app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusScoreboard.kt").read_text()
        self.assertIn("(!step.labOnly || mode==PhoneCorpusRunner.Mode.LAB)", scoreboard)
        self.assertIn("(!step.productionOnly || mode!=PhoneCorpusRunner.Mode.LAB)", scoreboard)
        self.assertIn('it.id != "score.complete"', (ROOT / "app/src/debug/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text())
        self.assertIn("var expandedFunnelSteps by rememberSaveable(state.mode) { mutableStateOf(emptySet<String>()) }", source)
        self.assertIn("board.matrix.last().step.id == \"score.complete\"", (ROOT / "tools/logic-checks/PipelineFunnelChecks.kt").read_text())

    def test_telemetry_does_not_enter_normal_add_wine_or_supply_reference_answers(self):
        pure = (ROOT / "app/src/debug/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").read_text()
        self.assertIn("latest attempt only", pure)
        self.assertIn("NOT_RECORDED", pure)
        self.assertFalse((ROOT / "app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt").exists())
        resolver = (ROOT / "app/src/debug/java/com/yourapp/wine/gazetteer/ProducerResolver.kt").read_text()
        self.assertIn("MAX_FUZZY_CANDIDATES=200", resolver)
        for name in ("ProducerResolver.kt", "LinkedWineResolver.kt"):
            source = (ROOT / "app/src/debug/java/com/yourapp/wine/gazetteer" / name).read_text()
            self.assertNotIn("scoring.json", source)
            self.assertNotIn("saved-production-v2", source)

    def test_merged_build_metadata_has_one_commit_declaration_and_all_fields(self):
        source = (ROOT / "app/build.gradle.kts").read_text()
        self.assertEqual(len(re.findall(r"^val buildCommit =", source, flags=re.MULTILINE)), 1)
        self.assertIn('gitMetadata("rev-parse", "--short=12", "HEAD")', source)
        for name in ("BUILD_BRANCH", "BUILD_COMMIT", "BUILD_WORKING_TREE", "NATIVE_ABIS", "BUILD_DIRTY", "BUILD_TIME_MILLIS"):
            self.assertIn('"' + name + '"', source)
        self.assertIn("abiFilters += packagedNativeAbis", source)
