package com.n8rtz.winetracker.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pure JVM coverage for the W-057 schedule, snapshot naming, retention and status rules. */
class BackupScheduleTest {

    private val zone = ZoneId.of("America/New_York")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 21,
        minute: Int = 0,
        second: Int = 0
    ): Long = ZonedDateTime.of(
        LocalDateTime.of(year, month, day, hour, minute, second),
        zone
    ).toInstant().toEpochMilli()

    @Test
    fun neverBackedUpIsDue() {
        assertTrue(
            BackupSchedule.isDue(at(2026, 11, 3), null, BackupInterval(1, BackupIntervalUnit.MONTHS), zone)
        )
        assertNull(
            BackupSchedule.daysOverdue(
                at(2026, 11, 3), null, BackupInterval(1, BackupIntervalUnit.MONTHS), zone
            )
        )
    }

    @Test
    fun intervalIsTheMaximumAgeNotAFixedClockTime() {
        val interval = BackupInterval(1, BackupIntervalUnit.MONTHS)
        val last = at(2026, 1, 1)
        assertFalse(BackupSchedule.isDue(at(2026, 1, 31, 23, 59), last, interval, zone))
        assertFalse(BackupSchedule.isDue(at(2026, 2, 1, 20, 59), last, interval, zone))
        assertTrue(BackupSchedule.isDue(at(2026, 2, 1, 21, 0), last, interval, zone))
        assertTrue(BackupSchedule.isDue(at(2026, 3, 1, 0, 0), last, interval, zone))
        assertNull(BackupSchedule.daysOverdue(at(2026, 2, 1, 20, 59), last, interval, zone))
        assertEquals(2L, BackupSchedule.daysOverdue(at(2026, 2, 3, 21, 0), last, interval, zone))
    }

    @Test
    fun twoMonthsPlusTwoDaysIsDueWithTwoDaysOverdue() {
        val interval = BackupInterval(2, BackupIntervalUnit.MONTHS)
        val last = at(2026, 1, 1)
        assertFalse(BackupSchedule.isDue(at(2026, 2, 28), last, interval, zone))
        assertTrue(BackupSchedule.isDue(at(2026, 3, 3), last, interval, zone))
        assertEquals(2L, BackupSchedule.daysOverdue(at(2026, 3, 3), last, interval, zone))
    }

    @Test
    fun monthsClampToMonthEndAndLeapYears() {
        val interval = BackupInterval(1, BackupIntervalUnit.MONTHS)
        assertEquals(
            at(2026, 2, 28),
            BackupSchedule.nextDueEpochMillis(at(2026, 1, 31), interval, zone)
        )
        assertEquals(
            at(2024, 2, 29),
            BackupSchedule.nextDueEpochMillis(at(2024, 1, 31), interval, zone)
        )
    }

    @Test
    fun weeksUseCalendarDaysAcrossDaylightSaving() {
        val interval = BackupInterval(1, BackupIntervalUnit.WEEKS)
        // 2026-03-08 is the spring-forward date in America/New_York.
        assertEquals(
            at(2026, 3, 14, 21, 0),
            BackupSchedule.nextDueEpochMillis(at(2026, 3, 7, 21, 0), interval, zone)
        )
        assertTrue(
            at(2026, 3, 14, 21, 0) - at(2026, 3, 7, 21, 0) != 7L * 24 * 60 * 60 * 1000
        )
    }

    @Test
    fun daysUseTheDeviceZone() {
        val interval = BackupInterval(3, BackupIntervalUnit.DAYS)
        assertEquals(
            at(2026, 11, 6, 21, 0),
            BackupSchedule.nextDueEpochMillis(at(2026, 11, 3, 21, 0), interval, zone)
        )
    }

    @Test
    fun futureLastSuccessCountsAsDue() {
        val interval = BackupInterval(1, BackupIntervalUnit.DAYS)
        assertTrue(BackupSchedule.isDue(at(2026, 11, 3), at(2026, 11, 4), interval, zone))
        assertEquals(0L, BackupSchedule.daysOverdue(at(2026, 11, 3), at(2026, 11, 4), interval, zone))
    }

    @Test
    fun healthReflectsConfirmationFailureAndOverdue() {
        val interval = BackupInterval(1, BackupIntervalUnit.MONTHS)
        val now = at(2026, 3, 3)
        val local = BackupDestinationId.LOCAL
        assertEquals(
            BackupHealth.NEVER_BACKED_UP,
            BackupSchedule.health(now, BackupDestinationSnapshot(local), interval, zone)
        )
        assertEquals(
            BackupHealth.CURRENT,
            BackupSchedule.health(
                now, BackupDestinationSnapshot(local, lastConfirmedEpochMillis = at(2026, 3, 1)), interval, zone
            )
        )
        assertEquals(
            BackupHealth.OVERDUE,
            BackupSchedule.health(
                now, BackupDestinationSnapshot(local, lastConfirmedEpochMillis = at(2026, 1, 1)), interval, zone
            )
        )
        assertEquals(
            BackupHealth.FAILED,
            BackupSchedule.health(
                now,
                BackupDestinationSnapshot(
                    local,
                    lastConfirmedEpochMillis = at(2026, 3, 1),
                    lastError = "verification mismatch"
                ),
                interval,
                zone
            )
        )
    }

    @Test
    fun protectionNeedsAConfirmedOffDeviceCopy() {
        val empty = BackupSettings(enabledDestinations = emptySet())
        assertEquals(BackupProtection.NONE, BackupSchedule.protection(empty, emptySet()))
        val localOnly = BackupSettings(enabledDestinations = setOf(BackupDestinationId.LOCAL))
        assertEquals(BackupProtection.LOCAL_ONLY, BackupSchedule.protection(localOnly, emptySet()))
        val withToggle = BackupSettings(
            enabledDestinations = setOf(BackupDestinationId.LOCAL, BackupDestinationId.GITHUB)
        )
        assertEquals(BackupProtection.LOCAL_ONLY, BackupSchedule.protection(withToggle, emptySet()))
        assertEquals(
            BackupProtection.OFF_DEVICE_ACTIVE,
            BackupSchedule.protection(withToggle, setOf(BackupDestinationId.GITHUB))
        )
    }

    @Test
    fun settingsValidateAndHonourPriority() {
        try {
            BackupSettings(keepCount = 0)
            fail("keepCount 0 must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("Keep count"))
        }
        try {
            BackupSettings(destinationPriority = listOf(BackupDestinationId.LOCAL))
            fail("a partial priority list must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("exactly once"))
        }
        val settings = BackupSettings(
            enabledDestinations = setOf(BackupDestinationId.LOCAL, BackupDestinationId.WEBDAV),
            destinationPriority = listOf(
                BackupDestinationId.WEBDAV,
                BackupDestinationId.LOCAL,
                BackupDestinationId.GITHUB,
                BackupDestinationId.GOOGLE_DRIVE
            )
        )
        assertEquals(
            listOf(BackupDestinationId.WEBDAV, BackupDestinationId.LOCAL),
            settings.orderedEnabledDestinations()
        )
    }

    @Test
    fun snapshotNamesRoundTripAndRejectForeignFiles() {
        val now = at(2026, 11, 3, 21, 17, 30)
        val name = BackupSnapshots.fileName(now, zone)
        assertEquals("WineTracker_2026-11-03_211730.json", name)
        assertEquals(now, BackupSnapshots.epochMillisOf(name, zone))
        assertTrue(BackupSnapshots.isAppBackup(name))
        assertFalse(BackupSnapshots.isAppBackup("WineTracker_Backup.json"))
        assertFalse(BackupSnapshots.isAppBackup("notes.json"))
        assertFalse(BackupSnapshots.isAppBackup("WineTracker_2026-11-03_211730.json.enc"))
        assertNull(BackupSnapshots.epochMillisOf("WineTracker_2026-13-03_211730.json", zone))
    }

    @Test
    fun retentionKeepsNewestAndNeverTouchesForeignFiles() {
        val names = listOf(
            "WineTracker_2026-01-01_090000.json",
            "WineTracker_2026-03-01_090000.json",
            "WineTracker_2026-02-01_090000.json",
            "WineTracker_2026-04-01_090000.json",
            "someone-else.json",
            "WineTracker_Old.json"
        )
        val ordered = BackupSnapshots.newestFirst(names)
        assertEquals(
            listOf(
                "WineTracker_2026-04-01_090000.json",
                "WineTracker_2026-03-01_090000.json",
                "WineTracker_2026-02-01_090000.json",
                "WineTracker_2026-01-01_090000.json"
            ),
            ordered
        )
        assertEquals(
            listOf(
                "WineTracker_2026-02-01_090000.json",
                "WineTracker_2026-01-01_090000.json"
            ),
            BackupSnapshots.namesToPrune(names, keepCount = 2)
        )
        assertTrue(BackupSnapshots.namesToPrune(names, keepCount = 5).isEmpty())
        assertEquals(3, BackupSnapshots.namesToPrune(names, keepCount = 1).size)
        assertFalse(
            BackupSnapshots.namesToPrune(names, keepCount = 1)
                .contains("WineTracker_2026-04-01_090000.json")
        )
        try {
            BackupSnapshots.namesToPrune(names, keepCount = 0)
            fail("keepCount 0 must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("At least one"))
        }
    }

    @Test
    fun everyDestinationIsImplemented() {
        assertTrue(BackupDestinationId.values().all { it.isImplemented })
        assertTrue(BackupDestinationId.values().count { it.isOffDevice } == 3)
    }

    @Test
    fun priorityMovesAreSafe() {
        val original = BackupSettings.DEFAULT_PRIORITY
        val movedUp = BackupSchedule.moveInPriority(original, BackupDestinationId.WEBDAV, -1)
        assertEquals(
            listOf(
                BackupDestinationId.LOCAL,
                BackupDestinationId.GOOGLE_DRIVE,
                BackupDestinationId.WEBDAV,
                BackupDestinationId.GITHUB
            ),
            movedUp
        )
        assertEquals(original.size, movedUp.distinct().size)
        assertEquals(original, BackupSchedule.moveInPriority(original, BackupDestinationId.LOCAL, -1))
        assertEquals(original, BackupSchedule.moveInPriority(original, BackupDestinationId.WEBDAV, 1))
        assertEquals(original, BackupSchedule.moveInPriority(original, BackupDestinationId.LOCAL, 0))
    }

    @Test
    fun setupAndFailureHealth() {
        val interval = BackupInterval(1, BackupIntervalUnit.MONTHS)
        val now = at(2026, 3, 3)
        val local = BackupDestinationId.LOCAL
        assertEquals(
            BackupHealth.NEEDS_SETUP,
            BackupSchedule.health(now, BackupDestinationSnapshot(local), interval, zone, needsSetup = true)
        )
        // A recorded failure must not be hidden behind a missing setup.
        assertEquals(
            BackupHealth.FAILED,
            BackupSchedule.health(
                now,
                BackupDestinationSnapshot(local, lastError = "token expired"),
                interval,
                zone,
                needsSetup = true
            )
        )
    }

    @Test
    fun overdueWarningStartsAfterThreeDays() {
        val interval = BackupInterval(1, BackupIntervalUnit.MONTHS)
        val last = at(2026, 1, 1)
        assertFalse(BackupSchedule.shouldWarnOverdue(at(2026, 2, 2), last, interval, zone))
        assertTrue(BackupSchedule.shouldWarnOverdue(at(2026, 2, 4), last, interval, zone))
        // Never backed up is due but has no "no backup for N days" figure.
        assertFalse(BackupSchedule.shouldWarnOverdue(at(2026, 3, 3), null, interval, zone))
    }

    @Test
    fun manualReminderOnlyInManualModeAfterThirtyDays() {
        val now = at(2026, 3, 3)
        assertTrue(BackupSchedule.shouldRemindManually(now, null, automaticEnabled = false))
        assertFalse(BackupSchedule.shouldRemindManually(now, null, automaticEnabled = true))
        assertFalse(
            BackupSchedule.shouldRemindManually(now, at(2026, 2, 10), automaticEnabled = false)
        )
        assertTrue(
            BackupSchedule.shouldRemindManually(now, at(2026, 1, 1), automaticEnabled = false)
        )
        assertFalse(
            BackupSchedule.shouldRemindManually(now, at(2026, 1, 1), automaticEnabled = true)
        )
    }
}
