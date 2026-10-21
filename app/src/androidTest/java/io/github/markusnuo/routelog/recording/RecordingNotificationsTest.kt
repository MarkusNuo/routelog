package io.github.markusnuo.routelog.recording

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.testing.MockGps
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The notice "recording interrupted" with the real NotificationManager (resumable for 12 hours). */
@RunWith(AndroidJUnit4::class)
class RecordingNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications get() = AppGraph.get(context).notifications
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        MockGps.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        cancelAll()
    }

    @After
    fun cancelAll() {
        notifications.cancelInterrupted()
        notifications.cancelUploadProblem(ID)
    }

    private fun interrupted(agoMillis: Long): RecordingEntity {
        val end = System.currentTimeMillis() - agoMillis
        return RecordingEntity(id = ID, startedAtMillis = end - 600_000, endedAtMillis = end, state = RecordingState.INTERRUPTED)
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
    fun aFreshInterruptionIsAnnouncedUntilTheResumeWindowCloses() {
        notifications.showInterrupted(interrupted(agoMillis = TimeUnit.HOURS.toMillis(2)))

        waitFor("the notice") { shown(notifications.interruptedNotificationId) != null }
        val timeout = shown(notifications.interruptedNotificationId)!!.notification.timeoutAfter
        assertTrue("timeout $timeout", timeout in 1..TimeUnit.HOURS.toMillis(10))
    }

    @Test
    fun noNoticeOnceTheResumeWindowIsClosed() {
        notifications.showInterrupted(interrupted(agoMillis = TimeUnit.HOURS.toMillis(13)))
        // Notifications are posted in order: once this one is there, the one above would be too.
        notifications.showUploadProblem(ID, "example.com", UploadState.FAILED, 500)
        waitFor("the marker notice") { shown(UPLOAD_PROBLEM_ID) != null }

        assertNull(shown(notifications.interruptedNotificationId))
    }

    private companion object {
        const val ID = 987_654L

        // RoutelogConfig.legacy().uploadProblemNotificationId(ID): the app runs with the legacy ids.
        const val UPLOAD_PROBLEM_ID = (100 + ID).toInt()
    }
}
