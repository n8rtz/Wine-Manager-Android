import hashlib
import json
import re
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
        self.assertEqual((len(labels["records"]), len(inputs), len(targets)), (124, 124, 124))
        self.assertEqual((selection["included_count"], selection["excluded_count"]), (124, 11))
        self.assertIn(67, selection["excluded_review_numbers"])
        self.assertEqual({row["image"] for row in inputs}, {row["image"] for row in targets})
        self.assertEqual({row["image"] for row in selection["included_images"]}, {row["image"] for row in inputs})
        self.assertEqual({p.name for p in (ASSETS / "images").glob("*.jpg")}, {row["image"] for row in inputs})
        self.assertNotIn("IMG_2732.jpeg", {p.name for p in (ASSETS / "images").iterdir()})
        self.assertEqual(manifest["count"], 124)
        self.assertEqual(sum(row["source_repo"] == "AntoninLeroy/wine_label_reader_toolkit" for row in inputs), 102)
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
        self.assertFalse({14, 49, 52, 55, 64, 67, 73, 133, 159, 168} & {row["review_number"] for row in targets})
        self.assertTrue(all(set(row["fields"]) == {"producer", "wine_name", "country", "region", "subregion", "vintage"} for row in targets))

    def test_legacy_saved_photo_map_uses_exact_bytes(self):
        # The 168-photo debug corpus is retired, so the map is checked for internal consistency
        # against the live corpus: every legacy photo maps to a retained photo with the same bytes.
        mapping = json.loads((ASSETS / "legacy-phone-corpus-image-map.json").read_text())
        new_inputs = json.loads((ASSETS / "inputs.json").read_text())["images"]
        by_image = {row["image"]: row["sha256"] for row in new_inputs}
        rows = mapping["images"]
        self.assertEqual(mapping["mapping_count"], 102)
        self.assertEqual(len(rows), 102)
        self.assertEqual(len({row["legacy_image"] for row in rows}), 102)
        self.assertEqual(len({row["image"] for row in rows}), 102)
        for row in rows:
            self.assertRegex(row["legacy_image"], r"^[0-9a-f]{40}\.jpg$")
            self.assertEqual(by_image[row["image"]], row["sha256"], row["legacy_image"])
            self.assertNotEqual(row["legacy_image"], row["image"])

    def test_gazetteer_coverage_counts_unique_known_labels_on_scoring_side(self):
        coverage = json.loads((ASSETS / "gazetteer-coverage.json").read_text())
        self.assertEqual(coverage["dataset_count"], 124)
        self.assertIn("never provided to the OCR runner", coverage["method"])
        # Per-parameter total coverage across all consulted databases (rendered on screen).
        params = coverage["parameters"]
        self.assertEqual(
            [(row["id"], row["label"]) for row in params],
            [
                ("producer", "Producer"),
                ("wine_name", "Wine name"),
                ("country", "Country"),
                ("region", "Region"),
                ("subregion", "Subregion"),
            ],
        )
        by_param = {row["id"]: row for row in params}
        self.assertEqual((by_param["producer"]["matched_labels"], by_param["producer"]["total_labels"]), (100, 124))
        self.assertEqual((by_param["wine_name"]["matched_labels"], by_param["wine_name"]["total_labels"]), (106, 123))
        self.assertEqual(by_param["wine_name"]["wine_index_matched_labels"], 42)
        self.assertEqual(by_param["wine_name"]["appellation_or_varietal_matched_labels"], 64)
        self.assertEqual((by_param["country"]["matched_labels"], by_param["country"]["total_labels"]), (124, 124))
        self.assertEqual((by_param["region"]["matched_labels"], by_param["region"]["total_labels"]), (120, 120))
        self.assertEqual((by_param["subregion"]["matched_labels"], by_param["subregion"]["total_labels"]), (81, 81))
        self.assertTrue(all(0 <= row["matched_labels"] <= row["total_labels"] <= 124 for row in params))
        # Source-level databases remain in the asset for provenance auditing.
        rows = {row["id"]: row for row in coverage["databases"]}
        self.assertEqual(set(rows), {"lwin_producers", "expanded_producers", "lwin_wines", "appellations", "wine_geography",
                                     "kb_producers", "kb_wine_names", "kb_appellations", "kb_geography"})
        self.assertEqual(rows["lwin_producers"]["matched_labels"], 86)
        # The Lab expanded producer index (LWIN + X-Wines/TTB/OSM candidates) is a
        # strict superset of the LWIN-only producer coverage.
        self.assertEqual(rows["expanded_producers"]["matched_labels"], 100)
        self.assertGreater(rows["expanded_producers"]["matched_labels"], rows["lwin_producers"]["matched_labels"])
        self.assertEqual(rows["lwin_wines"]["matched_labels"], 42)
        self.assertEqual(rows["appellations"]["matched_labels"], 89)
        # Scanner KB rows include the bundled wine-knowledge origins catalog
        # (editorial + UC Davis AVA) folded into the knowledge-base place list.
        # The nested catalog adds the Bordeaux, Portuguese, Georgian and Italian names the
        # workbook prints; geography-role rows are counted as regions, not appellations.
        self.assertEqual(rows["kb_appellations"]["matched_labels"], 78)
        self.assertEqual(rows["kb_appellations"]["database_records_matched"], 42)
        self.assertEqual(rows["kb_producers"]["matched_labels"], 13)
        self.assertTrue(all(0 <= row["matched_labels"] <= 124 for row in rows.values()))
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        self.assertIn('Text("Database coverage"', activity)
        self.assertNotIn("Gazetteer database coverage", activity)
        self.assertIn('data.getJSONArray("parameters")', activity)
        self.assertNotIn('data.getJSONArray("databases")', activity)
        # Method/provenance text belongs in the coverage asset and docs, not on the page.
        self.assertIn("never provided to the OCR runner", coverage["method"])
        self.assertNotIn("Known workbook values and accepted aliases", activity)

    def test_user_adjudications_are_applied_to_workbook_and_phone_targets(self):
        labels = json.loads((REVIEW / "candidate-labels.json").read_text())
        by_review = {row["review_number"]: row for row in labels["records"]}
        self.assertNotIn(67, by_review)
        self.assertEqual((by_review[90]["wine_name"], by_review[90]["country"], by_review[90]["region"], by_review[90]["subregion"]),
                         ("Premier Grand Cru Classé", "France", "Bordeaux", "Margaux"))
        self.assertEqual((by_review[92]["country"], by_review[92]["region"], by_review[92]["subregion"]),
                         ("France", "Bordeaux", "Pessac-Léognan"))
        self.assertEqual(by_review[169]["wine_name"], "Gran Reserva 904")
        self.assertEqual(by_review[171]["wine_name"], "Triton")
        self.assertEqual((by_review[171]["region"], by_review[171]["subregion"]), ("Castilla y Leon", "Toro"))
        self.assertEqual((by_review[172]["producer"], by_review[172]["wine_name"]), ("Kirkland Signature", "Pauillac"))
        self.assertEqual((by_review[173]["producer"], by_review[173]["wine_name"], by_review[173]["subregion"]), ("Ciabot", "Baronetto", "Barbaresco"))
        self.assertEqual((by_review[179]["producer"], by_review[179]["wine_name"], by_review[179]["region"], by_review[179]["subregion"]),
                         ("Campo Eliseo", "Cuvée Alegre", "Castilla y Leon", "Toro"))
        self.assertEqual((by_review[181]["region"], by_review[181]["subregion"]), ("Maipo Valley", "Puente Alto"))
        self.assertEqual((by_review[184]["region"], by_review[184]["subregion"]), ("Willamette Valley", "Dundee Hills"))
        self.assertEqual(by_review[180]["wine_name_alternates"], ["Peppoli"])
        self.assertEqual(by_review[182]["country"], "California")
        self.assertEqual(by_review[182]["subregion_alternates"], ["Stags Leap"])
        self.assertEqual(by_review[189]["producer"], "Kirkland Signature")
        self.assertEqual(by_review[66]["producer"], "A. Kolb")
        self.assertIn("Club des Sommeliers", by_review[66]["notes"])
        self.assertEqual(by_review[190]["notes"].find("bottler, not substituted for producer") >= 0, True)
        scoring = json.loads((ASSETS / "scoring.json").read_text())
        cases = {row["review_number"]: row for row in scoring["images"]}
        self.assertNotIn(67, cases)
        self.assertEqual((cases[90]["fields"]["wine_name"]["target"], cases[90]["fields"]["country"]["target"],
                          cases[90]["fields"]["region"]["target"], cases[90]["fields"]["subregion"]["target"]),
                         ("Premier Grand Cru Classé", "France", "Bordeaux", "Margaux"))
        self.assertEqual((cases[92]["fields"]["region"]["target"], cases[92]["fields"]["subregion"]["target"]),
                         ("Bordeaux", "Pessac-Léognan"))
        self.assertEqual(cases[169]["fields"]["wine_name"]["target"], "Gran Reserva 904")
        self.assertEqual((cases[171]["fields"]["region"]["target"],cases[171]["fields"]["subregion"]["target"]),
                         ("Castilla y Leon","Toro"))
        self.assertEqual((cases[179]["fields"]["producer"]["target"],cases[179]["fields"]["wine_name"]["target"],
                          cases[179]["fields"]["region"]["target"],cases[179]["fields"]["subregion"]["target"]),
                         ("Campo Eliseo","Cuvée Alegre","Castilla y Leon","Toro"))
        self.assertEqual((cases[181]["fields"]["region"]["target"],cases[181]["fields"]["subregion"]["target"]),
                         ("Maipo Valley","Puente Alto"))
        self.assertEqual((cases[184]["fields"]["region"]["target"],cases[184]["fields"]["subregion"]["target"]),
                         ("Willamette Valley","Dundee Hills"))
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
        self.assertEqual(audit["owner_removed_dataset_review_ids"], [67])
        self.assertEqual({row["sheet"] for row in audit["owner_removed_dataset_row_detail"]}, {"reference-data-168", "Condensed Table"})
        self.assertEqual(audit["all_removed_review_ids"], sorted(red_ids + [14, 67]))
        corrections = {row["review_number"]: row["fields"] for row in audit["owner_adjudicated_field_corrections"]}
        self.assertEqual(corrections[90], {"wine_name": "Premier Grand Cru Classé", "country": "France", "region": "Bordeaux", "subregion": "Margaux"})
        self.assertEqual(corrections[92], {"region": "Bordeaux", "subregion": "Pessac-Léognan"})
        self.assertEqual(corrections[171], {"region": "Castilla y Leon", "subregion": "Toro"})
        self.assertEqual(corrections[179], {"producer": "Campo Eliseo", "wine_name": "Cuvée Alegre", "region": "Castilla y Leon", "subregion": "Toro"})
        self.assertEqual(corrections[181], {"region": "Maipo Valley", "subregion": "Puente Alto"})
        self.assertEqual(corrections[184], {"region": "Willamette Valley", "subregion": "Dundee Hills"})
        self.assertTrue((REVIEW / "reference-data-scrubbed-updated.xlsx").is_file())
        self.assertEqual(len(json.loads((ASSETS / "inputs.json").read_text())["images"]), 124)

    def test_main_profile_routes_to_the_124_label_test(self):
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
        self.assertIn("124 retained workbook-linked wine labels", activity)
        producer_manifest = json.loads((ROOT / "app/src/main/assets/producer-gazetteer-v1/manifest.json").read_text())
        self.assertNotIn("support", producer_manifest)
        self.assertNotIn("support_attribution", producer_manifest)
        for name in ("ttb-support.sqlite", "osm-support.sqlite", "xwines-candidates.sqlite"):
            self.assertFalse((ROOT / "app/src/main/assets/producer-gazetteer-v1" / name).exists())
            self.assertFalse((ROOT / "app/src/debug/assets/producer-gazetteer-v1" / name).exists())
        self.assertIn("val columnWidth = 88.dp", activity)
        # The Lab-only index claim lives in the copied stats and the docs, not in a wall of
        # on-screen prose (the owner asked for the long paragraphs to be removed).
        self.assertIn("the expanded producer index (LWIN plus X-Wines/TTB/OpenStreetMap candidates)", viewmodel)
        self.assertIn("LAB ONLY expanded producer index", (ROOT / "_refs/ARCH.md").read_text())
        self.assertIn("Pipeline bottleneck matrix", activity)
        # Top model section shows only Lab v5 + Cached OCR (retired Prod model rows removed from top).
        self.assertIn("val mode = PhoneCorpusRunner.Mode.LAB", activity)
        self.assertIn('"Runs fresh OCR on every pass · ${state.labCompleted}/${state.total} checkpointed"', activity)
        self.assertNotIn("PhoneCorpusRunner.Mode.entries.forEach", activity)
        self.assertNotIn("RadioButton(", activity)
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
        self.assertEqual(activity.count("SelectionContainer {"), 1)
        self.assertIn("SelectionContainer {\n            LazyColumn(", activity)
        self.assertNotIn("RUNNABLE_MODES", viewmodel)
        self.assertNotIn("Fully correct = all six scorable", activity)
        # The plain "Ready…" line was removed; the counts say the same thing.
        self.assertNotIn("Only the 124 retained workbook labels are eligible", viewmodel)
        self.assertIn("if (state.message.isNotBlank()) Text(state.message)", activity)
        self.assertIn('"Lab v5 checkpointed: ${state.labCompleted}/${state.total}"', activity)
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
        self.assertIn('val isCurrentRun = savedMode == mode.name && savedPipeline == mode.pipelineId', viewmodel)
        self.assertIn('val isLegacyProdV3Run = mode == PhoneCorpusRunner.Mode.PRODUCTION_V4', viewmodel)
        self.assertNotIn('dir.name.startsWith(folderPrefix(mode))', viewmodel)
        runner = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt").read_text()
        for label in ("Lab v5", "Prod v4.0", "Prod v2.0", "Prod v1.0"):
            self.assertIn(f'("{label}"', runner)

    def test_large_image_dataset_screen_prepares_and_saves_csv_exports(self):
        # The main-branch Large image dataset test page prepared CSVs, then saved each part via
        # the system document picker. The 124-label replacement screen lost that flow.
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
        # Owner rule: Prod v1.0/v2.0/v4.0 are benchmarks, not contenders. Each photo runs through
        # them once, the outcome is saved, and later passes score the saved outcome. Only Lab v5
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
        self.assertIn('.firstOrNull { it.optBoolean("frozen") }', store)
        self.assertIn('.filter { it.isFile }', store)

        # 4. Selection rule, mirrored from start(); the source must encode the same predicate.
        def needed(has_lab, lab_failed, frozen, frozen_failed, retry):
            """Modes still to run for one photo. A mode runs only when it has nothing usable."""
            def prod(version):
                record = frozen.get(version)
                return (record is not None and frozen_failed.get(version, False)) if retry else record is None
            return (["LAB"] if (lab_failed if has_lab else True) else []) + [v for v in ("V1", "V2", "V4") if prod(v)]
        self.assertEqual(needed(True, False, {"V1": 1, "V2": 1, "V4": 1}, {}, False), [])
        self.assertEqual(needed(True, False, {"V1": 1, "V2": 1}, {}, False), ["V4"])
        self.assertEqual(needed(False, False, {"V1": 1, "V2": 1, "V4": 1}, {}, False), ["LAB"])
        self.assertEqual(needed(True, False, {}, {}, False), ["V1", "V2", "V4"])
        self.assertEqual(needed(True, False, {"V1": 1, "V2": 1, "V4": 1}, {"V2": True}, True), ["V2"])
        self.assertEqual(needed(True, True, {"V1": 1, "V2": 1, "V4": 1}, {}, True), ["LAB"])
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

        # 6. The screen never offers a Prod version as a live run at the top; only Lab v5 + Cached OCR appear.
        self.assertIn("val mode = PhoneCorpusRunner.Mode.LAB", activity)
        self.assertNotIn("PhoneCorpusRunner.Mode.entries.forEach", activity)
        self.assertIn('"Runs fresh OCR on every pass · ${state.labCompleted}/${state.total} checkpointed"', activity)
        self.assertIn('Text("New Lab run (keeps old results and frozen Prod)")', activity)


    def test_benchmark_screen_stays_free_of_wall_of_text_copy(self):
        # Owner rule: the Large image dataset test is a tool, not a document. Status, counts,
        # tables and controls stay; the multi-sentence explanations live in the docs and in the
        # copied stats instead of the screen.
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        long_literals = [literal for literal in re.findall(r'"([^"\n]{121,})"', activity)]
        self.assertEqual(long_literals, [], "on-screen copy over 120 characters")
        for removed in (
            "This normal-app benchmark uses all 124 retained rows",
            "Private on-device benchmark",
            "Prod v4.0 uses verified LWIN producer data only",
            "Prod v1.0, Prod v2.0 and Prod v4.0 are frozen benchmarks",
            "Attempted is the saved-photo tally",
            "The scored CSV holds one result set per version",
            "Step-level diagnostics for the selected version only",
            "Known workbook values and accepted aliases",
        ):
            self.assertNotIn(removed, activity)
        # What must survive: the title, the subtitle, per-mode state and every control.
        for kept in ('Text("Large image dataset test"', '"124 retained workbook-linked wine labels', 'Text(mode.title)',
                     'Text(state.message)', 'Copy scored stats', 'Prepare scored CSV export',
                     'Prepare raw diagnostics CSV export', 'Split an existing CSV for upload',
                     'Pipeline bottleneck matrix', 'Database coverage', 'Incomplete labels ('):
            self.assertIn(kept, activity)
        self.assertNotIn('Gazetteer database coverage', activity)
        self.assertNotIn('Reviewed labels', activity)

    def test_incomplete_labels_section_is_collapsed_by_default_with_multi_field_failure_toggles(self):
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        # Replaced Reviewed labels with collapsible Incomplete labels section, collapsed by default.
        self.assertNotIn("showReviewedLabels", activity)
        self.assertIn("var showIncompleteLabels by remember { mutableStateOf(false) }", activity)
        self.assertIn("var selectedFailureFields by remember { mutableStateOf(emptySet<String>()) }", activity)
        self.assertIn('"Incomplete labels (${incompleteCases.size})"', activity)
        self.assertIn("if (showIncompleteLabels) {", activity)

        # Six failure-filter toggle buttons supporting simultaneous multi-select.
        for key, label in (
            ("producer", "Producer"),
            ("wine_name", "Wine name"),
            ("country", "Country"),
            ("region", "Region"),
            ("subregion", "Subregion"),
            ("vintage", "Vintage"),
        ):
            self.assertIn(f'"{key}" to "{label}"', activity)
        self.assertIn("FilterChip(", activity)
        self.assertIn("selectedFailureFields - fieldKey", activity)
        self.assertIn("selectedFailureFields + fieldKey", activity)
        self.assertIn('row["reference_status"] != "unresolved" && !PhoneCorpusMath.correct(row["outcome"].orEmpty())', activity)
        self.assertIn("failedFieldsByImage[wineCase.image].orEmpty().any { it in selectedFailureFields }", activity)
        self.assertIn("results[field]?.takeIf(::isFieldFailure)?.let { field to it }", activity)
        self.assertNotIn("color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified", activity)

        # Clicking the thumbnail opens a full-resolution zoomable (pinch / pan) dialog; single tap dismisses it.
        self.assertIn("var expandedImage by rememberSaveable { mutableStateOf<String?>(null) }", activity)
        self.assertIn("LabelThumbnail(wineCase.image, onClick = { expandedImage = wineCase.image })", activity)
        self.assertIn("ZoomableLabelDialog(image = image, onDismiss = { expandedImage = null })", activity)
        self.assertIn("DialogProperties(usePlatformDefaultWidth = false)", activity)
        self.assertIn("event.calculateZoom()", activity)
        self.assertIn("event.calculatePan()", activity)
        self.assertIn("if (!wasCanceled && !movedPastSlop && !multiTouch)", activity)
        self.assertIn("currentOnDismiss()", activity)

    def test_lab_v5_best_build_row_and_scalar_results_log_contracts(self):
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        build_log = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsBuildLog.kt").read_text()

        # Separate scalar log file (never mixed into scored/raw export CSVs, zero raw OCR/image data)
        # logs both per-run scalar metrics and the composite best_build row with source build numbers.
        self.assertIn('const val FILE_NAME = "benchmark-results-log.csv"', build_log)
        self.assertIn('const val BEST_BUILD_ROW_TITLE = "Lab v5 Best build"', build_log)
        self.assertIn('const val BEST_BUILD_ROW_TYPE = "best_build"', build_log)
        self.assertNotIn("Lab v5 Last build", build_log)
        self.assertNotIn('"Last build"', activity)
        self.assertNotIn('"Last build"', viewmodel)
        for field in (
            '"row_type"',
            '"date"',
            '"time"',
            '"build_number"',
            '"version_code"',
            '"build_commit"',
            '"branch"',
            '"model_version"',
            '"attempted"',
            '"eligible"',
            '"complete"',
            '"one_error"',
            '"two_plus_errors"',
            '"failed"',
            '"correct_values"',
            '"wrong"',
            '"missing"',
            '"correct_blanks"',
            '"total_correct"',
            '"unsupported"',
            '"unresolved"',
            '"deadline_partial"',
            '"producer_correct"',
            '"producer_incorrect"',
            '"wine_name_correct"',
            '"wine_name_incorrect"',
            '"country_correct"',
            '"country_incorrect"',
            '"region_correct"',
            '"region_incorrect"',
            '"subregion_correct"',
            '"subregion_incorrect"',
            '"vintage_correct"',
            '"vintage_incorrect"',
            '"best_complete_build"',
            '"best_total_correct_build"',
            '"best_correct_values_build"',
            '"best_producer_build"',
            '"best_wine_name_build"',
            '"best_country_build"',
            '"best_region_build"',
            '"best_subregion_build"',
            '"best_vintage_build"',
        ):
            self.assertIn(field, build_log)
        for forbidden in ('"raw_text"', '"tokens"', '"trace"', "BitmapFactory", "images/"):
            self.assertNotIn(forbidden, build_log)

        # Score table places "Lab v5 Best build" directly below "Lab v5" and above Prod v4.0/v2.0/v1.0,
        # backfills existing saved Lab runs on load, and syncs the persisted best_build log row.
        self.assertIn("val comparisonRows = listOf(modeRows.first(), bestBuildRow) + modeRows.drop(1)", viewmodel)
        self.assertIn("val bestBuild = buildLog?.bestBuildSummary()", viewmodel)
        self.assertIn("backfillBuildLog(logStore, signature)", viewmodel)
        self.assertIn("log.syncBestBuildSummary(PhoneCorpusRunner.Mode.LAB)", viewmodel)
        self.assertIn('WineAppLabelsBuildLog(File(root, WineAppLabelsBuildLog.FILE_NAME))', viewmodel)

        # Granular per-field Current build / Best build / Prod v4.0 table sits at the bottom of
        # Scored results (above CSV export) using green/red "correct/incorrect" passFailLabel formatting.
        scored_section = activity.split('Text("Scored results"', 1)[1].split('Text("CSV export"', 1)[0]
        self.assertIn("BuildFieldComparisonTable(", scored_section)
        self.assertIn('"Current build" to currentScores', activity)
        self.assertIn('"Best build" to bestBuildScores', activity)
        self.assertIn("PhoneCorpusRunner.Mode.PRODUCTION_V4.title to prodV4Scores", activity)
        self.assertIn("val prodV4Preds = byMode.getValue(PhoneCorpusRunner.Mode.PRODUCTION_V4)", viewmodel)
        self.assertIn("passFailLabel(score.correct, score.incorrect, passColor, failColor)", activity)

    def test_background_execution_and_lab_ocr_cache_contracts(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        runner = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt").read_text()
        service = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsRunService.kt").read_text()
        cache = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsOcrCache.kt").read_text()

        # 1. Background execution: process-scoped coroutine session + non-exported foreground service;
        #    leaving WineAppLabelsActivity (Back or app switch) no longer calls stop().
        self.assertIn("private class WineAppLabelsSession", viewmodel)
        self.assertIn("CoroutineScope(SupervisorJob() + Dispatchers.IO)", viewmodel)
        pause_body = viewmodel.split("fun onBackgroundPause(resumeWhenForeground: Boolean) {", 1)[1].split("\n    }", 1)[0]
        self.assertNotIn("stop()", pause_body)
        self.assertIn("WineAppLabelsRunService.start(app,", viewmodel)
        self.assertIn("WineAppLabelsRunService.update(", viewmodel)
        self.assertIn("WineAppLabelsRunService.stop(app)", viewmodel)
        self.assertIn('android:name="com.yourapp.wine.phonecorpus.WineAppLabelsRunService"', manifest)
        self.assertIn('android:foregroundServiceType="specialUse"', manifest)
        self.assertIn("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE", service)
        self.assertIn("PowerManager.PARTIAL_WAKE_LOCK", service)

        # 2. Cached OCR checkbox sits next to Lab v5 only and reuses verified OCR outputs without
        #    ever replaying cached OCR into Prod baselines.
        self.assertIn("val mode = PhoneCorpusRunner.Mode.LAB", activity)
        self.assertIn("Checkbox(", activity)
        self.assertIn("checked = state.useCachedOcr", activity)
        self.assertIn("onCheckedChange = vm::setUseCachedOcr", activity)
        self.assertIn('Text("Cached OCR"', activity)
        self.assertIn('File(app.noBackupFilesDir, "wine-app-labels-v1/ocr-cache-v1/$signature")', cache)
        for forbidden in ("packageManager", "lastUpdateTime", "firstInstallTime", "BuildConfig.BUILD_COMMIT).take("):
            self.assertNotIn(forbidden, cache)
        self.assertIn("fun promote(runFolders: List<File>, expectedHashes: Map<String, String>): Int", cache)
        self.assertIn("Mode.PRODUCTION_V1 !in modes && modes==setOf(Mode.LAB) && it.image==image && it.imageSha256==hash", runner)
        self.assertIn("useCachedOcr && needed == setOf(PhoneCorpusRunner.Mode.LAB)", viewmodel)
        self.assertIn("onFreshOcr = { meta, rows -> ocrStore?.save(image, imageSha, meta, rows) }", viewmodel)

    def test_benchmark_timers_sit_below_start_button_and_above_tables(self):
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        timers = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsTimers.kt").read_text()

        # Placement: directly below the Start button and above the Scored results table.
        start_idx = activity.index('"Start / resume Lab v5 only"')
        timer_idx = activity.index("BenchmarkTimersRow(state = state, nowRealtimeMs = nowRealtimeMs)")
        table_idx = activity.index('Text("Scored results"')
        self.assertLess(start_idx, timer_idx)
        self.assertLess(timer_idx, table_idx)

        # Three distinct timers: Run time (mm ss), Avg / image (s), Est. remaining (countdown mm ss).
        self.assertIn('"Run time" to WineAppLabelsTimers.formatElapsedMmSs(liveElapsedMs)', activity)
        self.assertIn('"Avg / image" to WineAppLabelsTimers.formatAverageSeconds(state.avgImageMs)', activity)
        self.assertIn('"Est. remaining" to WineAppLabelsTimers.formatCountdownMmSs(remainingMs)', activity)
        self.assertIn('"%02dm %02ds".format(Locale.US, minutes, seconds)', timers)
        self.assertIn('"%.1fs".format(Locale.US, avgImageMs.coerceAtLeast(0L) / 1000.0)', timers)
        self.assertIn("writeRunTiming(lab, RunTiming(", viewmodel)
        self.assertIn('"elapsed_run_ms"', viewmodel)
        self.assertIn('"timed_images_count"', viewmodel)
        self.assertIn('"timed_images_total_ms"', viewmodel)

    def test_incomplete_label_cards_show_failed_parameter_table_and_collapsed_raw_ocr(self):
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()

        # Failed parameters are rendered as a 3-column Parameter | Expected | Output table,
        # filtered strictly to failed fields.
        self.assertIn("FailedParametersTable(failedResults)", activity)
        table_block = activity.split("private fun FailedParametersTable(", 1)[1]
        for header in ('"Parameter"', '"Expected"', '"Output"'):
            self.assertIn(header, table_block)
        self.assertIn("FIELD_DISPLAY_NAMES[field] ?: field", table_block)

        # Raw OCR per incomplete label is inside a collapsible section that is collapsed by default.
        self.assertIn("var expandedRawOcrImages by remember(state.mode) { mutableStateOf(emptySet<String>()) }", activity)
        self.assertIn("val showRawOcr = wineCase.image in expandedRawOcrImages", activity)
        self.assertIn('"Raw OCR"', activity)
        self.assertIn("if (showRawOcr) {", activity)
        self.assertIn("vm.loadRawOcrText(wineCase.image, state.mode)", activity)
        self.assertIn("suspend fun loadRawOcrText(", viewmodel)
        self.assertIn('row.optString("stage") != "recognition"', viewmodel)

    def test_benchmark_pass_reuses_warm_read_only_engines_and_closes_in_finally(self):
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        runner = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt").read_text()
        extraction = (ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabWineExtraction.kt").read_text()
        spatial = (ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt").read_text()

        # Pass-scoped WarmSession is opened once per start() pass, forwarded to runImageAllModes,
        # and closed in finally even on stop/pause/exception.
        self.assertIn("var warmSession: PhoneCorpusRunner.WarmSession? = null", viewmodel)
        self.assertIn("warmSession = PhoneCorpusRunner.WarmSession(app)", viewmodel)
        self.assertIn("warmSession = warmSession,", viewmodel)
        self.assertIn("runCatching { warmSession?.close() }", viewmodel)
        self.assertIn("internal class WarmSession(context:Context):AutoCloseable", runner)
        self.assertIn("TextLabWineExtraction.append(context,batch,warmSession=warmSession?.extraction)", runner)

        # TextLabWineExtraction skips unused TextLabFieldExtractor on benchmarkOnly batches,
        # reuses warm KB/FillEngine/EvidenceExtractor/Gazetteer handles across photos,
        # replays gazetteer_setup funnel fields, and avoids rebuilding FillEngine on filtered photos.
        self.assertIn("if(batch.benchmarkOnly) null else TextLabFieldExtractor(kb!!)", extraction)
        self.assertIn("kb=warmSession?.knowledgeBase() ?: SqliteWineKnowledgeBase.open(context.applicationContext,0)", extraction)
        self.assertIn("warmSession?.fillEngine(it) ?: com.yourapp.wine.fill.FillEngine(it)", extraction)
        self.assertIn("warmSession?.evidenceExtractor(it,batch.anchorHybrid,batch.repairPlaces)", extraction)
        self.assertIn("val setupFields=cachedGazetteer?.setupFields ?: setup.fields()", extraction)
        self.assertIn("finally { if(ownsKb) kb?.close() }", extraction)
        self.assertIn("finally { if(ownsProducers) producers?.close() }", extraction)
        self.assertIn("if(fillEngine!=null) TextLabFormPreview.append(work,fillEngine,year,recordsOverride=readings)", spatial)

    def test_cached_ocr_defaults_on_carries_across_versions_and_bundles_verified_124_label_seed(self):
        import gzip
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        ocr_cache = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsOcrCache.kt").read_text()
        baselines = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsBaselines.kt").read_text()

        # 1. Cached OCR defaults to true for Lab runs while remaining toggleable via the UI checkbox.
        self.assertIn("val useCachedOcr: Boolean = true,", viewmodel)
        self.assertIn('optBoolean("use_cached_ocr_v2", true) else true', viewmodel)
        self.assertIn("ocrStore.promote(matchingRunFolders(signature) + allRunFolders(), hashes)", viewmodel)

        # 2. OCR cache signature depends only on OCR model + threshold signature (never corpus scoring signature),
        #    migrates across sibling ocr-cache-v1/* dirs and prior run folders, and seeds from packaged asset.
        self.assertIn('private val signature = anchor("ocr-cache-v2|$modelSig")', ocr_cache)
        self.assertNotIn('$corpusSignature|ocr-cache-v1', ocr_cache)
        self.assertIn('const val PACKAGED_SEED_ASSET = "wine-app-labels-v1/ocr-raw-cache-v1.jsonl.gz"', ocr_cache)
        self.assertIn("GZIPInputStream(rawStream)", ocr_cache)

        # 3. Frozen Prod baselines migrate verified retained photos across scoring/reference updates.
        self.assertIn("fun migrateRetainedBaselines(", baselines)
        self.assertIn("val migratedBaselines = store.migrateRetainedBaselines(BASELINE_MODES, hashes)", viewmodel)

        # 4. Packaged 124-label raw OCR seed asset is complete, SHA-256 aligned with inputs.json, and compact.
        seed_path = ROOT / "app/src/main/assets/wine-app-labels-v1/ocr-raw-cache-v1.jsonl.gz"
        self.assertTrue(seed_path.is_file(), "Missing packaged raw OCR seed asset")
        self.assertLess(seed_path.stat().st_size, 2 * 1024 * 1024, "Seed asset should stay compact (< 2 MiB)")
        inputs = {
            row["image"]: row["sha256"]
            for row in json.loads((ROOT / "app/src/main/assets/wine-app-labels-v1/inputs.json").read_text())["images"]
        }
        seen = {}
        with gzip.open(seed_path, "rt", encoding="utf-8") as gz:
            for line in gz:
                if not line.strip():
                    continue
                rec = json.loads(line)
                img = rec["image"]
                self.assertEqual(rec["image_sha256"], inputs[img], img)
                self.assertEqual(rec["metadata"].get("input_sha256"), inputs[img], img)
                rows = rec["rows"]
                cands = {r.get("candidate") for r in rows}
                self.assertIn("mlkit_reference", cands, img)
                self.assertIn("candidate_3", cands, img)
                seen[img] = rec["model_signature"]
        self.assertEqual(set(seen.keys()), set(inputs.keys()))
        self.assertEqual(len(set(seen.values())), 1)

    def test_post_ocr_modules_are_individually_selectable_and_auto_isolated(self):
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        build_log = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsBuildLog.kt").read_text()
        modules = (ROOT / "app/src/main/java/com/yourapp/wine/textlab/evidence/LabPostOcrModules.kt").read_text()

        # 1. All 7 post-Best-V5 modules exist and default to false (reproducing Best V5 when unchecked).
        self.assertIn("val none = LabPostOcrModules()", modules)
        for key in (
            "blank_subregion_fuzzy",
            "vintage_recovery",
            "stacked_appellations",
            "final_producer_near_match",
            "producer_role_particle",
            "spatial_semantic_guards",
            "vertical_wine_title_pairs",
        ):
            self.assertIn(f'"{key}"', modules)

        # 2. Checkboxes appear near the top of Large image dataset test before Start / resume.
        self.assertIn('Text("Post-OCR test modules"', activity)
        self.assertIn('Text("All off (Best V5)")', activity)
        self.assertIn('Text("All on")', activity)
        self.assertIn("LabPostOcrModules.ModuleId.entries.forEach", activity)
        self.assertLess(
            activity.index('Text("Post-OCR test modules"'),
            activity.index("Start / resume Lab v5 only"),
        )

        # 3. ViewModel defaults to LabPostOcrModules.none, isolates Lab checkpoint folders by lab_modules_key,
        #    and logs active module keys to copied stats, CSV exports, and benchmark-results-log.csv.
        self.assertIn("val labModules: LabPostOcrModules = LabPostOcrModules.none,", viewmodel)
        self.assertIn('metadata.optString("lab_modules_key") != mutable.value.labModules.signatureKey()', viewmodel)
        self.assertIn("labModules = activeModules,", viewmodel)
        self.assertIn("Lab v5 post-OCR modules:", viewmodel)
        self.assertIn('"lab_modules" to labModules', build_log)


if __name__ == "__main__":
    unittest.main()
