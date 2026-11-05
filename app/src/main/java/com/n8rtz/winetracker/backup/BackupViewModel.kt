package com.n8rtz.winetracker.backup

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.local.WineDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

data class RestoreEntry(val name: String, val destination: BackupDestinationId)

data class BackupUiState(
    val settings: BackupSettings = BackupSettings(),
    val statuses: Map<BackupDestinationId, BackupDestinationSnapshot> = emptyMap(),
    val health: Map<BackupDestinationId, BackupHealth> = emptyMap(),
    val statusLines: Map<BackupDestinationId, String> = emptyMap(),
    /** Short "Last confirmed <date/time>" line for the toggle menu; null when never confirmed. */
    val lastBackupLines: Map<BackupDestinationId, String?> = emptyMap(),
    val setupDetails: Map<BackupDestinationId, String> = emptyMap(),
    val needsSetup: Map<BackupDestinationId, Boolean> = emptyMap(),
    val protection: BackupProtection = BackupProtection.LOCAL_ONLY,
    val protectionLine: String = "",
    val warningBanner: String? = null,
    /** Popup shown on every app open while a backup remains failed. No system notification. */
    val failureDialog: String? = null,
    val isRunning: Boolean = false,
    /** Which destination a manual "Back up now" is currently writing to. */
    val runningDestination: BackupDestinationId? = null,
    val runMessage: String? = null,
    val overdueNotice: String? = null,
    val setupMessage: String? = null,
    val setupInProgress: Boolean = false,
    /** All snapshots across the enabled, configured destinations, newest first. */
    val restoreEntries: List<RestoreEntry> = emptyList(),
    val restoreLoading: Boolean = false,
    val restoreError: String? = null,
    val nowEpochMillis: Long = System.currentTimeMillis()
)

class BackupViewModel(application: Application, database: WineDatabase) : AndroidViewModel(application) {

