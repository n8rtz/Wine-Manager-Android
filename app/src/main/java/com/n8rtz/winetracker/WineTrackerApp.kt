package com.n8rtz.winetracker

import android.app.Application
import com.n8rtz.winetracker.scanner.ProductionLabelScanSession
import com.n8rtz.winetracker.scanner.isBusy
import com.yourapp.wine.gliner.GlinerModelPrewarm

/**
 * Application entry point. Only schedules the delayed GLINER model pre-warm; nothing runs
 * blocking here, so startup timing is unchanged.
 */
class WineTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlinerModelPrewarm.schedule(this) { ProductionLabelScanSession.state.value.isBusy }
        // Start the first-scan warm-up (ONNX session, KB/gazetteer,
        // OCR sessions, evidence extractor) at app launch: most users
        // scan once per session, so the work should already be done —
        // not merely started — when they open Add Wine. All async on
        // background threads; nothing blocks startup.
        ProductionLabelScanSession.warmUp(this)
    }
}
