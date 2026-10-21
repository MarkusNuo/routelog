package io.github.markusnuo.routelog.link

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.recording.RecorderState
import io.github.markusnuo.routelog.ui.MainActivity
import io.github.markusnuo.routelog.ui.MainUiState
import io.github.markusnuo.routelog.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Links come from outside: a link alone must never start recording, only the user's "Record".
 * The token comes as an extra of the intent (`S.token=…` of the `intent://` link), never in the address.
 */
@RunWith(AndroidJUnit4::class)
class LinkIntentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** What the browser's `intent://` link turns into: the address as data, the token as an extra. */
    private fun view(address: String, token: String? = TOKEN, flags: Int = 0) =
        Intent(Intent.ACTION_VIEW, Uri.parse(address)).setClass(context, MainActivity::class.java).addFlags(flags)
            .apply { if (token != null) putExtra("token", token) }

    private val untrusted =
        "routelog://record?upload=https%3A%2F%2Funtrusted.example%2Fr&expires=2099-01-01T00:00:00Z"

    @Test
    fun anUntrustedLinkWaitsForConfirmation() {
        val graph = AppGraph.get(context)
        graph.trustedHosts.remove("untrusted.example")
        withLaunched(view(untrusted)) { activity, ui ->
            waitUntil("the confirmation") { ui().linkToConfirm != null }
            assertEquals(RecorderState.Idle, graph.recorder.state.value)
            assertEquals("untrusted.example", ui().linkToConfirm?.host)
            // After it was handled, the activity no longer carries the grant: neither address nor token.
            assertNull(activity.intent.data)
            assertNull(activity.intent.getStringExtra("token"))
        }
    }

    @Test
    fun aTokenInTheAddressIsOnlyReported() {
        val graph = AppGraph.get(context)
        withLaunched(view("$untrusted&token=t-leaked")) { _, ui ->
            waitUntil("a message") { ui().message != null }
            assertEquals(RecorderState.Idle, graph.recorder.state.value)
            assertNull(ui().linkToConfirm)
        }
    }

    @Test
    fun aLinkWithoutATokenIsOnlyReported() {
        val graph = AppGraph.get(context)
        withLaunched(view(untrusted, token = null)) { _, ui ->
            waitUntil("a message") { ui().message != null }
            assertEquals(RecorderState.Idle, graph.recorder.state.value)
            assertNull(ui().linkToConfirm)
        }
    }

    @Test
    fun anInvalidLinkIsOnlyReported() {
        val graph = AppGraph.get(context)
        val twoUploads = "routelog://record?upload=https%3A%2F%2Fa.example%2Fr&upload=https%3A%2F%2Fb.example%2Fr&expires=2099-01-01T00:00:00Z"
        withLaunched(view(twoUploads)) { _, ui ->
            waitUntil("a message") { ui().message != null }
            assertEquals(RecorderState.Idle, graph.recorder.state.value)
            assertNull(ui().linkToConfirm)
            assertNotNull(ui().message)
        }
    }

    @Test
    fun onlyViewIntentsWithOurSchemeAreLinks() {
        val link = MainActivity.recordingLinkOf(view(untrusted))!!
        assertEquals(untrusted, link.address)
        assertEquals(TOKEN, link.token)
        assertNull(MainActivity.recordingLinkOf(view(untrusted.replace("routelog://", "https://"))))
        assertNull(MainActivity.recordingLinkOf(view(untrusted).setAction(Intent.ACTION_MAIN)))
    }

    @Test
    fun theTokenIsReadFromTheExtraAndNeverFromTheAddress() {
        val link = MainActivity.recordingLinkOf(view("$untrusted&token=from-the-address", token = "from-the-extra"))!!
        assertEquals("from-the-extra", link.token)
        assertEquals(
            LinkParseResult.Invalid(LinkParseResult.Reason.BAD_PARAMETERS),
            RecordingLinkParser.parse(link.address, link.token, System.currentTimeMillis(), allowHttp = false),
        )
        // No extra: no token, even if the address has one.
        assertNull(MainActivity.recordingLinkOf(view("$untrusted&token=from-the-address", token = null))!!.token)
    }

    @Test
    fun anExtraThatIsNoStringIsNoToken() {
        val intent = view(untrusted, token = null).putExtra("token", 12345)
        assertNull(MainActivity.recordingLinkOf(intent)!!.token)
    }

    @Test
    fun aLinkReopenedFromRecentAppsIsIgnored() {
        assertNull(MainActivity.recordingLinkOf(view(untrusted, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }

    /**
     * Starts [intent] like a browser would and hands over the resumed activity. ActivityScenario
     * can't be used: it finds its activity by the launch intent, which MainActivity replaces once
     * it has taken the link.
     */
    private fun withLaunched(intent: Intent, block: (MainActivity, () -> MainUiState) -> Unit) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val activity = resumedMainActivity()
        try {
            val viewModel = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
            block(activity) { viewModel.ui.value }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun resumedMainActivity(): MainActivity {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val found = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            if (found != null) return found
            assertTrue("MainActivity did not open", System.nanoTime() < deadline)
            Thread.sleep(100)
        }
    }

    private fun waitUntil(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            assertTrue("Timed out waiting for: $what", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        // Not stored anywhere by these tests: no recording is started.
        const val TOKEN = "t-link-intent-test"
    }
}
