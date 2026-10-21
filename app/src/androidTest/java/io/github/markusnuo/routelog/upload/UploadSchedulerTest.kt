package io.github.markusnuo.routelog.upload

import io.github.markusnuo.routelog.RoutelogConfig
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.testing.MockGps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The real JobScheduler, with a recording id no real recording has. */
@RunWith(AndroidJUnit4::class)
class UploadSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = UploadScheduler(context, RoutelogConfig.legacy())
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @After
    fun cleanUp() {
        scheduler.cancelUpload(ID)
        scheduler.cancelExpiry(ID)
    }

    private fun expiryJob() = jobs.allPendingJobs.single { it.extras.getLong(UploadScheduler.EXTRA_RECORDING_ID) == ID && it.extras.getInt(UploadScheduler.EXTRA_KIND) == UploadScheduler.KIND_EXPIRY }

    @Test
    @Suppress("DEPRECATION") // getNetworkType works on every version this app supports
    fun anUploadJobWaitsForAnyNetworkAndSurvivesARestart() {
        // Without a network the job must stay pending. With one, Android 17 may run it (and the
        // service finish it, as no recording has this id) before the test can look at it.
        withoutNetwork {
            scheduler.scheduleUpload(ID)

            assertTrue(scheduler.isUploadScheduled(ID))
            val job = jobs.allPendingJobs.single { it.extras.getInt(UploadScheduler.EXTRA_KIND) == UploadScheduler.KIND_UPLOAD && it.extras.getLong(UploadScheduler.EXTRA_RECORDING_ID) == ID }
            assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
            assertTrue(job.isPersisted)

            SystemClock.sleep(2_000)
            assertTrue("no network: still waiting", scheduler.isUploadScheduled(ID))

            scheduler.cancelUpload(ID)
            assertFalse(scheduler.isUploadScheduled(ID))
        }
    }

    private fun withoutNetwork(block: () -> Unit) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // The phone is left as it was: airplane mode may have been on already.
        val wasEnabled = airplaneModeEnabled()
        MockGps.shell("cmd connectivity airplane-mode enable")
        var failure: Throwable? = null
        try {
            check(airplaneModeEnabled()) { "Airplane mode did not turn on. $NEEDS_AIRPLANE_MODE" }
            waitFor("network gone after airplane mode was turned on. $NEEDS_AIRPLANE_MODE") { connectivity.activeNetwork == null }
            block()
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // A failure to restore must not hide what went wrong in the test itself.
            try {
                MockGps.shell("cmd connectivity airplane-mode ${if (wasEnabled) "enable" else "disable"}")
                if (!wasEnabled) waitFor("network back") { connectivity.activeNetwork != null }
            } catch (t: Throwable) {
                if (failure == null) throw t
                failure.addSuppressed(t)
            }
        }
    }

    /** What `cmd connectivity airplane-mode` prints: "enabled" or "disabled". */
    private fun airplaneModeEnabled(): Boolean {
        val answer = MockGps.shell("cmd connectivity airplane-mode").trim()
        return when (answer) {
            "enabled" -> true
            "disabled" -> false
            else -> error("`cmd connectivity airplane-mode` answered \"$answer\". $NEEDS_AIRPLANE_MODE")
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "timed out: $what" }
            SystemClock.sleep(100)
        }
    }

    @Test
    fun aGrantThatRunsOutFarAwayIsCheckedAgainAfterAtMostThirtyDays() {
        val now = System.currentTimeMillis()
        for (expires in listOf(
            now + TimeUnit.DAYS.toMillis(400),
            now + TimeUnit.DAYS.toMillis(365L * 8_000), // year 9999
            Long.MAX_VALUE,
        )) {
            scheduler.scheduleExpiry(ID, expires, now)

            assertTrue("expires $expires", scheduler.isExpiryScheduled(ID))
            val latency = expiryJob().minLatencyMillis
            assertTrue("latency $latency", latency in 1..TimeUnit.DAYS.toMillis(30))
        }
    }

    @Test
    fun aGrantThatRunsOutSoonIsCheckedWhenItDoes() {
        val now = System.currentTimeMillis()
        scheduler.scheduleExpiry(ID, now + TimeUnit.HOURS.toMillis(5), now)
        assertEquals(TimeUnit.HOURS.toMillis(5), expiryJob().minLatencyMillis)
    }

    @Test
    fun cancellingWhatIsNotScheduledIsFine() {
        scheduler.cancelUpload(ID)
        scheduler.cancelExpiry(ID)
        assertFalse(scheduler.isUploadScheduled(ID))
        assertFalse(scheduler.isExpiryScheduled(ID))
    }

    private companion object {
        const val ID = 987_654L
        const val NEEDS_AIRPLANE_MODE =
            "This test needs `cmd connectivity airplane-mode` (Android 11+) and a device where airplane mode turns off all networks."
    }
}
