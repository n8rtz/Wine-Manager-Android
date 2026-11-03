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

data class BackupUiState(
    val settings: BackupSettings = BackupSettings(),
    val statuses: Map<BackupDestinationId, BackupDestinationSnapshot> = emptyMap(),
    val health: Map<BackupDestinationId, BackupHealth> = emptyMap(),
    val statusLines: Map<BackupDestinationId, String> = emptyMap(),
    val availableBackups: List<String> = emptyList(),
    val protection: BackupProtection = BackupProtection.LOCAL_ONLY,
    val protectionLine: String = "",
    val isRunning: Boolean = false,
    val runMessage: String? = null,
    val overdueNotice: String? = null,
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
        }
    }

    fun backUpNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true, runMessage = "Backing up…")
            val report = try {
                coordinator.backUpNow()
            } catch (error: Exception) {
                Log.e("WineTracker", "BackupViewModel: manual backup failed", error)
                _state.value = _state.value.copy(
                    isRunning = false,
                    runMessage = "Backup failed: ${error.message ?: "unknown error"}"
                )
                return@launch
            }
            refreshState()
            _state.value = _state.value.copy(isRunning = false)
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
        if (!id.isImplemented) {
            Log.w("WineTracker", "BackupViewModel: ${id.displayName} has no upload implementation yet")
            return
        }
        persist { current ->
            val next = if (enabled) {
                current.enabledDestinations + id
            } else {
                current.enabledDestinations - id
            }
            current.copy(enabledDestinations = next)
        }
    }

    suspend fun restoreUri(name: String): Uri = coordinator.restoreUri(name)

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
        _state.value = _state.value.copy(
            runMessage = results,
            overdueNotice = if (overdue && report.overdueTriggered) OVERDUE_NOTICE else null
        )
    }

    private suspend fun refreshState() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val settings = coordinator.loadSettings()
        val statuses = BackupDestinationId.values().associateWith { coordinator.statusFor(it) }
        val health = statuses.mapValues { (_, snapshot) ->
            BackupSchedule.health(now, snapshot, settings.interval, zone)
        }
        val statusLines = statuses.mapValues { (_, snapshot) ->
            val last = formatTimestamp(snapshot.lastConfirmedEpochMillis)
            when (health.getValue(snapshot.id)) {
                BackupHealth.FAILED ->
                    "Needs attention: ${snapshot.lastError ?: "last attempt failed"} · last confirmed $last"
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
        val confirmedOffDevice = statuses.values
            .filter { it.id.isOffDevice && it.lastConfirmedEpochMillis != null }
            .mapTo(mutableSetOf()) { it.id }
        val protection = BackupSchedule.protection(settings, confirmedOffDevice)
        val backups = try {
            BackupSnapshots.newestFirst(coordinator.listBackups())
        } catch (error: Exception) {
            Log.w("WineTracker", "BackupViewModel: could not list local backups", error)
            emptyList()
        }
        _state.value = _state.value.copy(
            settings = settings,
            statuses = statuses,
            health = health,
            statusLines = statusLines,
            availableBackups = backups,
            protection = protection,
            protectionLine = protectionLine(protection),
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
