package com.yourapp.wine

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanStage
import com.yourapp.wine.fill.UiState
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

/** Import error/cancel integration only: never starts the native OCR engine or a wine-network lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SelectedPhotoViewModelTest {
    private fun fixture(): Triple<ScannerViewModel, ViewModelStore, WineDatabase> {
        val app = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(app, WineDatabase::class.java).allowMainThreadQueries().build()
        val repository = WineRepository(db.wineDao(), db.inventoryDao(), db.tastingNoteDao(), db.ratingDao(), db)
        val vm = ScannerViewModel(app, repository)
        val store = ViewModelStore().apply { put("scanner", vm) }
        return Triple(vm, store, db)
    }

    @Test fun unreadableSelectedPhotoProducesCopyableRedReviewWithImportFailure() = runBlocking {
        val (vm, store, db) = fixture()
        try {
            val uri = Uri.parse("content://missing.scanner.provider/image")
            assertTrue(vm.onLabelPhotoSelected(uri))
            assertFalse(vm.onLabelPhotoSelected(uri))
            val review = withTimeout(5000) { vm.labelScanState.filterIsInstance<LabelScanState.Review>().first() }
            assertEquals(ScanInputSource.PHOTO_LIBRARY, review.result.diagnostics.source)
            assertEquals(ScanStage.IMPORT, review.result.diagnostics.lastStage)
            assertTrue(review.result.diagnostics.failures.isNotEmpty())
            assertTrue(review.result.fill.fields.values.all { it.state == UiState.RED && it.value.isBlank() })
            assertTrue(review.result.tokens.isEmpty())
            vm.dismissLabelScan(review.requestId)
            assertEquals(LabelScanState.Idle, vm.labelScanState.value)
        } finally { store.clear(); db.close() }
    }

    @Test fun cancellingASelectedPhotoDoesNotLeaveTheScannerBusy() = runBlocking {
        val (vm, store, db) = fixture()
        try {
            assertTrue(vm.onLabelPhotoSelected(Uri.parse("content://missing.scanner.provider/image")))
            vm.cancelActiveLabelScan()
            withTimeout(5000) { vm.labelScanState.first { it == LabelScanState.Idle } }
            assertEquals(LabelScanState.Idle, vm.labelScanState.value)
        } finally { store.clear(); db.close() }
    }
}
