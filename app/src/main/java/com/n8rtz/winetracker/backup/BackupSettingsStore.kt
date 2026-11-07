package com.n8rtz.winetracker.backup

import android.content.Context
import android.util.Log

/**
 * SharedPreferences-backed settings and per-destination status. No credentials or backup bytes are
 * stored here. Defaults follow the owner's W-057 decision: automatic, every 1 month, Local on,
 * keep newest 5.
 */
class BackupSettingsStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    fun load(): BackupSettings {
        val storedCount = preferences.getInt(KEY_INTERVAL_COUNT, 1)
        val storedUnitName = preferences.getString(KEY_INTERVAL_UNIT, BackupIntervalUnit.MONTHS.name)
        val unit = BackupIntervalUnit.values().firstOrNull { it.name == storedUnitName }
        if (unit == null) {
            Log.w("WineTracker", "BackupSettingsStore: unknown interval unit '$storedUnitName'; using MONTHS")
        }
        val resolvedUnit = unit ?: BackupIntervalUnit.MONTHS
        val interval = if (storedCount in BackupInterval.MIN_COUNT..BackupInterval.MAX_COUNT) {
            BackupInterval(storedCount, resolvedUnit)
        } else {
            Log.w("WineTracker", "BackupSettingsStore: out-of-range interval count $storedCount; using 1")
            BackupInterval(1, resolvedUnit)
        }

        val enabled = parseDestinations(
            preferences.getString(KEY_ENABLED, BackupDestinationId.LOCAL.storageKey)
        )
        val priority = sanitizePriority(preferences.getString(KEY_PRIORITY, null))
        val storedKeepCount = preferences.getInt(KEY_KEEP_COUNT, BackupSettings.DEFAULT_KEEP_COUNT)
        val keepCount = if (storedKeepCount in BackupSettings.MIN_KEEP_COUNT..BackupSettings.MAX_KEEP_COUNT) {
            storedKeepCount
        } else {
            Log.w("WineTracker", "BackupSettingsStore: out-of-range keep count $storedKeepCount; using 5")
            BackupSettings.DEFAULT_KEEP_COUNT
        }

        return BackupSettings(
            automaticEnabled = preferences.getBoolean(KEY_AUTOMATIC, true),
            interval = interval,
            enabledDestinations = enabled,
            destinationPriority = priority,
            pruneEnabled = preferences.getBoolean(KEY_PRUNE, true),
            keepCount = keepCount
        )
    }

    fun save(settings: BackupSettings) {
        preferences.edit()
            .putBoolean(KEY_AUTOMATIC, settings.automaticEnabled)
            .putInt(KEY_INTERVAL_COUNT, settings.interval.count)
            .putString(KEY_INTERVAL_UNIT, settings.interval.unit.name)
            .putString(
                KEY_ENABLED,
                settings.enabledDestinations.joinToString(",") { it.storageKey }
            )
            .putString(
                KEY_PRIORITY,
                settings.destinationPriority.joinToString(",") { it.storageKey }
            )
            .putBoolean(KEY_PRUNE, settings.pruneEnabled)
            .putInt(KEY_KEEP_COUNT, settings.keepCount)
            .apply()
        Log.d(
            "WineTracker",
            "BackupSettingsStore: automatic=${settings.automaticEnabled}, " +
                "interval=${settings.interval.count} ${settings.interval.unit}, " +
                "enabled=${settings.enabledDestinations.joinToString { it.storageKey }}, " +
                "prune=${settings.pruneEnabled}, keep=${settings.keepCount}"
        )
    }

    fun statusFor(id: BackupDestinationId): BackupDestinationSnapshot {
        val prefix = statusPrefix(id)
        return BackupDestinationSnapshot(
            id = id,
            lastConfirmedEpochMillis = preferences
                .getLong("${prefix}last_confirmed", NO_TIMESTAMP)
                .takeIf { it != NO_TIMESTAMP },
            lastAttemptEpochMillis = preferences
                .getLong("${prefix}last_attempt", NO_TIMESTAMP)
                .takeIf { it != NO_TIMESTAMP },
            lastError = preferences.getString("${prefix}last_error", null)
        )
    }

    fun recordSuccess(id: BackupDestinationId, epochMillis: Long) {
        val prefix = statusPrefix(id)
        preferences.edit()
            .putLong("${prefix}last_confirmed", epochMillis)
            .putLong("${prefix}last_attempt", epochMillis)
            .remove("${prefix}last_error")
            .apply()
        Log.d("WineTracker", "BackupSettingsStore: confirmed ${id.storageKey} at $epochMillis")
    }

    fun recordFailure(id: BackupDestinationId, message: String, epochMillis: Long) {
        val prefix = statusPrefix(id)
        preferences.edit()
            .putLong("${prefix}last_attempt", epochMillis)
            .putString("${prefix}last_error", message)
            .apply()
        Log.w("WineTracker", "BackupSettingsStore: ${id.storageKey} failed at $epochMillis: $message")
    }

    fun clearFailure(id: BackupDestinationId) {
        preferences.edit().remove("${statusPrefix(id)}last_error").apply()
    }

    private fun statusPrefix(id: BackupDestinationId): String =
        "$KEY_STATUS_PREFIX${id.storageKey}_"

    private fun parseDestinations(raw: String?): Set<BackupDestinationId> {
        if (raw.isNullOrBlank()) return setOf(BackupDestinationId.LOCAL)
        val keys = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val parsed = keys.mapNotNull { key ->
            BackupDestinationId.fromStorageKey(key).also {
                if (it == null) {
                    Log.w("WineTracker", "BackupSettingsStore: ignoring unknown destination key '$key'")
                }
            }
        }.toSet()
        // An empty or unparseable stored value must never silently leave the app with no destination.
        return parsed.ifEmpty {
            Log.w("WineTracker", "BackupSettingsStore: no valid destination stored; using Local")
            setOf(BackupDestinationId.LOCAL)
        }
    }

    private fun sanitizePriority(raw: String?): List<BackupDestinationId> {
        val known = BackupDestinationId.values().toList()
        if (raw.isNullOrBlank()) return known
        val parsed = raw.split(',')
            .mapNotNull { key -> BackupDestinationId.fromStorageKey(key.trim()) }
            .distinct()
        return parsed + known.filterNot { it in parsed }
    }

    private companion object {
        const val PREFERENCES_FILE = "backup_settings"
        const val KEY_AUTOMATIC = "automatic_enabled"
        const val KEY_INTERVAL_COUNT = "interval_count"
        const val KEY_INTERVAL_UNIT = "interval_unit"
        const val KEY_ENABLED = "enabled_destinations"
        const val KEY_PRIORITY = "destination_priority"
        const val KEY_PRUNE = "prune_enabled"
        const val KEY_KEEP_COUNT = "keep_count"
        const val KEY_STATUS_PREFIX = "status_"
        const val NO_TIMESTAMP = -1L
    }
}