    private val coordinator = BackupCoordinator(application.applicationContext, database)
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { refreshState() }
    }

    /**
     * Called from MainActivity.onResume. No background timer: an overdue backup starts right here,
     * on an app open. Repeated calls are harmless because the due check is idempotent.
     */
    fun onAppOpened() {
        viewModelScope.launch {
            val report = try {
                coordinator.runAutomaticCheck()
            } catch (error: Exception) {
                Log.e("WineTracker", "BackupViewModel: automatic due-check failed", error)
                _state.value = _state.value.copy(
                    runMessage = "Automatic backup check failed: ${error.message ?: "unknown error"}"
                )
                return@launch
            }
            refreshState()
            if (report != null) applyReport(report, overdue = true)
            showAppOpenFailureDialog()
        }
    }

    /**
     * Owner decision: a plain in-app popup on EVERY app open while a destination still has a
     * recorded failure. No POST_NOTIFICATIONS permission and no background notification channel.
     */
    private fun showAppOpenFailureDialog() {
        val current = _state.value
        val failures = current.statuses.filter { (id, snapshot) ->
            id in current.settings.enabledDestinations &&
                current.needsSetup[id] != true &&
                snapshot.lastError != null
        }
        if (failures.isEmpty()) return
        val text = "A backup failed and has not succeeded since:\n" +
            failures.entries.joinToString("\n") { (id, snapshot) ->
                "• ${id.displayName}: ${snapshot.lastError}"
            } +
            "\n\nOpen Backup & Restore to reconnect the destination or run Back up now."
        _state.value = current.copy(failureDialog = current.failureDialog ?: text)
    }

    fun dismissFailureDialog() {
        _state.value = _state.value.copy(failureDialog = null)
    }

    fun dismissSetupMessage() {
        _state.value = _state.value.copy(setupMessage = null)
    }

    /**
     * Manual backup. [target] null means every enabled destination; a concrete destination backs
     * up only that one, so the per-destination buttons on the Backup & Restore screen can select a
     * specific target. The coordinator's Mutex still serialises overlapping requests.
     */
    fun backUpNow(target: BackupDestinationId? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isRunning = true,
                runningDestination = target,
                runMessage = "Backing up…"
            )
            val report = try {
                coordinator.backUpNow(targets = target?.let(::listOf))
            } catch (error: Exception) {
                Log.e("WineTracker", "BackupViewModel: manual backup failed", error)
                _state.value = _state.value.copy(
                    isRunning = false,
                    runningDestination = null,
                    runMessage = "Backup failed: ${error.message ?: "unknown error"}"
                )
                return@launch
            }
            refreshState()
            _state.value = _state.value.copy(isRunning = false, runningDestination = null)
            applyReport(report, overdue = false)
        }
    }

    fun dismissNotices() {
        _state.value = _state.value.copy(runMessage = null, overdueNotice = null)
    }

    fun setAutomaticEnabled(enabled: Boolean) =
        persist { it.copy(automaticEnabled = enabled) }

    fun setPruneEnabled(enabled: Boolean) =
        persist { it.copy(pruneEnabled = enabled) }

    fun setInterval(count: Int, unit: BackupIntervalUnit) = persist {
        val safeCount = count.coerceIn(BackupInterval.MIN_COUNT, BackupInterval.MAX_COUNT)
        it.copy(interval = BackupInterval(safeCount, unit))
    }

    fun setKeepCount(count: Int) = persist {
        val safeCount = count.coerceIn(BackupSettings.MIN_KEEP_COUNT, BackupSettings.MAX_KEEP_COUNT)
        it.copy(keepCount = safeCount)
    }

    fun setDestinationEnabled(id: BackupDestinationId, enabled: Boolean) {
        persist { current ->
            val next = if (enabled) {
                current.enabledDestinations + id
            } else {
                current.enabledDestinations - id
            }
            current.copy(enabledDestinations = next)
        }
    }

    /** Reorders the priority list; the order decides upload and restore-offer order. */
    fun movePriority(id: BackupDestinationId, offset: Int) = persist { current ->
        current.copy(
            destinationPriority = BackupSchedule.moveInPriority(
                current.destinationPriority, id, offset
            )
        )
    }

    fun saveGitHubConfig(repository: String, token: String) {
        if (!BackupConfigRules.isValidGitHubRepository(repository)) {
            _state.value = _state.value.copy(
                setupMessage = "Enter the repository as owner/name. A full URL is not accepted."
            )
            return
        }
        if (token.isBlank()) {
            _state.value = _state.value.copy(
                setupMessage = "A fine-grained personal access token with Contents: read/write is required."
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(setupInProgress = true, setupMessage = null)
            coordinator.saveGitHubConfig(repository, token)
            val message = try {
                "GitHub ready: ${coordinator.testConnection(BackupDestinationId.GITHUB)}"
            } catch (error: Exception) {
                coordinator.clearConfig(BackupDestinationId.GITHUB)
                "GitHub setup failed: ${error.message ?: "unknown error"}"
            }
            refreshState()
            _state.value = _state.value.copy(setupInProgress = false, setupMessage = message)
        }
    }

    fun saveWebDavConfig(url: String, username: String, password: String) {
        if (!BackupConfigRules.isValidWebDavUrl(url)) {
            _state.value = _state.value.copy(
                setupMessage = "Enter an absolute http(s) URL with a host and no embedded password."
            )
            return
        }
        if (username.isBlank() || password.isEmpty()) {
            _state.value = _state.value.copy(
                setupMessage = "The share username and password are both required."
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(setupInProgress = true, setupMessage = null)
            coordinator.saveWebDavConfig(url, username, password)
            val message = try {
                "WebDAV ready: ${coordinator.testConnection(BackupDestinationId.WEBDAV)}"
            } catch (error: Exception) {
                coordinator.clearConfig(BackupDestinationId.WEBDAV)
                "WebDAV setup failed: ${error.message ?: "unknown error"}"
            }
            refreshState()
            _state.value = _state.value.copy(setupInProgress = false, setupMessage = message)
        }
    }

    fun onDriveFolderPicked(uri: Uri?) {
        // Backing out of the folder picker keeps the Drive folder that was already selected. Only a
        // confirmed, non-empty selection is written to the credential store.
        if (uri == null || uri.toString().isBlank()) {
            _state.value = _state.value.copy(setupInProgress = false, setupMessage = null)
            refresh()
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(setupInProgress = true, setupMessage = null)
            val message = try {
                GoogleDriveDestination.persistTreeUri(getApplication(), uri)
                "Drive ready: ${coordinator.testConnection(BackupDestinationId.GOOGLE_DRIVE)}"
            } catch (error: Exception) {
                "Drive setup failed: ${error.message ?: "unknown error"}"
            }
            refreshState()
            _state.value = _state.value.copy(setupInProgress = false, setupMessage = message)
        }
    }

    fun disconnect(id: BackupDestinationId) {
        coordinator.clearConfig(id)
        refresh()
    }

    /** Unified restore: list every snapshot at every enabled, configured destination at once. */
    fun loadRestoreBackups() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                restoreEntries = emptyList(),
                restoreLoading = true,
                restoreError = null
            )
            val settings = coordinator.loadSettings()
            val entries = mutableListOf<RestoreEntry>()
            val errors = mutableListOf<String>()
            for (id in settings.orderedEnabledDestinations()) {
                if (id != BackupDestinationId.LOCAL && !coordinator.setupState(id).configured) continue
                try {
                    for (name in coordinator.listBackups(id)) {
                        entries += RestoreEntry(name, id)
                    }
                } catch (error: Exception) {
                    errors += "${id.displayName}: ${error.message ?: "unknown error"}"
                }
            }
            refreshState()
            _state.value = _state.value.copy(
                restoreLoading = false,
                restoreEntries = entries.sortedByDescending { it.name },
                restoreError = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            )
        }
    }

    fun clearRestoreSelection() {
        _state.value = _state.value.copy(
            restoreEntries = emptyList(),
            restoreLoading = false,
            restoreError = null
        )
    }

    suspend fun restoreUri(id: BackupDestinationId, name: String): Uri =
        coordinator.restoreUri(id, name)

    private fun persist(change: (BackupSettings) -> BackupSettings) {
        val updated = try {
            change(_state.value.settings)
        } catch (error: IllegalArgumentException) {
            Log.w("WineTracker", "BackupViewModel: rejected settings change: ${error.message}")
            return
        }
        _state.value = _state.value.copy(settings = updated)
        coordinator.saveSettings(updated)
        refresh()
    }

    private fun applyReport(report: BackupRunReport, overdue: Boolean) {
        val results = if (report.results.isEmpty()) {
            "No backup destination is enabled."
        } else {
            report.results.joinToString(" · ") { result ->
                "${result.id.displayName}: " +
                    if (result.succeeded) "confirmed" else "failed — ${result.message}"
            }
        }
        // A missing/invalid setup is reported in the result line and the banner, but is not a
        // failed upload: the owner's popup is for backups that were attempted and failed.
        val failures = report.results.filter { !it.succeeded && !it.needsSetup }
        val dialog = if (failures.isEmpty()) {
            null
        } else {
            "The last backup did not complete for:\n" +
                failures.joinToString("\n") { "• ${it.id.displayName}: ${it.message}" } +
                "\n\nOpen Backup & Restore to reconnect the destination or run Back up now."
        }
        _state.value = _state.value.copy(
            runMessage = results,
            overdueNotice = if (overdue && report.overdueTriggered) OVERDUE_NOTICE else null,
            failureDialog = dialog
        )
    }

    private suspend fun refreshState() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val settings = coordinator.loadSettings()
        val statuses = BackupDestinationId.values().associateWith { coordinator.statusFor(it) }
        val setupStates = BackupDestinationId.values().associateWith { coordinator.setupState(it) }
        val needsSetup = setupStates.mapValues { (id, setup) ->
            !setup.configured && id in settings.enabledDestinations
        }
        val health = statuses.mapValues { (id, snapshot) ->
            BackupSchedule.health(
                now, snapshot, settings.interval, zone,
                needsSetup = needsSetup[id] == true
            )
        }
        val statusLines = statuses.mapValues { (_, snapshot) ->
            val last = formatTimestamp(snapshot.lastConfirmedEpochMillis)
            when (health.getValue(snapshot.id)) {
                BackupHealth.FAILED ->
                    "Needs attention: ${snapshot.lastError ?: "last attempt failed"} · last confirmed $last"
                BackupHealth.NEEDS_SETUP ->
                    "Needs setup/reconnect · ${setupStates.getValue(snapshot.id).detail}"
                BackupHealth.NEVER_BACKED_UP -> "No confirmed backup yet"
                BackupHealth.OVERDUE -> {
                    val days = BackupSchedule.daysOverdue(
                        now, snapshot.lastConfirmedEpochMillis, settings.interval, zone
                    )
                    val suffix = days?.let {
                        " by $it day${if (it == 1L) "" else "s"}"
                    } ?: ""
                    "Backup overdue$suffix · last confirmed $last"
                }
                BackupHealth.CURRENT -> "Last confirmed $last"
            }
        }
        val lastBackupLines = statuses.mapValues { (_, snapshot) ->
            snapshot.lastConfirmedEpochMillis?.let { "Last confirmed ${formatTimestamp(it)}" }
        }
        val confirmedOffDevice = statuses.values
            .filter { it.id.isOffDevice && it.lastConfirmedEpochMillis != null }
            .mapTo(mutableSetOf()) { it.id }
        val protection = BackupSchedule.protection(settings, confirmedOffDevice)
        val warnings = mutableListOf<String>()
        settings.orderedEnabledDestinations().forEach { id ->
            val snapshot = statuses.getValue(id)
            when {
                health[id] == BackupHealth.FAILED ->
                    warnings += "${id.displayName}: ${snapshot.lastError ?: "last backup failed"}"
                health[id] == BackupHealth.NEEDS_SETUP ->
                    warnings += "${id.displayName} needs setup or reconnection before backups can run"
                BackupSchedule.shouldWarnOverdue(
                    now, snapshot.lastConfirmedEpochMillis, settings.interval, zone
                ) -> {
                    val days = BackupSchedule.daysOverdue(
                        now, snapshot.lastConfirmedEpochMillis, settings.interval, zone
                    )
                    warnings += "${id.displayName}: no confirmed backup for $days day(s)"
                }
            }
        }
        val oldestConfirmed = settings.orderedEnabledDestinations()
            .mapNotNull { statuses[it]?.lastConfirmedEpochMillis }
            .minOrNull()
        if (BackupSchedule.shouldRemindManually(
                now, oldestConfirmed, settings.automaticEnabled
            )
        ) {
            warnings += "Manual backups are on and the last confirmed copy is over " +
                "${BackupSchedule.MANUAL_REMINDER_DAYS} days old. Run Back up now."
        }
        _state.value = _state.value.copy(
            settings = settings,
            statuses = statuses,
            health = health,
            statusLines = statusLines,
            lastBackupLines = lastBackupLines,
            setupDetails = setupStates.mapValues { it.value.detail },
            needsSetup = needsSetup,
            protection = protection,
            protectionLine = protectionLine(protection),
            warningBanner = warnings.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"),
            nowEpochMillis = now
        )
    }

    private fun formatTimestamp(epochMillis: Long?): String =
        epochMillis?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        } ?: "never"

    private fun protectionLine(protection: BackupProtection): String = when (protection) {
        BackupProtection.NONE -> "No backup destination enabled"
        BackupProtection.LOCAL_ONLY ->
            "Only a local copy exists — it does not protect against losing the phone"
        BackupProtection.OFF_DEVICE_ACTIVE -> "Off-device protection: active"
    }

    private companion object {
        const val OVERDUE_NOTICE =
            "Your last confirmed backup was older than the selected interval, " +
                "so a new backup started when the app opened."
    }
}
