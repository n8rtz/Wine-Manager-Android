package com.yourapp.wine

import com.yourapp.wine.phonecorpus.WineAppLabelsTimers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WineAppLabelsTimersTest {

    @Test
    fun formatsElapsedRunTimeInMmSsAndAccumulatesAcrossResume() {
        assertEquals("00m 00s", WineAppLabelsTimers.formatElapsedMmSs(0L))
        assertEquals("00m 00s", WineAppLabelsTimers.formatElapsedMmSs(999L))
        assertEquals("00m 01s", WineAppLabelsTimers.formatElapsedMmSs(1_000L))
        assertEquals("04m 18s", WineAppLabelsTimers.formatElapsedMmSs(258_400L))
        assertEquals("65m 02s", WineAppLabelsTimers.formatElapsedMmSs(3_902_000L))

        // Paused after 2m 00s (120_000 ms), idle: stays at 120_000 ms.
        assertEquals(120_000L, WineAppLabelsTimers.liveElapsedMs(120_000L, null, 999_999L))

        // Resumed at 500_000 ms, sampled 15_400 ms later -> 2m 15s (135_400 ms).
        val resumed = WineAppLabelsTimers.liveElapsedMs(120_000L, 500_000L, 515_400L)
        assertEquals(135_400L, resumed)
        assertEquals("02m 15s", WineAppLabelsTimers.formatElapsedMmSs(resumed))
    }

    @Test
    fun formatsAverageImageSeconds() {
        assertNull(WineAppLabelsTimers.averageImageMs(0L, 0))
        assertEquals("--s", WineAppLabelsTimers.formatAverageSeconds(null))

        val avg = WineAppLabelsTimers.averageImageMs(34_200L, 10)
        assertEquals(3_420L, avg)
        assertEquals("3.4s", WineAppLabelsTimers.formatAverageSeconds(avg))
    }

    @Test
    fun computesSmoothCountdownAndClampsLongSinglePhotoOverrun() {
        // Before 1st photo finishes -> unknown countdown.
        assertNull(WineAppLabelsTimers.estimatedRemainingMs(125, null, 1_000L, 2_500L))
        assertEquals("--m --s", WineAppLabelsTimers.formatCountdownMmSs(null))

        // 10 photos remaining (1 in flight + 9 queued), pace = 5_000 ms/photo.
        // At t = 0s into current photo: 50_000 ms -> 00m 50s.
        val t0 = WineAppLabelsTimers.estimatedRemainingMs(10, 5_000L, 10_000L, 10_000L)
        assertEquals(50_000L, t0)
        assertEquals("00m 50s", WineAppLabelsTimers.formatCountdownMmSs(t0))

        // At t = 2.2s into current photo: 2_800 + 45_000 = 47_800 ms -> ceiling 48s -> 00m 48s.
        val t2 = WineAppLabelsTimers.estimatedRemainingMs(10, 5_000L, 10_000L, 12_200L)
        assertEquals(47_800L, t2)
        assertEquals("00m 48s", WineAppLabelsTimers.formatCountdownMmSs(t2))

        // If current photo runs slower than average (7s vs 5s), current photo clamps at 0 ms
        // so countdown holds at the 9 queued photos (45_000 ms -> 00m 45s) and never goes negative.
        val overrun = WineAppLabelsTimers.estimatedRemainingMs(10, 5_000L, 10_000L, 17_000L)
        assertEquals(45_000L, overrun)
        assertEquals("00m 45s", WineAppLabelsTimers.formatCountdownMmSs(overrun))

        // Completed run (0 remaining) -> 00m 00s even if no pace was stored.
        val done = WineAppLabelsTimers.estimatedRemainingMs(0, null, null, 20_000L)
        assertEquals(0L, done)
        assertEquals("00m 00s", WineAppLabelsTimers.formatCountdownMmSs(done))
    }
}
