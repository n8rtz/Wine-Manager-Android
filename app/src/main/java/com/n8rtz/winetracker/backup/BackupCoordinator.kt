package com.n8rtz.winetracker.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.n8rtz.winetracker.data.importer.JsonExporter
import com.n8rtz.winetracker.data.local.WineDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId

data class BackupDestinationResult(
    val id: BackupDestinationId,
    val succeeded: Boolean,
    val message: String
)

data class BackupRunReport(
    val fileName: String,
    val results: List<BackupDestinationResult>,
    val overdueTriggered: Boolean
) {
    val succeeded: Boolean get() = results.isNotEmpty() && results.all { it.succeeded }
}

/**
 * One pipeline: existing JSON exporter snapshot -> verify each destination independently -> prune.
 *
 * There is deliberately no WorkManager, AlarmManager, service or background polling. The app calls
 * [runAutomaticCheck] whenever it is opened or resumed; data can only change while the app is open.
 * A single [Mutex] keeps overlapping checks from producing duplicate snapshots.
 */
class BackupCoordinator(
    private val context: Context,
    private val database: WineDatabase
) {
    private val settingsStore = BackupSettingsStore(context)
    private val destinations: Map<BackupDestinationId, BackupDestination> = mapOf(
        BackupDestinationId.LOCAL to LocalBackupDestination(context)
    )
    private val runMutex = Mutex()

    fun loadSettings(): BackupSettings = settingsStore.load()

    fun saveSettings(settings: BackupSettings) = settingsStore.save(settings)

    fun statusFor(id: BackupDestinationId): BackupDestinationSnapshot = settingsStore.statusFor(id)

    suspend fun listBackups(id: BackupDestinationId = BackupDestinationId.LOCAL): List<String> =
        withContext(Dispatchers.IO) {
            destinations[id]?.list() ?: emptyList()
        }

    suspend fun restoreUri(name: String): Uri = withContext(Dispatchers.IO) {
        destinations[BackupDestinationId.LOCAL]?.download(name)
            ?: throw IOException("No local backup destination is available.")
    }

    /** Returns null when automatic backup is off or nothing is due. */
    suspend fun runAutomaticCheck(nowEpochMillis: Long = System.currentTimeMillis()): BackupRunReport? =
        runMutex.withLock {
            val settings = settingsStore.load()
            if (!settings.automaticEnabled) return@withLock null
            val zone = ZoneId.systemDefault()
            val due = settings.orderedEnabledDestinations().filter { id ->
                id.isImplemented && BackupSchedule.isDue(
                    nowEpochMillis,
                    settingsStore.statusFor(id).lastConfirmedEpochMillis,
                    settings.interval,
                    zone
                )
            }
            if (due.isEmpty()) return@withLock null
            Log.d(
                "WineTracker",
                "BackupCoordinator: overdue ${due.joinToString { it.storageKey }}; " +
                    "starting a backup on app open"
            )
            runBackupLocked(settings, due, nowEpochMillis, zone, overdueTriggered = true)
        }

    /** Manual "Back up now": every enabled implemented destination, regardless of the interval. */
    suspend fun backUpNow(nowEpochMillis: Long = System.currentTimeMillis()): BackupRunReport =
        runMutex.withLock {
            val settings = settingsStore.load()
            val zone = ZoneId.systemDefault()
            val targets = settings.orderedEnabledDestinations().filter { it.isImplemented }
            if (targets.isEmpty()) {
                Log.w("WineTracker", "BackupCoordinator: no implemented destination is enabled")
                return@withLock BackupRunReport(
                    BackupSnapshots.fileName(nowEpochMillis, zone),
                    emptyList(),
                    overdueTriggered = false
                )
            }
            runBackupLocked(settings, targets, nowEpochMillis, zone, overdueTriggered = false)
        }

    private suspend fun runBackupLocked(
        settings: BackupSettings,
        targets: List<BackupDestinationId>,
        nowEpochMillis: Long,
        zone: ZoneId,
        overdueTriggered: Boolean
    ): BackupRunReport = withContext(Dispatchers.IO) {
        val fileName = BackupSnapshots.fileName(nowEpochMillis, zone)
        val snapshot = try {
            val bytes = JsonExporter.exportDataToJson(context, database).readBytes()
            BackupSnapshot(fileName, bytes, sha256Hex(bytes))
        } catch (error: Exception) {
            val message = "Snapshot failed: ${error.message ?: error.javaClass.simpleName}"
            Log.e("WineTracker", "BackupCoordinator: snapshot failed", error)
            targets.forEach { settingsStore.recordFailure(it, message, nowEpochMillis) }
            return@withContext BackupRunReport(
                fileName,
                targets.map { BackupDestinationResult(it, false, message) },
                overdueTriggered
            )
        }

        val results = mutableListOf<BackupDestinationResult>()
        targets.forEach { id ->
            val destination = destinations[id]
            if (destination == null) {
                val message = "${id.displayName} has no upload implementation yet."
                Log.w("WineTracker", "BackupCoordinator: $message")
                settingsStore.recordFailure(id, message, nowEpochMillis)
                results += BackupDestinationResult(id, false, message)
                return@forEach
            }
            var uploadedName: String? = null
            try {
                val storedName = destination.upload(snapshot)
                uploadedName = storedName
                if (!destination.verify(storedName, snapshot.sha256)) {
                    throw IOException("Read-back verification failed for $storedName.")
                }
                if (settings.pruneEnabled) {
                    val names = (destination.list() + storedName).distinct()
                    val stale = BackupSnapshots.namesToPrune(names, settings.keepCount)
                    stale.forEach { name ->
                        val deletion = runCatching { destination.delete(name) }
                        deletion.exceptionOrNull()?.let { error ->
                            Log.w("WineTracker", "BackupCoordinator: could not prune $name", error)
                        }
                    }
                    if (stale.isNotEmpty()) {
                        Log.d(
                            "WineTracker",
                            "BackupCoordinator: pruned ${stale.size} old backup(s) at ${id.storageKey}"
                        )
                    }
                }
                settingsStore.recordSuccess(id, nowEpochMillis)
                results += BackupDestinationResult(id, true, "Confirmed $storedName")
                Log.d("WineTracker", "BackupCoordinator: ${id.storageKey} confirmed $storedName")
            } catch (error: Exception) {
                // An unverified copy must never be offered as a restore candidate.
                uploadedName?.let { name ->
                    runCatching { destination.delete(name) }
                        .exceptionOrNull()
                        ?.let { cleanupError ->
                            Log.w("WineTracker", "BackupCoordinator: could not remove $name", cleanupError)
                        }
                }
                val message = error.message ?: error.javaClass.simpleName
                settingsStore.recordFailure(id, message, nowEpochMillis)
                results += BackupDestinationResult(id, false, message)
                Log.e("WineTracker", "BackupCoordinator: ${id.storageKey} failed", error)
            }
        }
        BackupRunReport(fileName, results, overdueTriggered)
    }
}
