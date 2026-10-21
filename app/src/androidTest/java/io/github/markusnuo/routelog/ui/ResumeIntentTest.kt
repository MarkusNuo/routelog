package io.github.markusnuo.routelog.ui

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.testing.MockGps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The resume request of the "recording interrupted" notification must not be forgeable by other apps. */
@RunWith(AndroidJUnit4::class)
class ResumeIntentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theIntentOfTheNotificationIsAccepted() {
        assertEquals(7L, MainActivity.resumeRecordingIdOf(MainActivity.resumeIntent(context, 7)))
    }

    @Test
    fun theSameActionSentStraightToTheLauncherActivityIsIgnored() {
        // What another app could send: MainActivity is exported.
        val forged = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.resumeIntent(context, 7).action)
            .putExtra("recording_id", 7L)
        assertNull(MainActivity.resumeRecordingIdOf(forged))
    }

    @Test
    fun anImplicitIntentWithTheActionIsIgnored() {
        val forged = Intent(MainActivity.resumeIntent(context, 7).action).putExtra("recording_id", 7L)
        assertNull(MainActivity.resumeRecordingIdOf(forged))
    }

    @Test
    fun reopeningFromTheRecentAppsListIsIgnored() {
        val reopened = MainActivity.resumeIntent(context, 7).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(MainActivity.resumeRecordingIdOf(reopened))
    }

    @Test
    fun aMissingOrInvalidIdIsIgnored() {
        assertNull(MainActivity.resumeRecordingIdOf(MainActivity.resumeIntent(context, 0)))
        assertNull(MainActivity.resumeRecordingIdOf(MainActivity.resumeIntent(context, -3)))
        val withoutId = MainActivity.resumeIntent(context, 7).apply { removeExtra("recording_id") }
        assertNull(MainActivity.resumeRecordingIdOf(withoutId))
    }

    @Test
    fun otherAppsCanStartTheLauncherActivityButNotTheResumeAlias() {
        val packages = context.packageManager
        assertTrue(packages.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).exported)
        val alias = MainActivity.resumeIntent(context, 7).component!!
        assertFalse(packages.getActivityInfo(alias, 0).exported)
    }

    @Test
    fun theSystemDeliversTheAliasToTheActivityAndOnlyThenItCounts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // With everything granted the start does not stop at a permission dialog; the id does not
        // exist, so the service gives up again at once.
        for (permission in listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS")) {
            MockGps.shell("pm grant ${context.packageName} android.permission.$permission")
        }
        // Nor at the one-time battery exemption dialog, which would cover the app and keep the
        // second launch from coming to the front. The phone is left as it was.
        val exemptBefore = AppGraph.get(context).battery.isExempt()
        MockGps.shell("cmd deviceidle whitelist +${context.packageName}")
        try {
            launchViaAliasAndDirectly(instrumentation)
        } finally {
            if (!exemptBefore) MockGps.shell("cmd deviceidle whitelist -${context.packageName}")
        }
    }

    private fun launchViaAliasAndDirectly(instrumentation: Instrumentation) {
        val viaAlias = instrumentation.startActivitySync(MainActivity.resumeIntent(context, 987_654L))
        try {
            assertEquals(987_654L, MainActivity.resumeRecordingIdOf(viaAlias.intent))
        } finally {
            viaAlias.finish()
            instrumentation.waitForIdleSync()
        }

        val forged = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.resumeIntent(context, 1).action)
            .putExtra("recording_id", 987_654L)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val direct = instrumentation.startActivitySync(forged)
        try {
            assertNull(MainActivity.resumeRecordingIdOf(direct.intent))
        } finally {
            direct.finish()
            instrumentation.waitForIdleSync()
        }
    }
}
