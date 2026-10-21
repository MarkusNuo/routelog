package io.github.markusnuo.routelog.recording

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.testing.MockGps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The real recording service with the app's recorder: a start that arrives while a stop is
 * still being carried out must leave a recording that runs as a foreground service, with
 * a notification that shows this recording.
 *
 * Why the foreground service matters: location is a "while in use" permission. An app that has
 * only that gets no locations unless it is visible or runs a location foreground service, so with
 * the screen off or the app in the background, a recording without the service goes without fixes.
 * Here the app is visible (see [MockGps]), so fixes arrive either way; these tests therefore look
 * at the service itself (foreground flag, notification). The screen-off effect can only be seen on
 * a real device.
 *
 * Made for the AVDs, not for a phone: the tests use the app's own database (the recordings they
 * create are deleted afterwards), grant the notification permission and use mock location.
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    // Keeps the app visible: only then may a location foreground service be started.
    @get:Rule
    val gps = MockGps(context)

    private val graph get() = AppGraph.get(context)
    private val dao get() = graph.database.recordingDao()
    private val created = mutableSetOf<Long>()
    private var testStartedAtMillis = 0L

    @Before
    fun grantNotifications() {
        testStartedAtMillis = System.currentTimeMillis()
        MockGps.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assertEquals("the app's recorder must be idle before the test", RecorderState.Idle, graph.recorder.state.value)
    }

    @After
    fun cleanUp() {
        try {
            context.startService(RecordingService.stopIntent(context))
            // No assertions: when the test failed, a second failure here would only hide the first
            // (and the next test insists on an idle recorder anyway).
            waitFor { graph.recorder.state.value == RecorderState.Idle }
            waitFor { !serviceRunning() }
        } finally {
            // Also a recording that a start created after waitForRecording gave up.
            val strays = runBlocking { dao.observeRecordingList().first() }
                .map { it.recording }
                .filter { it.startedAtMillis >= testStartedAtMillis }
            (created + strays.map { it.id }).forEach { dao.delete(it) }
        }
    }

    @Test
    fun aStartSentRightAfterAStopIsNotEndedByTheStop() {
        // The two commands reach the service one after the other. The recorder has usually carried
        // out the stop (and reported Idle) before the start arrives, so this does not hit the race
        // of the next test. It checks the sequencing: what the stop does at its end (leave the
        // foreground, end the service) must not take away the service that the start needs.
        repeat(ROUNDS) { round ->
            context.startService(RecordingService.startIntent(context))
            val first = waitForRecording(other = null, round = round)

            context.startService(RecordingService.stopIntent(context))
            context.startService(RecordingService.startIntent(context))
            val second = waitForRecording(other = first, round = round)

            // Whatever the stop still had to do is done by now.
            Thread.sleep(1_500)
            assertEquals("round $round: still recording", second, recordingId())
            assertTrue("round $round: foreground service", serviceInForeground())
            assertTrue("round $round: notification", graph.notifications.recordingNotificationShown())
            assertEquals("round $round: first finished", RecordingState.FINISHED, dao.recording(first)?.state)

            context.startService(RecordingService.stopIntent(context))
            waitUntil("round $round: idle") { graph.recorder.state.value == RecorderState.Idle }
            waitUntil("round $round: service gone") { !serviceRunning() }
        }
    }

    @Suppress("DEPRECATION") // beginTransaction: holds the database's write lock on this thread
    @Test
    fun aStartQueuedBehindAStopKeepsTheServiceInTheForeground() {
        // The gap is a few milliseconds (between the end of the stop and the new recording being
        // set up), so not every round hits it. Without the startsInFlight check in the service's
        // leave(), every one of 7 runs on the AVD failed, the first time in the 3rd to 9th round
        // (about one round in six); 30 rounds make a miss very unlikely.
        repeat(QUEUED_ROUNDS) { round ->
            context.startService(RecordingService.startIntent(context))
            val first = waitForRecording(other = null, round = round)

            // Keep the recorder thread busy with a fix that waits for the database, so that the
            // stop and the start are both queued behind it: the start arrives while the recorder
            // still records, and the stop's clean-up runs while the start is being carried out.
            val database = graph.database
            database.beginTransaction()
            try {
                gps.push(round * 10.0)
                Thread.sleep(300)
                context.startService(RecordingService.stopIntent(context))
                context.startService(RecordingService.startIntent(context))
                Thread.sleep(500)
                // Without this, a round whose commands were not both queued would test nothing.
                assertEquals("round $round: stop and start still queued", first, recordingId())
            } finally {
                database.endTransaction()
            }
            val second = waitForRecording(other = first, round = round)

            Thread.sleep(1_500)
            assertEquals("round $round: still recording", second, recordingId())
            assertTrue("round $round: foreground service", serviceInForeground())
            assertTrue("round $round: notification", graph.notifications.recordingNotificationShown())
            assertEquals("round $round: first finished", RecordingState.FINISHED, dao.recording(first)?.state)

            // The notification shows the new recording, not the one it was first drawn for.
            val startedAt = dao.recording(second)!!.startedAtMillis
            waitUntil("round $round: notification of the new recording") { recordingNotificationWhen() == startedAt }

            // GPS still delivers to the new recording.
            val points = dao.recording(second)!!.pointCount
            gps.push(round * 10.0 + MockGps.STEP_METERS)
            waitUntil("round $round: point of the new recording stored") { dao.recording(second)!!.pointCount > points }

            context.startService(RecordingService.stopIntent(context))
            waitUntil("round $round: idle") { graph.recorder.state.value == RecorderState.Idle }
            waitUntil("round $round: service gone") { !serviceRunning() }
        }
    }

    @Test
    fun aStopEndsTheServiceItsNotificationAndGps() {
        context.startService(RecordingService.startIntent(context))
        val id = waitForRecording(other = null)
        assertTrue(serviceInForeground())
        // GPS delivers while recording; without this, the check after the stop would prove nothing.
        gps.push(0.0)
        waitUntil("a point stored") { dao.recording(id)?.pointCount == 1 }

        context.startService(RecordingService.stopIntent(context))
        waitUntil("idle") { graph.recorder.state.value == RecorderState.Idle }
        waitUntil("service gone") { !serviceRunning() }
        waitUntil("notification gone") { !graph.notifications.recordingNotificationShown() }
        assertEquals(RecordingState.FINISHED, dao.recording(id)?.state)

        // GPS is off: a fix that arrives now goes nowhere, and no recording gains a point.
        gps.push(MockGps.STEP_METERS)
        Thread.sleep(1_500)
        assertEquals(RecorderState.Idle, graph.recorder.state.value)
        assertEquals(listOf(1), recordingsSinceTestStart().map { it.pointCount })
    }

    @Test
    fun aStartCommandThatCannotBeCarriedOutGivesTheClaimBack() {
        // What Routelog does before it asks the service: claim the start. A command the service
        // can't use (a linked start without its link) must not leave the recorder in "Starting".
        assertTrue(graph.recorder.claimStart())
        assertEquals(RecorderState.Starting, graph.recorder.state.value)

        context.startService(Intent(context, RecordingService::class.java).setAction("io.github.markusnuo.routelog.action.START_LINKED"))

        waitUntil("idle again") { graph.recorder.state.value == RecorderState.Idle }
        waitUntil("service gone") { !serviceRunning() }
        // And the next start is not turned away.
        context.startService(RecordingService.startIntent(context))
        waitForRecording(other = null)
    }

    private fun recordingId(): Long? = (graph.recorder.state.value as? RecorderState.Recording)?.recordingId

    private fun recordingsSinceTestStart(): List<RecordingEntity> =
        runBlocking { dao.observeRecordingList().first() }
            .map { it.recording }
            .filter { it.startedAtMillis >= testStartedAtMillis }

    private fun waitForRecording(other: Long?, round: Int? = null): Long {
        var id = 0L
        waitUntil("round $round: recording other than $other") {
            val state = graph.recorder.state.value as? RecorderState.Recording
            state != null && state.recordingId != other && state.recordingId.also { id = it } != 0L
        }
        created += id
        return id
    }

    /** The start time that the shown recording notification counts from, or null if there is none. */
    private fun recordingNotificationWhen(): Long? = notificationManager.activeNotifications
        .firstOrNull { it.id == graph.notifications.recordingNotificationId }
        ?.notification?.`when`

    @Suppress("DEPRECATION") // still returns the caller's own services
    private fun ownService(): ActivityManager.RunningServiceInfo? =
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == RecordingService::class.java.name && it.service.packageName == context.packageName }

    private fun serviceRunning() = ownService() != null

    private fun serviceInForeground() = ownService()?.foreground == true

    private fun waitUntil(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        assertTrue("Timed out waiting for: $what", waitFor(timeoutMillis, condition))
    }

    private fun waitFor(timeoutMillis: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(50)
        }
        return true
    }

    private companion object {
        const val ROUNDS = 5
        const val QUEUED_ROUNDS = 30
    }
}
