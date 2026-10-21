package io.github.markusnuo.routelog.testing

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.geo.Geo
import io.github.markusnuo.routelog.ui.MainActivity
import org.junit.rules.ExternalResource
import java.util.concurrent.TimeUnit

/**
 * A fake GPS for instrumented tests. It takes the place of the device's GPS provider while the
 * test runs and hands out fixes on request, so the real [io.github.markusnuo.routelog.recording.Recorder]
 * can be driven through the real [LocationManager].
 *
 * The test runs inside the app's process, so the app's own permissions apply: location access and
 * "mock location app" are switched on for it through the shell, as the user would in the
 * developer options.
 *
 * Android only delivers location to an app that is visible or runs a location foreground service
 * (location is a "while in use" permission). The instrumentation process has neither, so the
 * rule keeps the app's main screen open for the duration of the test, which is how the app is
 * used when the user starts a recording. A test that wants the app out of sight at the start
 * (a tap on a notification brings it up) passes `keepAppVisible = false`.
 */
class MockGps(private val context: Context, private val keepAppVisible: Boolean = true) : ExternalResource() {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var pushed = 0
    private var visibleApp: ActivityScenario<MainActivity>? = null

    // The timeline of the fixes, laid out at the first push: 1.2 s apart (the recorder asks for one
    // per second and Android drops fixes that arrive faster than that), in the past, so a burst of
    // fixes needs no waiting.
    private var firstElapsedNanos = 0L
    private var firstTimeMillis = 0L

    // addTestProvider with Criteria constants is deprecated from Android 12, but works on every
    // version this app supports (the replacement needs API 31).
    @Suppress("DEPRECATION")
    override fun before() {
        val pkg = context.packageName
        shell("pm grant $pkg android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant $pkg android.permission.ACCESS_COARSE_LOCATION")
        shell("appops set $pkg android:mock_location allow")
        removeProvider() // left over from a run that was killed
        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, true, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
        )
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        if (keepAppVisible) visibleApp = ActivityScenario.launch(MainActivity::class.java)
    }

    override fun after() {
        visibleApp?.close()
        removeProvider()
    }

    /**
     * Delivers a fix [northMeters] north of the starting point, one step (1.2 s) after the previous
     * one. Returns it, so the test can compare what was stored.
     */
    fun push(northMeters: Double, speed: Float? = STEP_SPEED, accuracy: Float = ACCURACY): Location {
        val index = pushed++
        if (index == 0) startTimeline()
        val elapsedNanos = firstElapsedNanos + TimeUnit.MILLISECONDS.toNanos(index * STEP_MILLIS)
        // Android rejects a fix whose realtime lies in the future. The room since boot normally
        // holds hundreds of steps; on a device that has only just started, wait for the clock.
        while (SystemClock.elapsedRealtimeNanos() < elapsedNanos) Thread.sleep(10)
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = LATITUDE + northMeters / METERS_PER_DEGREE
            longitude = LONGITUDE
            this.accuracy = accuracy
            speed?.let { this.speed = it }
            time = firstTimeMillis + index * STEP_MILLIS
            elapsedRealtimeNanos = elapsedNanos
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        return location
    }

    /**
     * Starts up to 10 minutes back, but not before one second after boot: Android requires the
     * realtime of a fix to be positive, and the device may have been up for only a short time.
     * The UTC time of the first fix is set to match.
     */
    private fun startTimeline() {
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        firstElapsedNanos = maxOf(nowElapsedNanos - TimeUnit.MINUTES.toNanos(10), TimeUnit.SECONDS.toNanos(1))
        firstTimeMillis = System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(nowElapsedNanos - firstElapsedNanos)
    }

    private fun removeProvider() {
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: IllegalArgumentException) {
            // No test provider there.
        }
    }

    companion object {
        const val STEP_MILLIS = 1_200L
        const val STEP_SPEED = 14f
        const val ACCURACY = 5f
        const val LATITUDE = 47.4245
        const val LONGITUDE = 9.3767
        const val METERS_PER_DEGREE = Geo.EARTH_MEAN_RADIUS_METERS * Math.PI / 180

        /** Metres between two fixes of a steady drive: 14 m/s for 1.2 s. */
        const val STEP_METERS = STEP_SPEED * STEP_MILLIS / 1000.0

        /** Runs a shell command as the shell user and returns what it printed. */
        fun shell(command: String): String {
            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
            return ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
                it.readBytes().decodeToString()
            }
        }
    }
}
