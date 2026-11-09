package com.n8rtz.winetracker.backup

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The four destinations the Backup & Restore screen knows about; all four can upload. */
enum class BackupDestinationId(
    val storageKey: String,
    val displayName: String,
    val isOffDevice: Boolean,
    val isImplemented: Boolean
) {
    LOCAL("local", "Local folder", false, true),
    GOOGLE_DRIVE("google_drive", "Google Drive", true, true),
    GITHUB("github", "GitHub", true, true),
    WEBDAV("webdav", "Local server (WebDAV)", true, true);

    companion object {
        fun fromStorageKey(key: String): BackupDestinationId? =
            values().firstOrNull { it.storageKey == key }
    }
}

enum class BackupIntervalUnit { DAYS, WEEKS, MONTHS }

data class BackupInterval(val count: Int, val unit: BackupIntervalUnit) {
    init {
        require(count in MIN_COUNT..MAX_COUNT) {
            "Interval count must be between $MIN_COUNT and $MAX_COUNT."
        }
    }

    companion object {
        const val MIN_COUNT = 1
        const val MAX_COUNT = 365
    }
}

data class BackupSettings(
    val automaticEnabled: Boolean = true,
    val interval: BackupInterval = BackupInterval(1, BackupIntervalUnit.MONTHS),
    val enabledDestinations: Set<BackupDestinationId> = setOf(BackupDestinationId.LOCAL),
    val destinationPriority: List<BackupDestinationId> = DEFAULT_PRIORITY,
    val pruneEnabled: Boolean = true,
    val keepCount: Int = DEFAULT_KEEP_COUNT
) {
    init {
        require(keepCount in MIN_KEEP_COUNT..MAX_KEEP_COUNT) {
            "Keep count must be between $MIN_KEEP_COUNT and $MAX_KEEP_COUNT."
        }
        val all = BackupDestinationId.values().toList()
        require(destinationPriority.size == all.size && destinationPriority.containsAll(all)) {
            "Destination priority must contain every destination exactly once."
        }
    }

    /** Enabled destinations in the owner's priority order. */
    fun orderedEnabledDestinations(): List<BackupDestinationId> =
        destinationPriority.filter { it in enabledDestinations }

    companion object {
        const val MIN_KEEP_COUNT = 1
        const val MAX_KEEP_COUNT = 50
        const val DEFAULT_KEEP_COUNT = 5
        val DEFAULT_PRIORITY: List<BackupDestinationId> = BackupDestinationId.values().toList()
    }
}

enum class BackupHealth { NEVER_BACKED_UP, OVERDUE, FAILED, NEEDS_SETUP, CURRENT }

enum class BackupProtection { NONE, LOCAL_ONLY, OFF_DEVICE_ACTIVE }

data class BackupDestinationSnapshot(
    val id: BackupDestinationId,
    val lastConfirmedEpochMillis: Long? = null,
    val lastAttemptEpochMillis: Long? = null,
    val lastError: String? = null
)

/**
 * Pure, JVM-testable scheduling rules. There is no background timer: the app records the last
 * confirmed backup and calls [isDue] whenever it is opened, then starts a backup on the spot.
 */
object BackupSchedule {

    /** A destination that is due but has not completed for this many days raises a "no backup" warning. */
    const val OVERDUE_WARNING_DAYS = 3L

    /** In manual mode, remind when the last confirmed copy is at least this old. */
    const val MANUAL_REMINDER_DAYS = 30L

