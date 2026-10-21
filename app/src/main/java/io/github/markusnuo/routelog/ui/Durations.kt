package io.github.markusnuo.routelog.ui

data class DurationParts(val hours: Long, val minutes: Long, val seconds: Long)

object Durations {
    fun split(millis: Long): DurationParts {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        return DurationParts(totalSeconds / 3600, totalSeconds % 3600 / 60, totalSeconds % 60)
    }

    /** Running clock for the recorder card, e.g. 1:02:03 or 4:05. */
    fun clock(millis: Long): String {
        val (h, m, s) = split(millis)
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
