package io.github.markusnuo.routelog

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The routelog app runs the library with its older ids and names ([RoutelogConfig.legacy]): the
 * channel settings of its users and the jobs already scheduled on their phones depend on them. If
 * `Routelog.configure` is missing in the app's `Application.onCreate`, the library falls back to
 * the host defaults and this test fails.
 */
@RunWith(AndroidJUnit4::class)
class AppConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val core get() = RoutelogCore.get(context)

    @Test
    fun theAppRunsWithTheLegacyConfigApartFromItsTwoIntents() {
        val config = core.config

        // The two lambdas are the app's own and can't be compared; everything else must be equal.
        assertEquals(RoutelogConfig.legacy(), config.copy(openAppIntent = null, resumeIntent = null))
        assertNotNull(config.openAppIntent)
        assertNotNull(config.resumeIntent)
    }

    @Test
    fun theAppsNotificationsOpenItsOwnScreen() {
        val open = core.config.openAppIntent!!(context)
        assertEquals(MainActivity::class.java.name, open.component?.className)

        val resume = core.config.resumeIntent!!(context, 7)
        // Through the not exported alias, not the library's own resume screen.
        assertEquals("io.github.markusnuo.routelog.ui.ResumeRecordingAlias", resume.component?.className)
        assertEquals(7L, MainActivity.resumeRecordingIdOf(resume))
        assertTrue(resume.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun theOldChannelsAndNotificationIdsAreThere() {
        val manager = context.getSystemService(NotificationManager::class.java)

        for (channel in listOf("recording", "interruptions", "uploads")) {
            assertNotNull("channel $channel", manager.getNotificationChannel(channel))
        }
        assertEquals("recording", core.notifications.channelRecording)
        assertEquals(1, core.config.recordingNotificationId())
        assertEquals(1, core.notifications.recordingNotificationId)
        assertEquals(2, core.notifications.interruptedNotificationId)
    }
}