    /** Calendar-aware deadline: months clamp to month ends, weeks/days follow the device time zone. */
    fun nextDueEpochMillis(lastSuccessEpochMillis: Long, interval: BackupInterval, zone: ZoneId): Long {
        val last = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastSuccessEpochMillis), zone)
        val due = when (interval.unit) {
            BackupIntervalUnit.DAYS -> last.plusDays(interval.count.toLong())
            BackupIntervalUnit.WEEKS -> last.plusWeeks(interval.count.toLong())
            BackupIntervalUnit.MONTHS -> last.plusMonths(interval.count.toLong())
        }
        return due.toInstant().toEpochMilli()
    }

    /**
     * True when a backup has never been confirmed, when the last confirmed copy is older than the
     * interval, or when the clock moved backwards (a future last-success time counts as due).
     */
    fun isDue(
        nowEpochMillis: Long,
        lastSuccessEpochMillis: Long?,
        interval: BackupInterval,
        zone: ZoneId
    ): Boolean {
        val last = lastSuccessEpochMillis ?: return true
        if (last > nowEpochMillis) return true
        return nowEpochMillis >= nextDueEpochMillis(last, interval, zone)
    }

    /** Whole calendar days past the due date; null when not due yet or never backed up. */
    fun daysOverdue(
        nowEpochMillis: Long,
        lastSuccessEpochMillis: Long?,
        interval: BackupInterval,
        zone: ZoneId
    ): Long? {
        val last = lastSuccessEpochMillis ?: return null
        if (!isDue(nowEpochMillis, last, interval, zone)) return null
        if (last > nowEpochMillis) return 0L
        val dueDate = Instant.ofEpochMilli(nextDueEpochMillis(last, interval, zone))
            .atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(dueDate, today).coerceAtLeast(0L)
    }

    /** Due for at least [thresholdDays] calendar days without a confirmed copy. */
    fun shouldWarnOverdue(
        nowEpochMillis: Long,
        lastSuccessEpochMillis: Long?,
        interval: BackupInterval,
        zone: ZoneId,
        thresholdDays: Long = OVERDUE_WARNING_DAYS
    ): Boolean {
        val days = daysOverdue(nowEpochMillis, lastSuccessEpochMillis, interval, zone)
            ?: return false
        return days >= thresholdDays
    }

    /** Manual mode: gentle reminder when nothing has been confirmed for [reminderDays] days. */
    fun shouldRemindManually(
        nowEpochMillis: Long,
        lastSuccessEpochMillis: Long?,
        automaticEnabled: Boolean,
        reminderDays: Long = MANUAL_REMINDER_DAYS
    ): Boolean {
        if (automaticEnabled) return false
        val last = lastSuccessEpochMillis ?: return true
        if (last > nowEpochMillis) return false
        val elapsedDays = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(last),
            Instant.ofEpochMilli(nowEpochMillis)
        )
        return elapsedDays >= reminderDays
    }

    fun health(
        nowEpochMillis: Long,
        snapshot: BackupDestinationSnapshot,
        interval: BackupInterval,
        zone: ZoneId,
        needsSetup: Boolean = false
    ): BackupHealth = when {
        snapshot.lastError != null -> BackupHealth.FAILED
        needsSetup -> BackupHealth.NEEDS_SETUP
        snapshot.lastConfirmedEpochMillis == null -> BackupHealth.NEVER_BACKED_UP
        isDue(nowEpochMillis, snapshot.lastConfirmedEpochMillis, interval, zone) -> BackupHealth.OVERDUE
        else -> BackupHealth.CURRENT
    }

    /**
     * Moves [id] one slot towards the front (offset -1) or the back (offset +1) of the priority
     * list. Every destination appears exactly once in the result; out-of-range moves are no-ops.
     */
    fun moveInPriority(
        priority: List<BackupDestinationId>,
        id: BackupDestinationId,
        offset: Int
    ): List<BackupDestinationId> {
        val current = priority.indexOf(id)
        if (current < 0 || offset == 0) return priority
        val target = current + offset
        if (target !in priority.indices) return priority
        return priority.toMutableList().also { list ->
            java.util.Collections.swap(list, current, target)
        }
    }

    /**
     * A local copy is never protection. An off-device destination only counts once a backup has
     * actually been confirmed there, so the summary cannot claim protection from a bare toggle.
     */
    fun protection(
        settings: BackupSettings,
        confirmedOffDeviceIds: Set<BackupDestinationId>
    ): BackupProtection {
        val enabled = settings.enabledDestinations
        if (enabled.isEmpty()) return BackupProtection.NONE
        val offDeviceEnabled = enabled.any { it.isOffDevice }
        val offDeviceConfirmed = enabled.any { it.isOffDevice && it in confirmedOffDeviceIds }
        return if (offDeviceEnabled && offDeviceConfirmed) {
            BackupProtection.OFF_DEVICE_ACTIVE
        } else {
            BackupProtection.LOCAL_ONLY
        }
    }
}

/** Snapshot naming, parsing and the keep-newest-N retention rule. */
object BackupSnapshots {
    const val PREFIX = "WineTracker_"
    const val SUFFIX = ".json"

    private val NAME_PATTERN = Regex("^WineTracker_(\\d{4}-\\d{2}-\\d{2}_\\d{6})\\.json$")
    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("uuuu-MM-dd_HHmmss", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    fun fileName(epochMillis: Long, zone: ZoneId): String =
        PREFIX + TIMESTAMP_FORMAT.format(
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        ) + SUFFIX

    fun isAppBackup(name: String): Boolean = NAME_PATTERN.matches(name)

    /** Returns null for foreign files and for malformed timestamps (e.g. month 13). */
    fun epochMillisOf(name: String, zone: ZoneId): Long? {
        val match = NAME_PATTERN.matchEntire(name) ?: return null
        return try {
            LocalDateTime.parse(match.groupValues[1], TIMESTAMP_FORMAT)
                .atZone(zone).toInstant().toEpochMilli()
        } catch (error: DateTimeParseException) {
            null
        }
    }

    /** Fixed-width timestamps sort chronologically as strings; foreign names are dropped. */
    fun newestFirst(names: Iterable<String>): List<String> = names
        .filter { isAppBackup(it) }
        .distinct()
        .sortedWith(compareByDescending<String> { it })

    /**
     * Oldest app-owned names beyond the newest [keepCount]. The newest copy is never returned
     * because [keepCount] is always at least one. Nothing outside the app's naming pattern is ever
     * eligible for deletion.
     */
    fun namesToPrune(names: Iterable<String>, keepCount: Int): List<String> {
        require(keepCount >= 1) { "At least one backup must be kept." }
        val ordered = newestFirst(names)
        return if (ordered.size <= keepCount) emptyList() else ordered.drop(keepCount)
    }
}
