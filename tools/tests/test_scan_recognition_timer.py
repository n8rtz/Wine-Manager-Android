"""Developer-option scan recognition timer: settings toggle + Add Wine display."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/java/com/n8rtz/winetracker'


class ScanRecognitionTimerTests(unittest.TestCase):
    def test_toggle_setting_is_persisted(self):
        store = (APP / 'scanner/ScanTimerSettings.kt').read_text()
        self.assertIn('object ScanTimerSettings', store)
        self.assertIn('"show_recognition_timer"', store)
        self.assertIn('fun isEnabled(context: Context): Boolean', store)
        self.assertIn('fun setEnabled(context: Context, enabled: Boolean)', store)
        self.assertIn('getBoolean(KEY_SHOW_RECOGNITION_TIMER, false)', store)
        self.assertIn('fun formatDuration(elapsedMs: Long): String', store)

    def test_settings_has_developer_options_toggle(self):
        profile = (APP / 'ui/screens/ProfileScreen.kt').read_text()
        self.assertIn('"Developer options"', profile)
        self.assertIn('Text("Show scan recognition timer")', profile)
        self.assertIn('ScanTimerSettings.isEnabled(context)', profile)
        self.assertIn('ScanTimerSettings.setEnabled(context, enabled)', profile)
        self.assertIn('checked = showScanTimer', profile)

    def test_completion_popup_lists_each_step_with_copy_button(self):
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        store = (APP / 'scanner/ScanTimerSettings.kt').read_text()
        self.assertIn('fun formatDurationWithMs(elapsedMs: Long): String', store)
        # Popup opens when a scan completes and the toggle is on.
        self.assertIn('if (showScanTimer) showScanTimingsPopup = true', add)
        self.assertIn('AlertDialog(', add)
        self.assertIn('title = { Text("Recognition time") }', add)
        # Every diagnostics stage is listed, plus a total.
        self.assertIn('report.diagnostics.stages.map { timing ->', add)
        self.assertIn('"Total: ${ScanTimerSettings.formatDurationWithMs(report.elapsedMs)}"', add)
        self.assertIn('private fun scanStageLabel(', add)
        # Copy button puts the same text on the clipboard.
        self.assertIn('clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(shareText))', add)
        self.assertIn('Text("Copy to clipboard")', add)

    def test_timer_starts_at_photo_confirmation_not_shutter(self):
        session = (APP / 'scanner/ProductionLabelScanSession.kt').read_text()
        # captured() runs at the confirm tap; it must stamp the confirm
        # time as the recognition start, not forward the shutter time.
        self.assertIn('val confirmedMs = SystemClock.elapsedRealtime()', session)
        self.assertIn('LabelScanState.Processing(id, file.absolutePath, confirmedMs)', session)
        self.assertIn('launchPhotoScan(app, id, started, ScanInputSource.CAMERA, file, prescan)', session)
        self.assertNotIn('LabelScanState.Processing(id, file.absolutePath, current.shutterMs)', session)

    def test_recognition_starts_speculatively_at_photo_taken(self):
        session = (APP / 'scanner/ProductionLabelScanSession.kt').read_text()
        vm = (APP / 'viewmodels/ScannerViewModel.kt').read_text()
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        # Photo taken (confirm still showing) -> speculative scan starts.
        self.assertIn('fun photoTaken(context: Context, id: Long, file: File)', session)
        self.assertIn('ProductionLabelScanner.scan(app, file, SystemClock.elapsedRealtime(), ScanInputSource.CAMERA)', session)
        self.assertIn('fun onLabelPhotoTaken(requestId: Long, photoFile: File)', vm)
        self.assertIn('scannerViewModel.onLabelPhotoTaken(requestId, photoFile)', add)
        # Confirm adopts it; retake/dismiss cancels it.
        self.assertIn('prescan?.await()', session)
        self.assertIn('cancelSpeculative(id)', session)
        # ...but the reported total is still measured from confirmation.
        self.assertIn('elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)) else scanned', session)

    def test_fill_detail_stages_are_measured_and_shown(self):
        runner = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerLabRunner.kt').read_text()
        diagnostics = (ROOT / 'app/src/main/java/com/yourapp/wine/diagnostics/ScanDiagnostics.kt').read_text()
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        self.assertIn('val detailStages: List<ScanStageTiming> = emptyList()', diagnostics)
        for stage in ('KB_OPEN', 'GAZETTEER_OPEN', 'BASE_FILL', 'EVIDENCE', 'FIELD_DECISIONS'):
            self.assertIn(stage, runner)
            self.assertIn(stage, add)
        self.assertIn('detailStages=result.detailStages', (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerProductionScanner.kt').read_text())
        # Popup indents the breakdown under Filling in details, copy text too.
        self.assertIn('timing.stage == com.yourapp.wine.diagnostics.ScanStage.FILL', add)
        self.assertIn('"  $label: ${ScanTimerSettings.formatDurationWithMs(ms)}"', add)

    def test_kb_producer_lookups_are_memoized(self):
        kb = (ROOT / 'app/src/main/java/com/yourapp/wine/kb/SqliteWineKnowledgeBase.kt').read_text()
        # Repeated identical FTS+alias+Levenshtein lookups across the
        # fill/evidence stages must be served from cache; results are
        # immutable per KB instance, and imports clear the caches.
        self.assertIn('private val producerHitCache = HashMap<String, List<ProducerHit>>()', kb)
        self.assertIn('producerHitCache["p:$text"]?.let { return@access it }', kb)
        self.assertIn('producerHitCache["f:$text"]?.let { return@access it }', kb)
        self.assertIn('aliasCache[id]?.let { return it }', kb)
        self.assertGreaterEqual(kb.count('producerHitCache.clear()'), 2)
        self.assertIn('aliasCache.clear()', kb)

    def test_gazetteer_lookups_memoized_and_fill_runs_once(self):
        gaz = (ROOT / 'app/src/main/java/com/yourapp/wine/gazetteer/ProducerGazetteer.kt').read_text()
        runner = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerLabRunner.kt').read_text()
        self.assertIn('private val lookupCache = HashMap<String, List<ProducerResolver.Entity>>()', gaz)
        self.assertIn('private val wineLookupCache = HashMap<String, List<LinkedWineResolver.Wine>>()', gaz)
        self.assertIn('lookupCache[cacheKey]?.let { return it }', gaz)
        self.assertIn('wineLookupCache[cacheKey]?.let { return it }', gaz)
        # Bottle filter runs once, before the fill, in the GLiNER
        # post-process — the spatial stage no longer triggers a second
        # base fill on the filtered readings.
        self.assertIn('val bottleKept=TextLabBottleFilter.filterTokens(records).kept', runner)
        self.assertIn('recordsOverride=effectiveRecords', runner)
        self.assertNotIn('recordsOverride=records)', runner)

    def test_first_scan_warmup_covers_ocr_sessions_and_extractor(self):
        warmup = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerScanWarmup.kt').read_text()
        detector = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/PaddleTextDetector.kt').read_text()
        recognizer = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/PaddleTextRecognizer.kt').read_text()
        self.assertIn('suspend fun warm(', detector)
        self.assertIn('suspend fun warm(', recognizer)
        self.assertIn('PaddleTextDetector.warm(app, gate, spec)', warmup)
        self.assertIn('PaddleTextRecognizer.warm(app, gate, spec)', warmup)
        self.assertIn('GlinerOcr.warmMlKit()', warmup)
        # Evidence extractor + friends are pre-built with the same
        # arguments the post-process passes (warm-session cache key).
        self.assertIn('session.evidenceExtractor(kb, true, true, LabPostOcrModules.none.withIntegrated())', warmup)
        self.assertIn('session.anchorHybrid(kb)', warmup)
        self.assertIn('session.producerRepair(kb)', warmup)

    def test_post_records_have_no_score_floor_and_evidence_timing_split(self):
        runner = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerLabRunner.kt').read_text()
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        # Quality first: weak readings are NOT deleted before the
        # fill/evidence stages (a 0.45 floor cost fields in the lab A/B
        # with identical OCR readings); span thresholds gate evidence.
        self.assertIn('val effectiveRecords=bottleKept', runner)
        self.assertNotIn('bottleKept.filter { it.score>=0.45f }', runner)
        self.assertIn('ScanStage.EVIDENCE_SETUP', runner)
        self.assertIn('ScanStage.PRODUCER_LOOKUPS', runner)
        self.assertIn('"Evidence setup (indexes)"', add)
        kb = (ROOT / 'app/src/main/java/com/yourapp/wine/kb/SqliteWineKnowledgeBase.kt').read_text()
        self.assertIn('PRAGMA cache_size=-8192', kb)

    def test_evidence_fast_path_and_scanner_stage_accounting(self):
        runner = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerLabRunner.kt').read_text()
        scanner = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerProductionScanner.kt').read_text()
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        # Quality first: no fast path skips the evidence/spatial stage,
        # and producer repair is never truncated in production.
        self.assertNotIn('skipSpatial', runner)
        self.assertNotIn('spatial contender skipped', runner)
        self.assertNotIn('PRODUCTION_REPAIR_BUDGET_MS', runner)
        self.assertIn('repairBudgetMs=60_000L)', runner)
        # Graph-builder de-duplication is bookkeeping only.
        graph = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt').read_text()
        self.assertIn('val seenPhrases=mutableSetOf<Pair<Set<Int>,String>>()', graph)
        self.assertIn('val singlePass=singles.map { passFor(it.observations) }', graph)
        self.assertIn('val ak=singleCompact[ai];val bk=singleCompact[bi]', graph)
        gaz = (ROOT / 'app/src/main/java/com/yourapp/wine/kb/PlaceGazetteer.kt').read_text()
        self.assertIn('private val sharedRoots = java.util.WeakHashMap<List<Place>, Node>()', gaz)
        supp = (ROOT / 'app/src/main/java/com/yourapp/wine/kb/SupplementedWineKnowledgeBase.kt').read_text()
        self.assertIn('private val sharedSnapshots = java.util.WeakHashMap<List<Place>, List<Place>>()', supp)
        # Previously unmeasured scanner time is now staged.
        self.assertIn('ScanStage.WARM_SESSION,warmPickupMs', scanner)
        self.assertIn('ScanStage.FIELD_ADAPTER,adapterMs', scanner)
        # adapterMs must be computed before the diagnostics that use it.
        self.assertLess(scanner.index('val adapterMs='), scanner.index('val diagnostics='))
        self.assertIn('"Reader pickup"', add)
        self.assertIn('"Field mapping"', add)

    def test_evidence_budget_cap_and_warmup_split(self):
        preview = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt').read_text()
        spatial = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt').read_text()
        runner = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerLabRunner.kt').read_text()
        warmup = (ROOT / 'gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner/GlinerScanWarmup.kt').read_text()
        app = (APP / 'WineTrackerApp.kt').read_text()
        self.assertIn('budgetMs:Long=EvidenceExtractor.DEFAULT_BUDGET_MS', preview)
        self.assertIn('engine.extract(records,year,budgetMs,exactGeographyFastPath=exactGeographyFastPath)', preview)
        self.assertIn('evidenceBudgetMs:Long=com.yourapp.wine.textlab.evidence.EvidenceExtractor.DEFAULT_BUDGET_MS', spatial)
        self.assertEqual(spatial.count('budgetMs=evidenceBudgetMs'), 2)
        # Production (deadline-bound) runs cap evidence at 4s; lab runs
        # (no deadline) keep the extractor default.
        self.assertIn('const val PRODUCTION_EVIDENCE_BUDGET_MS=5_000L', runner)
        self.assertIn('ScanStage.EVIDENCE_EXTRACT,extractMs', runner)
        self.assertIn('ScanStage.SPATIAL_TAIL,tailMs', runner)
        preview_src = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt').read_text()
        self.assertIn('producerRepair!!.repair(original,records,emptyList(),ProducerRepair.recommendedConfig,repairBudgetMs)', preview_src)
        self.assertIn('values["producer_repair_status"]="failed: ${error.javaClass.simpleName}"', preview_src)
        spatial_src = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt').read_text()
        # One shared completion gazetteer, not one per candidate.
        self.assertIn('val completionGazetteer=engine?.let', spatial_src)
        self.assertNotIn('engine?.let { e -> com.yourapp.wine.kb.PlaceGazetteer(', spatial_src)
        # A failed evidence summary (budget cap) must fall back to the
        # base fill summary, never fail the scan.
        self.assertIn('spatialSummary["status"]!="failed") spatialSummary', runner)
        self.assertIn('evidence/spatial stage failed or exceeded its budget; base field fill used', runner)
        self.assertIn('values["evidence_extract_ms"]=result.elapsedMs.toString()',
            (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt').read_text())
        self.assertIn('if(deadlineAtMs==Long.MAX_VALUE) com.yourapp.wine.textlab.evidence.EvidenceExtractor.DEFAULT_BUDGET_MS else PRODUCTION_EVIDENCE_BUDGET_MS', runner)
        # Scan awaits only the KB+gazetteer core; extractor pre-build is
        # background, and warm-up starts at app launch.
        self.assertIn('private fun prebuildPostProcessors', warmup)
        self.assertIn('extractor.warmUp()', warmup)
        extractor_src = (ROOT / 'app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt').read_text()
        self.assertIn('internal fun warmUp()', extractor_src)
        self.assertIn('prepJob = scope.async', warmup)
        self.assertIn('ProductionLabelScanSession.warmUp(this)', app)

    def test_add_wine_shows_live_and_final_timer(self):
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        self.assertIn('val showScanTimer = ScanTimerSettings.isEnabled(context)', add)
        # Live ticking timer alongside the progress step.
        self.assertIn('DrawerScanProgress(labelScanState, showScanTimer)', add)
        self.assertIn('ScanTimerSettings.formatDuration(nowMs - startedMs)', add)
        # Final total comes from the report's whole-recognition elapsed time.
        self.assertIn('"Recognition took ${ScanTimerSettings.formatDuration(report.elapsedMs)}"', add)


if __name__ == '__main__':
    unittest.main()
