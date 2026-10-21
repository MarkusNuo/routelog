package io.github.markusnuo.routelog.recording

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.Routelog
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.testing.MockGps
import io.github.markusnuo.routelog.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * What the library's resume screen does when the resume does not start: the notice "recording
 * interrupted" that the tap removed comes back (if the recording can still be resumed), and only
 * then the host app is told. The app's own screen (the host here) removes that notice when it opens,
 * as a host's list should, so the host app is not opened in this test: the screen's request to open
 * it is caught and answered with "cancelled". No app screen is kept open either ([MockGps] with
 * `keepAppVisible = false`).
 */
@RunWith(AndroidJUnit4::class)
class RoutelogResumeScreenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @get:Rule
    val gps = MockGps(context, keepAppVisible = false)

    private val graph get() = AppGraph.get(context)
    private val dao get() = graph.database.recordingDao()
    private val insertedIds = mutableListOf<Long>()
    private val hostIntents = CopyOnWriteArrayList<Intent>()

    /** Catches the request to open the host app and doesn't carry it out. */
    private val hostBlocker = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className != MainActivity::class.java.name) return null
            hostIntents += Intent(intent)
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    @Before
    fun setUp() {
        MockGps.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        graph.notifications.cancelInterrupted()
        instrumentation.addMonitor(hostBlocker)
    }

    @After
    fun tearDown() {
        instrumentation.removeMonitor(hostBlocker)
        // Through the library's delete: it ends the jobs and notices of the recording as well.
        insertedIds.forEach { runBlocking { Routelog.delete(context, it) } }
        graph.notifications.cancelInterrupted()
    }

    private fun insert(state: RecordingState, endedAgoMillis: Long = 60_000): Long {
        val end = System.currentTimeMillis() - endedAgoMillis
        val id = dao.insertRecording(
            RecordingEntity(
                startedAtMillis = end - 60_000,
                endedAtMillis = if (state == RecordingState.RECORDING) null else end,
                state = state,
            ),
        )
        insertedIds += id
        return id
    }

    private fun noticeShown() = manager.activeNotifications.any { it.id == graph.notifications.interruptedNotificationId }

    private fun withGpsOff(block: () -> Unit) {
        val locationManager = context.getSystemService(LocationManager::class.java)
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        try {
            block()
        } finally {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        }
    }

    private fun waitUntil(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            assertTrue("Timed out waiting for: $what", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    @Test
    fun aResumeThatDoesNotStartPostsTheNoticeAgainAndThenTellsTheHostApp() {
        val id = insert(RecordingState.INTERRUPTED)
        assertFalse(noticeShown())

        withGpsOff {
            context.startActivity(RoutelogResumeActivity.intent(context, id))

            waitUntil("the host app is asked to open") { hostIntents.isNotEmpty() }
            val host = hostIntents.single()
            assertEquals(id, host.getLongExtra(Routelog.EXTRA_RESUME_FAILED, 0))
            assertTrue("in a task of its own", host.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            // The notice was posted before the host app was asked, so it is there, and stays.
            waitUntil("the notice is back") { noticeShown() }
            Thread.sleep(500)
            assertTrue(noticeShown())
        }
        assertEquals(RecordingState.INTERRUPTED, dao.recording(id)?.state)
    }

    @Test
    fun noNoticeComesBackForARecordingThatCannotBeResumedAnymore() {
        val finished = insert(RecordingState.FINISHED)

        withGpsOff {
            context.startActivity(RoutelogResumeActivity.intent(context, finished))

            waitUntil("the host app is asked to open") { hostIntents.isNotEmpty() }
            // The host still hears that the resume did not start; it finds the recording as it is.
            assertEquals(finished, hostIntents.single().getLongExtra(Routelog.EXTRA_RESUME_FAILED, 0))
            Thread.sleep(1_000)
            assertFalse(noticeShown())
        }
    }
}
