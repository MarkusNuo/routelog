package io.github.markusnuo.routelog.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsTest {
    @Test
    fun splitsIntoHoursMinutesSeconds() =
        assertEquals(DurationParts(1, 32, 5), Durations.split((3600 + 32 * 60 + 5) * 1000L + 999))

    @Test
    fun negativeDurationIsZero() = assertEquals(DurationParts(0, 0, 0), Durations.split(-5_000))

    @Test
    fun clockWithoutHours() = assertEquals("4:05", Durations.clock(245_000))

    @Test
    fun clockWithHours() = assertEquals("1:02:03", Durations.clock(3_723_000))
}
