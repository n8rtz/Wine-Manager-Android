import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REVIEW = ROOT / "tools/evaluation/wine-app-labels-review-v1"
ASSETS = ROOT / "app/src/main/assets/wine-app-labels-v1"


class WineAppLabelsCorpusTests(unittest.TestCase):
    def test_packaged_photos_and_target_metadata_are_complete_and_frozen(self):
        labels = json.loads((REVIEW / "candidate-labels.json").read_text())
        manifest = json.loads((ASSETS / "manifest.json").read_text())
        inputs_doc = json.loads((ASSETS / "inputs.json").read_text())
        selection = json.loads((ASSETS / "evaluation-set.json").read_text())
        scoring = json.loads((ASSETS / "scoring.json").read_text())
        inputs = inputs_doc["images"]
        targets = scoring["images"]
        self.assertEqual((len(labels["records"]), len(inputs), len(targets)), (125, 125, 125))
        self.assertEqual((selection["included_count"], selection["excluded_count"]), (125, 10))
        self.assertEqual({row["image"] for row in inputs}, {row["image"] for row in targets})
        self.assertEqual({row["image"] for row in selection["included_images"]}, {row["image"] for row in inputs})
        self.assertEqual({p.name for p in (ASSETS / "images").glob("*.jpg")}, {row["image"] for row in inputs})
        self.assertNotIn("IMG_2732.jpeg", {p.name for p in (ASSETS / "images").iterdir()})
        self.assertEqual(manifest["count"], 125)
        self.assertEqual(sum(row["source_repo"] == "AntoninLeroy/wine_label_reader_toolkit" for row in inputs), 103)
        self.assertEqual(sum(row["source_repo"] == "n8rtz/Wine-Reference-Drop" for row in inputs), 22)
        self.assertEqual(manifest["bytes"], sum((ASSETS / "images" / row["image"]).stat().st_size for row in inputs))
        self.assertEqual(hashlib.sha256((ASSETS / "inputs.json").read_bytes()).hexdigest(), manifest["inputs_sha256"])
        self.assertEqual(hashlib.sha256((ASSETS / "evaluation-set.json").read_bytes()).hexdigest(), manifest["evaluation_set_sha256"])
        self.assertEqual(hashlib.sha256((ASSETS / "scoring.json").read_bytes()).hexdigest(), manifest["scoring_sha256"])
        for row in inputs:
            self.assertEqual(hashlib.sha256((ASSETS / "images" / row["image"]).read_bytes()).hexdigest(), row["sha256"])
        by_sha = {row["sha256"]: row["image"] for row in inputs}
        for row in labels["records"]:
            # The review workbook's source_path pointed into the retired debug corpus; the same
            # bytes are packaged under wine-app-labels-v1, so verify provenance there.
            packaged = ASSETS / "images" / by_sha[row["source_sha256"]]
            self.assertEqual(hashlib.sha256(packaged.read_bytes()).hexdigest(), row["source_sha256"], row["source_path"])
        self.assertEqual({row["review_number"] for row in targets}, {row["review_number"] for row in labels["records"]})
        self.assertFalse({14, 49, 52, 55, 64, 73, 133, 159, 168} & {row["review_number"] for row in targets})
        self.assertTrue(all(set(row["fields"]) == {"producer", "wine_name", "country", "region", "subregion", "vintage"} for row in targets))

    def test_legacy_saved_photo_map_uses_exact_bytes(self):
        # The 168-photo debug corpus is retired, so the map is checked for internal consistency
        # against the live corpus: every legacy photo maps to a retained photo with the same bytes.
        mapping = json.loads((ASSETS / "legacy-phone-corpus-image-map.json").read_text())
        new_inputs = json.loads((ASSETS / "inputs.json").read_text())["images"]
        by_image = {row["image"]: row["sha256"] for row in new_inputs}
        rows = mapping["images"]
        self.assertEqual(mapping["mapping_count"], 103)
        self.assertEqual(len(rows), 103)
        self.assertEqual(len({row["legacy_image"] for row in rows}), 103)
        self.assertEqual(len({row["image"] for row in rows}), 103)
        for row in rows:
            self.assertRegex(row["legacy_image"], r"^[0-9a-f]{40}\.jpg$")
            self.assertEqual(by_image[row["image"]], row["sha256"], row["legacy_image"])
            self.assertNotEqual(row["legacy_image"], row["image"])

    def test_gazetteer_coverage_counts_unique_known_labels_on_scoring_side(self):
        coverage = json.loads((ASSETS / "gazetteer-coverage.json").read_text())
        self.assertEqual(coverage["dataset_count"], 125)
        self.assertIn("never provided to the OCR runner", coverage["method"])
        rows = {row["id"]: row for row in coverage["databases"]}
        self.assertEqual(set(rows), {"lwin_producers", "expanded_producers", "lwin_wines", "appellations", "wine_geography",
                                     "kb_producers", "kb_wine_names", "kb_appellations", "kb_geography"})
        self.assertEqual(rows["lwin_producers"]["matched_labels"], 86)
        # The Lab expanded producer index (LWIN + X-Wines/TTB/OSM candidates) is a
        # strict superset of the LWIN-only producer coverage.
        self.assertEqual(rows["expanded_producers"]["matched_labels"], 101)
        self.assertGreater(rows["expanded_producers"]["matched_labels"], rows["lwin_producers"]["matched_labels"])
        self.assertEqual(rows["lwin_wines"]["matched_labels"], 42)
        self.assertEqual(rows["appellations"]["matched_labels"], 89)
        # Scanner KB rows include the bundled wine-knowledge origins catalog
        # (editorial + UC Davis AVA) folded into the knowledge-base place list.
        self.assertEqual(rows["kb_appellations"]["matched_labels"], 72)
        self.assertEqual(rows["kb_producers"]["matched_labels"], 13)
        self.assertTrue(all(0 <= row["matched_labels"] <= 125 for row in rows.values()))
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        self.assertIn("Gazetteer database coverage", activity)
        self.assertIn("Known workbook values and accepted aliases", activity)

    def test_user_adjudications_are_applied_to_workbook_and_phone_targets(self):
        labels = json.loads((REVIEW / "candidate-labels.json").read_text())
        by_review = {row["review_number"]: row for row in labels["records"]}
        self.assertEqual(by_review[169]["wine_name"], "Gran Reserva 904")
        self.assertEqual(by_review[171]["wine_name"], "Triton")
        self.assertEqual((by_review[172]["producer"], by_review[172]["wine_name"]), ("Kirkland Signature", "Pauillac"))
        self.assertEqual((by_review[173]["producer"], by_review[173]["wine_name"], by_review[173]["subregion"]), ("Ciabot", "Baronetto", "Barbaresco"))
        self.assertEqual(by_review[179]["wine_name"], "Campo Eliseo")
        self.assertEqual(by_review[180]["wine_name_alternates"], ["Peppoli"])
        self.assertEqual(by_review[182]["country"], "California")
        self.assertEqual(by_review[182]["subregion_alternates"], ["Stags Leap"])
        self.assertEqual(by_review[189]["producer"], "Kirkland Signature")
        self.assertEqual(by_review[66]["producer"], "A. Kolb")
        self.assertIn("Club des Sommeliers", by_review[66]["notes"])
        self.assertEqual(by_review[190]["notes"].find("bottler, not substituted for producer") >= 0, True)
        scoring = json.loads((ASSETS / "scoring.json").read_text())
        cases = {row["review_number"]: row for row in scoring["images"]}
        self.assertEqual(cases[169]["fields"]["wine_name"]["target"], "Gran Reserva 904")
        self.assertEqual(cases[180]["fields"]["wine_name"]["accepted"], ["peppolichianticlassico", "peppoli"])
        self.assertIn("stagsleap", cases[182]["fields"]["subregion"]["accepted"])
        self.assertEqual(cases[176]["fields"]["vintage"]["status"], "unresolved")
        self.assertEqual(cases[188]["fields"]["region"]["status"], "expected_blank")
        self.assertNotIn("clubdessommeliers", cases[66]["fields"]["producer"]["accepted"])

    def test_red_rows_and_club_producer_removal_are_audited(self):
        audit = json.loads((REVIEW / "workbook-update-audit.json").read_text())
        red_ids = [49, 52, 55, 64, 73, 133, 159, 168]
        self.assertEqual(audit["red_rows_removed_from_both_tabs"], red_ids)
        self.assertEqual(audit["owner_removed_club_des_sommeliers_review_ids"], [14])
        self.assertEqual(audit["all_removed_review_ids"], sorted(red_ids + [14]))
        self.assertTrue((REVIEW / "reference-data-scrubbed-updated.xlsx").is_file())
        self.assertEqual(len(json.loads((ASSETS / "inputs.json").read_text())["images"]), 125)

    def test_main_profile_routes_to_the_125_label_test(self):
        profile = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt").read_text()
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        self.assertIn("com.yourapp.wine.phonecorpus.WineAppLabelsActivity", profile)
        self.assertIn("WineAppLabelsActivity\" android:exported=\"false\"", manifest)
        self.assertIn('Text("Large image dataset test"', activity)
        self.assertIn('"wine-app-labels-v1/images/$image"', activity)
        self.assertIn('PhoneCorpusRunner.runImageAllModes(', viewmodel)
        self.assertNotIn('PhoneCorpusRunner.runImage(', viewmodel)
        self.assertIn('"wine-app-labels-v1"', viewmodel)
        self.assertIn("125 retained workbook-linked wine labels", activity)
        producer_manifest = json.loads((ROOT / "app/src/main/assets/producer-gazetteer-v1/manifest.json").read_text())
        self.assertNotIn("support", producer_manifest)
        self.assertNotIn("support_attribution", producer_manifest)
        for name in ("ttb-support.sqlite", "osm-support.sqlite", "xwines-candidates.sqlite"):
            self.assertFalse((ROOT / "app/src/main/assets/producer-gazetteer-v1" / name).exists())
            self.assertFalse((ROOT / "app/src/debug/assets/producer-gazetteer-v1" / name).exists())
        self.assertIn("val columnWidth = 88.dp", activity)
        self.assertIn("Lab v4 uses the Lab-only expanded producer index", activity)
        self.assertIn("Supplemental candidates never reach production or the Prod versions", activity)
        self.assertIn("Pipeline bottleneck matrix", activity)
        self.assertIn("are frozen benchmarks, not live contenders", activity)
        self.assertIn("every later pass scores that saved record instead of re-running them", activity)
        self.assertNotIn("Every photo runs through all four versions", activity)
        self.assertIn("Mode.entries.map { comparedMode ->", viewmodel)
        self.assertNotIn("savedPredictions", viewmodel)
        self.assertNotIn('File(app.noBackupFilesDir, "phone-corpus-v1")', viewmodel)
        self.assertIn("val legacy = PhoneCorpusRunner.Mode.entries.mapNotNull { mode -> latestFolder(mode)?.let { mode to it } }.toMap()", viewmodel)
        self.assertIn("val promoted = store.promote(BASELINE_MODES, legacy)", viewmodel)
        self.assertIn("val comparisonPredictions = byMode.getValue(comparedMode)", viewmodel)
        self.assertIn('if (it == "Complete") "Fully correct" else it', viewmodel)
        self.assertIn("Attempted is the saved-photo tally", viewmodel)
        self.assertIn('BASELINE_MODES = listOf(', viewmodel)
        # Only Lab is offered as a runnable mode; Prod versions are frozen comparators.
        self.assertIn("private val BASELINE_MODES = listOf(", viewmodel)
        self.assertIn("RadioButton(", activity)
        self.assertNotIn("RUNNABLE_MODES", viewmodel)
        self.assertIn("Fully correct (for example, 2/5) means all six scorable fields matched", activity)
        self.assertIn('"Lab v4 checkpointed: ${state.labCompleted}/${state.total}"', activity)
        self.assertIn("Prod baselines captured: $frozen/${state.total} photos", activity)
        start_body = viewmodel.split("fun start(retryFailures: Boolean = false)", 1)[1]
        self.assertEqual(start_body.count("PhoneCorpusRunner.runImageAllModes("), 1)
        self.assertIn('app, image, input.getString("sha256"), needed, File(root, "work"),', start_body)
        # Only photos without a frozen record enter a Prod version; Lab is always eligible.
        self.assertIn("val neededBaselines = BASELINE_MODES.filter", start_body)
        self.assertIn("val frozen = store.record(version, image)", start_body)
        self.assertIn("if (!runLab && neededBaselines.isEmpty()) continue", start_body)
        self.assertIn("store.save(version, image, result)", start_body)
        self.assertIn("for ((version, result) in results)", start_body)
        self.assertIn("init { load(PhoneCorpusRunner.Mode.LAB) }", viewmodel)
        self.assertIn("fun onBackgroundPause(resumeWhenForeground: Boolean)", viewmodel)
        self.assertIn("fun onForeground()", viewmodel)
        self.assertNotIn("ProductionBaselinePlan", viewmodel)
        self.assertNotIn("baselinePending", viewmodel)
        self.assertIn("vm.onBackgroundPause(resumeWhenForeground = !isFinishing)", activity)
        self.assertIn("vm.onForeground()", activity)
        self.assertIn("Reuse this version's saved per-photo results", viewmodel)
        self.assertIn('"evaluation_set_sha256", "scoring_sha256"', viewmodel)
        self.assertIn('metadata.optString("pipeline") != mode.pipelineId', viewmodel)
        self.assertNotIn('dir.name.startsWith(folderPrefix(mode))', viewmodel)
        runner = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt").read_text()
        for label in ("Lab v4", "Prod v3.0", "Prod v2.0", "Prod v1.0"):
            self.assertIn(f'("{label}"', runner)

    def test_large_image_dataset_screen_prepares_and_saves_csv_exports(self):
        # The main-branch Large image dataset test page prepared CSVs, then saved each part via
        # the system document picker. The 125-label replacement screen lost that flow.
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        self.assertIn('ActivityResultContracts.CreateDocument("text/csv")', activity)
        self.assertIn("vm.saveExportPart(it, exportIndex)", activity)
        self.assertIn('Text("Prepare scored CSV export")', activity)
        self.assertIn('Text("Prepare raw diagnostics CSV export")', activity)
        self.assertIn("vm.prepareExport(raw = false)", activity)
        self.assertIn("vm.prepareExport(raw = true)", activity)
        self.assertIn("export.launch(file.name)", activity)
        self.assertIn('Text("Save ${if (state.exportParts.size > 1) "part ${index + 1}/${state.exportParts.size}" else "CSV"}', activity)
        self.assertIn('Text("Split an existing CSV for upload")', activity)
        self.assertIn("vm::splitExistingCsv", activity)
        self.assertIn("val exportParts: List<File> = emptyList()", viewmodel)
        self.assertIn("fun prepareExport(raw: Boolean)", viewmodel)
        self.assertIn("fun saveExportPart(uri: Uri, index: Int)", viewmodel)
        self.assertIn("fun splitExistingCsv(uri: Uri)", viewmodel)
        # Both exports cover every version (frozen Prod + live Lab) and use the shared splitter.
        self.assertIn("for ((mode, records) in predictionsByMode())", viewmodel)
        self.assertIn("private fun writeScoredCsv(target: File)", viewmodel)
        self.assertIn("private fun writeRawCsv(target: File)", viewmodel)
        self.assertIn('"result_origin" to "frozen_baseline"', viewmodel)
        self.assertIn("CsvUploadParts.split(target, exportDir)", viewmodel)
        # Saving a CSV opens the document picker, which must not be treated as pausing a run.
        self.assertIn("if (mutable.value.busy && !exporting) {", viewmodel)

    def test_prod_versions_are_captured_once_and_then_read_from_the_frozen_store(self):
        # Owner rule: Prod v1.0/v2.0/v3.0 are benchmarks, not contenders. Each photo runs through
        # them once, the outcome is saved, and later passes score the saved outcome. Only Lab v4
        # re-runs, so the comparison rows stay meaningful across builds.
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        store = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsBaselines.kt").read_text()

        # 1. Frozen records are keyed by corpus signature, never by build SHA or install time:
        #    that is what makes them survive an app update.
        self.assertIn('File(app.noBackupFilesDir, "wine-app-labels-v1/baselines-v1/$signature")', store)
        self.assertIn("MessageDigest.getInstance(\"SHA-256\").digest(corpusSignature.toByteArray())", store)
        for forbidden in ("packageManager", "lastUpdateTime", "firstInstallTime", "BuildConfig.BUILD_COMMIT).take("):
            self.assertNotIn(forbidden, store)
        self.assertIn("record.put(\"corpus_signature\", signature)", store)
        self.assertIn("record.put(\"captured_build\", BuildConfig.BUILD_COMMIT)", store)

        # 2. A frozen record keeps what the scoreboard/diagnostics/CSV read and drops raw evidence.
        self.assertIn('record.remove("trace")', store)
        self.assertIn('record.remove("tokens")', store)
        self.assertIn('record.remove("diagnostics")', store)
        self.assertIn('record.put("frozen", true)', store)
        self.assertIn('if (PhoneCorpusRunner.Mode.fromPipelineId(row.optString("pipeline")) == mode) row.put("pipeline", mode.pipelineId)', store)

        # 3. Upgrading must not demand a second Prod pass: a completed checkpoint of the frozen
        #    pipeline is promoted as the capture, failures stay pending, existing records win.
        promote_body = store.split("fun promote(", 1)[1]
        self.assertIn('if (previous.optString("status") != "complete") continue', promote_body)
        self.assertIn("if (image.isBlank() || record(mode, image) != null) continue", promote_body)
        self.assertIn("save(mode, image, previous)", promote_body)
        self.assertIn("runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.optBoolean(\"frozen\") }", store)

        # 4. Selection rule, mirrored from start(); the source must encode the same predicate.
        def needed(has_lab, lab_failed, frozen, frozen_failed, retry):
            """Modes still to run for one photo. A mode runs only when it has nothing usable."""
            def prod(version):
                record = frozen.get(version)
                return (record is not None and frozen_failed.get(version, False)) if retry else record is None
            return (["LAB"] if (lab_failed if has_lab else True) else []) + [v for v in ("V1", "V2", "V3") if prod(v)]
        self.assertEqual(needed(True, False, {"V1": 1, "V2": 1, "V3": 1}, {}, False), [])
        self.assertEqual(needed(True, False, {"V1": 1, "V2": 1}, {}, False), ["V3"])
        self.assertEqual(needed(False, False, {"V1": 1, "V2": 1, "V3": 1}, {}, False), ["LAB"])
        self.assertEqual(needed(True, False, {}, {}, False), ["V1", "V2", "V3"])
        self.assertEqual(needed(True, False, {"V1": 1, "V2": 1, "V3": 1}, {"V2": True}, True), ["V2"])
        self.assertEqual(needed(True, True, {"V1": 1, "V2": 1, "V3": 1}, {}, True), ["LAB"])
        start_body = viewmodel.split("fun start(retryFailures: Boolean = false)", 1)[1]
        self.assertIn("val runLab = if (retryFailures) labPrevious == null || isFailed(labPrevious) else labPrevious == null", start_body)
        self.assertIn("if (retryFailures) frozen != null && isFailed(frozen) else frozen == null", start_body)
        self.assertIn("if (!runLab && neededBaselines.isEmpty()) continue", start_body)
        # One OCR pass serves every mode still needed for that photo.
        self.assertEqual(start_body.count("PhoneCorpusRunner.runImageAllModes("), 1)
        self.assertIn("val needed = neededBaselines.toSet() + if (runLab) setOf(PhoneCorpusRunner.Mode.LAB) else emptySet()", start_body)

        # 5. Frozen rows are labelled as such and never claim a live run id.
        self.assertIn('"result_origin" to "frozen_baseline"', viewmodel)
        # Lab rows keep the live run folder's id; frozen rows never borrow it.
        self.assertIn('"run_id" to (labFolder?.name ?: "")', viewmodel)
        self.assertEqual(viewmodel.count('"run_id" to "frozen-baseline"'), 2)  # scored + raw exports
        self.assertIn('"captured_build" to baselines?.capture(mode)?.build.orEmpty()', viewmodel)

        # 6. The screen never offers a Prod version as a live run and says the rows are frozen.
        self.assertIn("enabled = !state.busy && mode == PhoneCorpusRunner.Mode.LAB,", activity)
        self.assertIn("are frozen benchmarks, not live contenders", activity)
        self.assertIn('"Runs fresh OCR on every pass · ${state.labCompleted}/${state.total} checkpointed"', activity)
        self.assertIn('"Frozen ${capture.count}/${state.total} photos · captured by ${capture.build}"', activity)
        self.assertIn('Text("New Lab run (keeps old results and frozen Prod)")', activity)


if __name__ == "__main__":
    unittest.main()
