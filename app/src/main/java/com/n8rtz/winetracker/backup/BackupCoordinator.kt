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
    val message: String,
    /** A missing/invalid setup is surfaced for reconnection, not counted as a failed upload. */
    val needsSetup: Boolean = false
)

data class BackupSetupState(val configured: Boolean, val detail: String)

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
    private val credentials = BackupCredentialStore(context)
    private val runMutex = Mutex()

    /** Built fresh for each run so a newly saved setup is used without restarting the app. */
    private fun destinationFor(id: BackupDestinationId): BackupDestination = when (id) {
        BackupDestinationId.LOCAL -> LocalBackupDestination(context)
        BackupDestinationId.GOOGLE_DRIVE -> GoogleDriveDestination(context, credentials)
        BackupDestinationId.GITHUB -> GitHubDestination(context, credentials)
        BackupDestinationId.WEBDAV -> WebDavDestination(context, credentials)
    }

    fun loadSettings(): BackupSettings = settingsStore.load()

    fun saveSettings(settings: BackupSettings) = settingsStore.save(settings)

    fun statusFor(id: BackupDestinationId): BackupDestinationSnapshot = settingsStore.statusFor(id)

    /** Does not touch the network: only reads the locally saved setup. */
    suspend fun setupState(id: BackupDestinationId): BackupSetupState = withContext(Dispatchers.IO) {
        val result = runCatching { destinationFor(id).isConfigured() }
        val error = result.exceptionOrNull()
        when {
            error != null -> BackupSetupState(false, error.message ?: "Reconnect this destination.")
            result.getOrNull() == true -> BackupSetupState(true, configurationSummary(id))
            else -> BackupSetupState(false, "Set up ${id.displayName} before backups can run.")
        }
    }

    /** Non-secret description of the saved setup, safe to show in the UI. */
    fun configurationSummary(id: BackupDestinationId): String = when (id) {
        BackupDestinationId.LOCAL -> "Documents/WineManager/"
        BackupDestinationId.GOOGLE_DRIVE -> "Drive folder selected"
        BackupDestinationId.GITHUB -> runCatching { credentials.githubRepository() }
            .getOrDefault("not configured")
        BackupDestinationId.WEBDAV -> runCatching { credentials.webDavUrl() }
            .getOrDefault("not configured")
    }

    suspend fun testConnection(id: BackupDestinationId): String =
        destinationFor(id).testConnection()

    fun saveGitHubConfig(repository: String, token: String) =
        credentials.saveGitHub(repository, token)

    fun saveWebDavConfig(url: String, username: String, password: String) =
        credentials.saveWebDav(url, username, password)

    fun saveDriveTreeUri(treeUri: Uri) = credentials.saveDriveTreeUri(treeUri.toString())

    fun clearConfig(id: BackupDestinationId) {
        credentials.clear(id)
        settingsStore.clearFailure(id)
    }

    suspend fun listBackups(id: BackupDestinationId): List<String> = withContext(Dispatchers.IO) {
        BackupSnapshots.newestFirst(destinationFor(id).list())
    }

    suspend fun restoreUri(id: BackupDestinationId, name: String): Uri = withContext(Dispatchers.IO) {
        destinationFor(id).download(name)
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
            // A destination with no valid setup is reported for reconnection, but is NOT recorded
            // as a failed upload: the app-open failure popup is for backup attempts that failed.
            val destination = try {
                val candidate = destinationFor(id)
                if (!candidate.isConfigured()) {
                    val message = "${id.displayName} needs setup or reconnection: ${setupState(id).detail}"
                    Log.w("WineTracker", "BackupCoordinator: $message")
                    settingsStore.clearFailure(id)
                    results += BackupDestinationResult(id, false, message, needsSetup = true)
                    return@forEach
                }
                candidate
            } catch (error: Exception) {
                val message = "${id.displayName} cannot be used: " +
                    (error.message ?: error.javaClass.simpleName)
                Log.w("WineTracker", "BackupCoordinator: $message", error)
                settingsStore.clearFailure(id)
                results += BackupDestinationResult(id, false, message, needsSetup = true)
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
