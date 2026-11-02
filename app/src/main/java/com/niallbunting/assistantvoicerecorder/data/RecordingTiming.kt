package com.niallbunting.assistantvoicerecorder.data

/** Time calculations kept independent of Android APIs so their boundary behaviour is testable. */
object RecordingTiming {
    const val MILLIS_PER_DAY = 86_400_000L

    fun durationMs(startedAt: Long, finishedAt: Long): Long = (finishedAt - startedAt).coerceAtLeast(0L)

    fun retentionCutoff(now: Long, retentionDays: Int): Long =
        now - retentionDays.coerceAtLeast(1).toLong() * MILLIS_PER_DAY
}
