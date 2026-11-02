package com.niallbunting.assistantvoicerecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimingTest {
    @Test
    fun duration_is_the_elapsed_time_between_start_and_stop() {
        assertEquals(12_345L, RecordingTiming.durationMs(1_000L, 13_345L))
    }

    @Test
    fun duration_never_becomes_negative_when_the_wall_clock_moves_back() {
        assertEquals(0L, RecordingTiming.durationMs(13_345L, 1_000L))
    }

    @Test
    fun retention_cutoff_is_exactly_the_requested_number_of_days_ago() {
        val now = 40 * RecordingTiming.MILLIS_PER_DAY
        assertEquals(10 * RecordingTiming.MILLIS_PER_DAY, RecordingTiming.retentionCutoff(now, 30))
    }

    @Test
    fun retention_never_accepts_zero_or_negative_days() {
        val now = 10 * RecordingTiming.MILLIS_PER_DAY
        assertEquals(now - RecordingTiming.MILLIS_PER_DAY, RecordingTiming.retentionCutoff(now, 0))
        assertEquals(now - RecordingTiming.MILLIS_PER_DAY, RecordingTiming.retentionCutoff(now, -7))
    }
}
