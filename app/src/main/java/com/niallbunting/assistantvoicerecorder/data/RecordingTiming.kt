package com.niallbunting.assistantvoicerecorder.data

/** Time calculations kept independent of Android APIs so their boundary behaviour is testable. */
object RecordingTiming {
    const val MILLIS_PER_DAY = 86_400_000L

    fun durationMs(startedAt: Long, finishedAt: Long): Long = (finishedAt - startedAt).coerceAtLeast(0L)

    fun retentionCutoff(now: Long, retentionDays: Int): Long =
        now - retentionDays.coerceAtLeast(1).toLong() * MILLIS_PER_DAY

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
