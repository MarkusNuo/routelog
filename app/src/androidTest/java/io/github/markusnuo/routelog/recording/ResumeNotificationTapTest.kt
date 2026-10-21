package io.github.markusnuo.routelog.recording

import android.app.Instrumentation
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.Routelog
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.library.R
import io.github.markusnuo.routelog.testing.MockGps
import io.github.markusnuo.routelog.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The resume screen of the library as a host gets it: a notification whose tap opens
 * [RoutelogResumeActivity] through an immutable PendingIntent, with the app in the background and
 * the notification opened and tapped in the notification shade (found and clicked through the
 * accessibility tree, as a user would). The recording must continue in a new segment.
 *
 * Needs an emulator or phone with an unlocked screen. No app screen is kept open ([MockGps] with
 * `keepAppVisible = false`).
 */
@RunWith(AndroidJUnit4::class)
class ResumeNotificationTapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @get:Rule
    val gps = MockGps(context, keepAppVisible = false)

    private val graph get() = AppGraph.get(context)
    private val dao get() = graph.database.recordingDao()
    private var recordingId = 0L
    private val noticeId = 4_242

    // The app's own screen, which the resume screen opens after the tap: closed again at the end.
    private val appScreen = Instrumentation.ActivityMonitor(MainActivity::class.java.name, null, false)

    @Before
    fun setUp() {
        MockGps.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        MockGps.shell("input keyevent KEYCODE_WAKEUP")
        MockGps.shell("wm dismiss-keyguard")
        instrumentation.addMonitor(appScreen)
    }

    @After
    fun tearDown() {
        MockGps.shell("cmd statusbar collapse")
        manager.cancel(noticeId)
        try {
            Routelog.stop(context)
            waitFor { graph.recorder.state.value == RecorderState.Idle }
        } finally {
            if (recordingId != 0L) {
                runBlocking { Routelog.delete(context, recordingId) }
                graph.uploads.forget(recordingId)
                dao.delete(recordingId)
            }
            graph.notifications.cancelInterrupted()
            instrumentation.removeMonitor(appScreen)
            appScreen.lastActivity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            MockGps.shell("input keyevent KEYCODE_HOME")
        }
    }

    @Test
    fun tappingTheNoticeInTheShadeResumesTheRecordingInANewSegment() {
        assertEquals("the app's recorder must be idle before the test", RecorderState.Idle, graph.recorder.state.value)
        val end = System.currentTimeMillis() - 60_000
        recordingId = dao.insertRecording(RecordingEntity(startedAtMillis = end - 60_000, endedAtMillis = end, state = RecordingState.INTERRUPTED))
        val title = "Resume test " + UUID.randomUUID()
        postNotice(title)

        MockGps.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(500)
        MockGps.shell("cmd statusbar expand-notifications")

        val clicked = clickNotice(title)
        assertNotNull("the notification could not be found and clicked in the shade; attempts:\n$attempts\ntree:\n$lastTree", clicked)

        waitFor(20_000) { (graph.recorder.state.value as? RecorderState.Recording)?.recordingId == recordingId }
        assertEquals(recordingId, (graph.recorder.state.value as? RecorderState.Recording)?.recordingId)
        assertEquals(1, dao.recording(recordingId)?.segment)
    }

    // What a host would do with the library's resume screen as the target of its own notification.
    private fun postNotice(title: String) {
        val notification = NotificationCompat.Builder(context, graph.notifications.channelInterruptions)
            .setSmallIcon(R.drawable.routelog_ic_stat_recording)
            .setContentTitle(title)
            .setContentText("Tap to continue")
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    4,
                    RoutelogResumeActivity.intent(context, recordingId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(noticeId, notification)
        waitFor { manager.activeNotifications.any { it.id == noticeId } }
    }

    private var lastTree = ""
    private var attempts = ""

    /** Finds the notification by its title in any window and clicks the row; null if it is not found. */
    private fun clickNotice(title: String): Boolean? {
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val roots = buildList {
                automation.rootInActiveWindow?.let { add(it) }
                automation.windows.forEach { window -> window.root?.let { add(it) } }
            }
            lastTree = roots.joinToString("\n---\n") { dump(it, 0) }.take(6_000)
            for (root in roots) {
                val node = find(root, title, 0) ?: continue
                var target: AccessibilityNodeInfo? = node
                while (target != null) {
                    if (target.isClickable) {
                        val done = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        attempts += "click on ${target.className} (${target.viewIdResourceName}) -> $done\n"
                        if (done) return true
                    }
                    target = target.parent
                }
            }
            SystemClock.sleep(300)
        }
        return null
    }

    // By walking the tree: findAccessibilityNodeInfosByText did not find the title here.
    private fun find(node: AccessibilityNodeInfo, text: String, depth: Int): AccessibilityNodeInfo? {
        if (node.text?.toString() == text) return node
        if (depth > 25) return null
        for (index in 0 until node.childCount) {
            val found = node.getChild(index)?.let { find(it, text, depth + 1) }
            if (found != null) return found
        }
        return null
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int): String {
        val line = "  ".repeat(depth) + "${node.className} text=${node.text} click=${node.isClickable}\n"
        if (depth > 12) return line
        return line + (0 until node.childCount).joinToString("") { index -> node.getChild(index)?.let { dump(it, depth + 1) } ?: "" }
    }

    private fun waitFor(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
    }
}
