package com.yourapp.wine

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.scanner.ProductionLabelScanSession
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.PendingLabelScanStore
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import com.yourapp.wine.capture.ScanReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Exercises session ownership with a controlled scanner; no claim about real device power policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LabelScanBackgroundLifecycleTest {
    @Test fun processingOutlivesViewModelAndPersistsResult() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(app, WineDatabase::class.java).allowMainThreadQueries().build()
        val repo = WineRepository(db.wineDao(), db.inventoryDao(), db.tastingNoteDao(), db.ratingDao(), db)
        val vm = ScannerViewModel(app, repo)
        val viewModels = ViewModelStore().apply { put("scanner", vm) }
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val file = File.createTempFile("wine-background-test-", ".jpg", app.cacheDir).apply { writeText("synthetic") }
        ProductionLabelScanSession.scannerForTesting = { _, _, _, _ ->
            started.complete(Unit)
            resume.await()
            ScanReport.red("test result")
        }
        try {
            assertTrue(vm.requestLabelScan())
            val id = (vm.labelScanState.value as LabelScanState.CapturingPhoto).requestId
            vm.onLabelPhotoCaptured(id, file)
            withTimeout(5000) { started.await() }
            vm.cancelPendingLabelCapture() // Equivalent to the screen's ON_STOP/disposal callback.
            viewModels.clear() // The Activity-owned ViewModel is no longer alive.
            assertTrue(ProductionLabelScanSession.state.value is LabelScanState.Processing)
            resume.complete(Unit)
            val review = withTimeout(5000) { ProductionLabelScanSession.state.filterIsInstance<LabelScanState.Review>().first() }
            assertEquals(id, review.requestId)
            assertEquals(id, PendingLabelScanStore(app).result()?.requestId)
            assertFalse(file.exists())
            ProductionLabelScanSession.dismiss(app, id)
            assertNull(PendingLabelScanStore(app).result())
        } finally {
            ProductionLabelScanSession.scannerForTesting = null
            ProductionLabelScanSession.cancelActive(app)
            resume.complete(Unit)
            file.delete()
            viewModels.clear()
            db.close()
        }
    }
}
