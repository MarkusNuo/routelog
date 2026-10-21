package io.github.markusnuo.routelog.geo

import kotlin.math.max

data class Fix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timeMillis: Long,
    /** Speed over ground as the GPS reports it; null if the device doesn't say. */
    val speedMetersPerSecond: Float? = null,
)

/**
 * Driven distance of a recording. Only affects the displayed distance: every raw fix is stored
 * and exported regardless.
 *
 * GPS positions jitter by a few metres even when the car stands still, so distance is measured
 * from an anchor fix and only added once the car has clearly moved away from it. Measuring from
 * the anchor (not from the previous fix) means slow movement is still counted in full.
 *
 * Plausibility is checked against the last fix as well as the anchor: after a long stop the anchor
 * is old, and a single wild fix would look like a slow, legitimate drive when compared to it.
 *
 * The reported speed is only used once it has been seen to work: some GPS receivers (the emulator's
 * among them) report "speed 0" for every fix, and trusting that would hide the whole drive.
 */
data class DistanceState(
    val totalMeters: Double = 0.0,
    val anchor: Fix? = null,
    /** The last fix that was not discarded for bad accuracy. */
    val last: Fix? = null,
    /** A fix with a real speed was seen, so this GPS's "speed 0" means standing still. */
    val speedTrusted: Boolean = false,
) {
    fun add(fix: Fix): DistanceState {
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > MAX_ACCURACY_METERS) return this
        val speed = fix.speedMetersPerSecond
        val trusted = speedTrusted || (speed != null && speed >= MIN_MOVING_SPEED_METERS_PER_SECOND)
        return advance(fix, accuracy, trusted).copy(speedTrusted = trusted)
    }

    private fun advance(fix: Fix, accuracy: Float?, trusted: Boolean): DistanceState {
        val anchor = anchor
        val last = last
        if (anchor == null || last == null) return copy(anchor = fix, last = fix)

        // A jump no car can make is a GPS glitch on one side. Move on without counting it, so a
        // single bad fix, before or after, can't block the distance for the rest of the recording.
        if (!plausible(last, fix) || !plausible(anchor, fix)) return copy(anchor = fix, last = fix)

        // Standing still: once the GPS has shown that its speed works, it is far more reliable than
        // position. Keep the anchor, so movement that is too slow to report speed is still counted
        // once it is clear.
        val speed = fix.speedMetersPerSecond
        if (trusted && speed != null && speed < MIN_MOVING_SPEED_METERS_PER_SECOND) return copy(last = fix)

        val meters = Geo.distanceMeters(anchor.latitude, anchor.longitude, fix.latitude, fix.longitude)
        val threshold = max(MIN_STEP_METERS, max(anchor.accuracyMeters ?: 0f, accuracy ?: 0f).toDouble())
        if (meters < threshold) return copy(last = fix)
        return DistanceState(totalMeters + meters, fix, fix)
    }

    private fun plausible(from: Fix, to: Fix): Boolean {
        val seconds = (to.timeMillis - from.timeMillis) / 1000.0
        if (seconds <= 0.0) return false
        val meters = Geo.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        return meters / seconds <= MAX_SPEED_METERS_PER_SECOND
    }

    companion object {
        const val MAX_ACCURACY_METERS = 50f
        const val MIN_STEP_METERS = 5.0
        const val MAX_SPEED_METERS_PER_SECOND = 70.0
        const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.5f
    }
}
