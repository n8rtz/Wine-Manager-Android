package com.yourapp.wine.capture

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.ocr.MlKitOcrEngine
import com.yourapp.wine.ocr.OcrEngine
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.textlab.PaddleMediumOcrEngine

/** App-scoped clients/DB/native gate, never reconstructed for each capture. */
object WineScannerServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Shared by built-in engines and the debug-only line recognizer; late native tasks retain their lease.
    internal val nativeGate = NativeEngineGate()
    @Volatile private var pending: Deferred<OfflineWineScanner>? = null

    /** No coordinates: cancellably awaits app-scoped initialization; native/client construction stays on Default. */
    suspend fun get(context: Context): OfflineWineScanner {
        val app = context.applicationContext
        val job = synchronized(this) {
            // A failed async is cancelled: do not permanently poison every later retake with its exception.
            pending?.takeUnless { it.isCancelled } ?: scope.async {
                RetiredModelCleanup.remove(app.noBackupFilesDir)
                val gate = nativeGate
                // Model weights have a separate, explicitly relaxed budget; retain the KB's own cap.
                val kb = SqliteWineKnowledgeBase.open(app, 0)
                val engines = mutableListOf<OcrEngine>()
                try {
                    engines += MlKitOcrEngine(gate)
                    engines += PaddleMediumOcrEngine.create(app, gate)
                    OfflineWineScanner(kb, engines.toList())
                } catch (error: Throwable) {
                    engines.asReversed().forEach { engine -> runCatching { engine.close() }.onFailure(error::addSuppressed) }
                    runCatching { kb.close() }.onFailure(error::addSuppressed)
                    throw error
                }
            }.also { pending = it }
        }
        return job.await()
    }
}
