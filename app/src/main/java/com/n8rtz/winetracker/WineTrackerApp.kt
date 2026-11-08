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
    }
}
