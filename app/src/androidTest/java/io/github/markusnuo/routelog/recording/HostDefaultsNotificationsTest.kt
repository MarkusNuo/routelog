package io.github.markusnuo.routelog.recording

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.RoutelogConfig
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.testing.MockGps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a host app gets with the default [RoutelogConfig]: channel ids with the `routelog_` prefix
 * and notification ids from 0x524C0000 on, far from what a host uses itself. The routelog app
 * itself runs with [RoutelogConfig.legacy] (see AppConfigTest), so this test builds the
 * notifications with the default config directly, next to the app's, and removes its channels again.
 */
@RunWith(AndroidJUnit4::class)
class HostDefaultsNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val config = RoutelogConfig()
    private val host = RecordingNotifications(context, config)

    @Before
    fun setUp() {
        MockGps.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        // A channel of an earlier, killed run must not make this test pass.
        deleteChannels()
        cancelNotices()
        host.createChannels()
    }

    @After
    fun tearDown() {
        cancelNotices()
        deleteChannels()
    }

    private fun cancelNotices() {
        host.cancelInterrupted()
        host.cancelUploadProblem(ID)
    }

    private fun deleteChannels() {
        for (channel in CHANNELS) manager.deleteNotificationChannel(channel)
    }

    private fun shown(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "timed out: $what" }
            SystemClock.sleep(50)
        }
    }

    @Test
    fun theChannelsCarryTheRoutelogPrefix() {
        assertEquals("routelog_recording", host.channelRecording)
        assertEquals("routelog_interruptions", host.channelInterruptions)
        assertEquals("routelog_uploads", host.channelUploads)
        for (channel in CHANNELS) assertNotNull("channel $channel", manager.getNotificationChannel(channel))
        assertTrue("recording is allowed in a fresh channel", host.recordingNotificationEnabled())
    }

    @Test
    fun theNotificationIdsStartAt0x524C0000() {
        assertEquals(0x524C_0001, host.recordingNotificationId)
        assertEquals(0x524C_0002, host.interruptedNotificationId)
        assertEquals(0x524C_0000 + 100 + ID.toInt(), config.uploadProblemNotificationId(ID))
        // The recording notification (the service's) is built for the host's channel.
        assertEquals("routelog_recording", host.recording(RecorderState.Idle).channelId)
    }

    @Test
    fun theInterruptedNoticeIsPostedAndCancelledUnderTheHostId() {
        val end = System.currentTimeMillis() - 60_000
        host.showInterrupted(RecordingEntity(id = ID, startedAtMillis = end - 60_000, endedAtMillis = end, state = RecordingState.INTERRUPTED))

        waitFor("the notice") { shown(0x524C_0002) != null }
        assertEquals("routelog_interruptions", shown(0x524C_0002)!!.notification.channelId)
        // Not under the routelog app's own id (2): the host default does not collide with it.
        assertNull(manager.activeNotifications.firstOrNull { it.id == 2 && it.notification.channelId == "routelog_interruptions" })

        host.cancelInterrupted()

        waitFor("the notice is gone") { shown(0x524C_0002) == null }
    }

    @Test
    fun anUploadProblemIsPostedAndCancelledUnderTheHostId() {
        host.showUploadProblem(ID, "example.com", UploadState.FAILED, 500)

        val id = 0x524C_0000 + 100 + ID.toInt()
        waitFor("the problem notice") { shown(id) != null }
        assertEquals("routelog_uploads", shown(id)!!.notification.channelId)

        host.cancelUploadProblem(ID)

        waitFor("the problem notice is gone") { shown(id) == null }
    }

    private companion object {
        const val ID = 987_653L
        val CHANNELS = listOf("routelog_recording", "routelog_interruptions", "routelog_uploads")
    }
}
