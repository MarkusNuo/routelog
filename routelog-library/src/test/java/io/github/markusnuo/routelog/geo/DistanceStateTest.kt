package io.github.markusnuo.routelog.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceStateTest {
    private val metersPerDegreeLat = Geo.EARTH_MEAN_RADIUS_METERS * Math.PI / 180
    private val lat0 = 47.4245
    private val lon0 = 9.3767

    /** A fix [north] metres north of the start, [seconds] after it. */
    private fun fix(north: Double, seconds: Long, accuracy: Float? = 4f, speed: Float? = null) =
        Fix(lat0 + north / metersPerDegreeLat, lon0, accuracy, 1_000_000L + seconds * 1000, speed)

    private fun DistanceState.addAll(fixes: List<Fix>) = fixes.fold(this) { state, f -> state.add(f) }

    @Test
    fun firstFixOnlySetsTheAnchor() {
        val state = DistanceState().add(fix(0.0, 0))
        assertEquals(0.0, state.totalMeters, 0.0)
        assertEquals(fix(0.0, 0), state.anchor)
    }

    @Test
    fun drivingStraightAddsUp() {
        // 50 km/h is about 14 m per second, for 60 seconds.
        val fixes = (0..60).map { fix(it * 14.0, it.toLong()) }
        assertEquals(840.0, DistanceState().addAll(fixes).totalMeters, 0.5)
    }

    @Test
    fun jitterWhileStandingStillIsIgnored() {
        val fixes = (0..120).map { fix(if (it % 2 == 0) 2.0 else -2.0, it.toLong(), accuracy = 5f) }
        assertEquals(0.0, DistanceState().addAll(fixes).totalMeters, 0.0)
    }

    @Test
    fun slowManoeuvringIsStillCounted() {
        // 1 m per second, below the per-step threshold; measured from the anchor it still counts.
        // Only the stretch since the last anchor, shorter than the threshold, is not counted yet.
        val fixes = (0..30).map { fix(it * 1.0, it.toLong(), accuracy = 3f) }
        val total = DistanceState().addAll(fixes).totalMeters
        assertTrue("total was $total", total > 30.0 - DistanceState.MIN_STEP_METERS && total <= 30.0 + 1e-6)
    }

    @Test
    fun inaccurateFixesAreSkipped() {
        val start = DistanceState().add(fix(0.0, 0))
        val after = start.add(fix(30.0, 2, accuracy = 80f))
        assertSame(start, after)
    }

    @Test
    fun fixWithoutAccuracyStillCounts() {
        val state = DistanceState().add(fix(0.0, 0, accuracy = null)).add(fix(20.0, 2, accuracy = null))
        assertEquals(20.0, state.totalMeters, 0.01)
    }

    @Test
    fun impossibleJumpIsNotCountedAndDoesNotBlockLaterFixes() {
        val fixes = listOf(
            fix(0.0, 0),
            fix(10.0, 1),
            fix(5_000.0, 2), // glitch: 5 km in one second
            fix(24.0, 3),
            fix(38.0, 4),
        )
        // Counted: 0→10 and 24→38. The steps into and out of the glitch are dropped.
        assertEquals(24.0, DistanceState().addAll(fixes).totalMeters, 0.5)
    }

    @Test
    fun timeGoingBackwardsIsNotCounted() {
        val state = DistanceState().add(fix(0.0, 10)).add(fix(20.0, 9))
        assertEquals(0.0, state.totalMeters, 0.0)
        assertEquals(fix(20.0, 9), state.anchor)
    }

    @Test
    fun resumedRecordingKeepsTotalButDoesNotBridgeTheGap() {
        val before = DistanceState().addAll(listOf(fix(0.0, 0), fix(100.0, 7)))
        val resumed = DistanceState(totalMeters = before.totalMeters)
        val after = resumed.addAll(listOf(fix(2_000.0, 600), fix(2_050.0, 604)))
        assertEquals(150.0, after.totalMeters, 0.5)
    }

    @Test
    fun poorAccuracyRaisesTheThreshold() {
        // With ±20 m, a 15 m step may be noise and is not counted yet.
        val state = DistanceState().add(fix(0.0, 0, accuracy = 20f)).add(fix(15.0, 1, accuracy = 20f))
        assertEquals(0.0, state.totalMeters, 0.0)
        val further = state.add(fix(25.0, 2, accuracy = 20f))
        assertEquals(25.0, further.totalMeters, 0.5)
    }

    @Test
    fun outlierAfterALongStopIsNotCountedThereOrBack() {
        // 14 m driven, then a minute standing (jitter only), then one fix 300 m away and back.
        // Measured against the old anchor the outlier would look like a slow drive of 5 m/s.
        val standing = (2..61).map { fix(14.0 + if (it % 2 == 0) 1.0 else -1.0, it.toLong()) }
        val fixes = listOf(fix(0.0, 0), fix(14.0, 1)) + standing + listOf(
            fix(314.0, 62), // 300 m in one second
            fix(14.0, 63), // back where the car really is
            fix(28.0, 64),
        )
        // 14 m before the stop and 14 m after it, nothing for the outlier.
        assertEquals(28.0, DistanceState().addAll(fixes).totalMeters, 0.5)
    }

    @Test
    fun aBadFirstFixDoesNotBlockTheDistance() {
        val fixes = listOf(fix(5_000.0, 0), fix(0.0, 1), fix(14.0, 2), fix(28.0, 3))
        assertEquals(28.0, DistanceState().addAll(fixes).totalMeters, 0.5)
    }

    @Test
    fun jumpWhileStandingStillIsNotCountedWhenTheGpsSaysSpeedZero() {
        // The drive shows that this GPS reports speed, so its "speed 0" can be trusted from then on.
        val drive = listOf(fix(0.0, 0, speed = 12f), fix(14.0, 1, speed = 12f))
        // A minute standing at 14 m, then 40 m in one second (possible for a car) and back, all
        // with speed 0.
        val standing = (2..61).map { fix(14.0, it.toLong(), speed = 0f) }
        val jump = listOf(fix(54.0, 62, speed = 0f), fix(14.0, 63, speed = 0f))
        val state = DistanceState().addAll(drive + standing + jump)
        assertEquals(14.0, state.totalMeters, 0.5)
        assertEquals(fix(14.0, 1, speed = 12f), state.anchor)
    }

    @Test
    fun theSameJumpIsCountedWhenTheGpsReportsMovement() {
        val withSpeed = DistanceState().addAll(listOf(fix(0.0, 0, speed = 12f), fix(40.0, 3, speed = 12f)))
        assertEquals(40.0, withSpeed.totalMeters, 0.5)
        val withoutSpeed = DistanceState().addAll(listOf(fix(0.0, 0), fix(40.0, 3)))
        assertEquals(40.0, withoutSpeed.totalMeters, 0.5)
    }

    @Test
    fun creepingBelowTheSpeedLimitIsCountedOnceMovementIsClear() {
        // After a drive, the GPS says "not moving" while the car creeps. The anchor stays, so the
        // distance isn't lost.
        val drive = listOf(fix(0.0, 0, speed = 10f), fix(14.0, 1, speed = 10f))
        val creeping = listOf(fix(17.0, 11, speed = 0.3f), fix(20.0, 21, speed = 0.3f), fix(23.0, 31, speed = 0.3f))
        val creeped = DistanceState().addAll(drive + creeping)
        assertEquals(14.0, creeped.totalMeters, 0.5)
        assertEquals(34.0, creeped.add(fix(34.0, 35, speed = 3f)).totalMeters, 0.5)
    }

    @Test
    fun aGpsThatAlwaysReportsSpeedZeroStillGetsItsDistanceCounted() {
        // Some receivers report speed 0 for every fix (the emulator's does) while the position
        // moves 14 m per second. Trusting that speed would show 0 km for the whole drive.
        val state = DistanceState().addAll((0..60).map { fix(it * 14.0, it.toLong(), speed = 0f) })
        assertEquals(840.0, state.totalMeters, 0.5)
        assertFalse(state.speedTrusted)
    }

    @Test
    fun speedZeroIsOnlyTrustedOnceARealSpeedWasSeen() {
        // Without a real speed, a 40 m jump with speed 0 is counted like any other movement.
        val zeroOnly = DistanceState().addAll(listOf(fix(0.0, 0, speed = 0f), fix(40.0, 3, speed = 0f)))
        assertEquals(40.0, zeroOnly.totalMeters, 0.5)
        assertFalse(zeroOnly.speedTrusted)

        val trusted = DistanceState().addAll(listOf(fix(0.0, 0, speed = 0f), fix(14.0, 1, speed = 12f)))
        assertTrue(trusted.speedTrusted)
        // Once trusted, it stays so, and the 40 m jump with speed 0 is not counted.
        val later = trusted.addAll(listOf(fix(14.0, 5, speed = 0f), fix(54.0, 6, speed = 0f)))
        assertTrue(later.speedTrusted)
        assertEquals(14.0, later.totalMeters, 0.5)
    }
}
