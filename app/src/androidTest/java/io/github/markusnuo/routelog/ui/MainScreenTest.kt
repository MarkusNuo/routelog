package io.github.markusnuo.routelog.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.R
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.link.RecordingLink
import io.github.markusnuo.routelog.recording.RecorderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The main screen with the app's real view model and recorder: what the user sees when a start
 * or resume is refused, and the guard of the link dialog against overlays.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph get() = AppGraph.get(context)
    private val store = ViewModelStore()
    private val inserted = mutableListOf<Long>()

    @Before
    fun requireIdleRecorder() {
        // The recorder is the app's real one, shared by all tests: they start from a clean state.
        assertEquals("The app's recorder is not idle at the start of the test", RecorderState.Idle, graph.recorder.state.value)
    }

    @After
    fun cleanUp() {
        store.clear()
        try {
            // A recording a test started must end (GPS, thread) before its rows go.
            val stopped = CountDownLatch(1)
            graph.recorder.stop { stopped.countDown() }
            assertTrue("The recorder did not stop in time", stopped.await(10, TimeUnit.SECONDS))
        } finally {
            runBlocking(Dispatchers.IO) { inserted.forEach { graph.database.recordingDao().delete(it) } }
        }
    }

    private fun showMainScreen() {
        var viewModel: MainViewModel? = null
        // Created on the main thread, as the activity does: it subscribes to the recorder's events at once.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context as Application))[MainViewModel::class.java]
        }
        compose.setContent {
            MainScreen(
                viewModel = viewModel!!,
                onStart = {},
                onResumeRecording = {},
                onStop = {},
                onBlockerConfirm = {},
                onConfirmLink = {},
                onAllowBattery = {},
            )
        }
    }

    private fun waitForText(id: Int) {
        val text = context.getString(id)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRecordingThatCanNotBeResumedIsExplained() {
        showMainScreen()
        graph.recorder.resume(NO_SUCH_RECORDING) {}
        waitForText(R.string.resume_failed)
    }

    @Test
    fun aResumeAfterTwelveHoursIsExplained() {
        val now = System.currentTimeMillis()
        val id = runBlocking {
            withContext(Dispatchers.IO) {
                graph.database.recordingDao().insertRecording(
                    RecordingEntity(
                        startedAtMillis = now - TimeUnit.HOURS.toMillis(14),
                        endedAtMillis = now - TimeUnit.HOURS.toMillis(13),
                        state = RecordingState.INTERRUPTED,
                    ),
                )
            }
        }
        inserted += id
        showMainScreen()
        graph.recorder.resume(id) {}
        waitForText(R.string.resume_too_late)
    }

    @Test
    fun aStartThatFailedIsExplained() {
        showMainScreen()
        graph.recorder.reportStartFailed()
        waitForText(R.string.start_failed)
    }

    @Test
    fun theLinkDialogIgnoresTouchesThroughOverlays() {
        val link = RecordingLink(
            uploadUrl = "https://a.example/r/1",
            host = "a.example",
            secure = true,
            token = "test-token",
            label = "Lesson 7",
            expiresAtMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1),
        )
        compose.setContent { LinkConfirmAlert(link, onConfirm = {}, onDismiss = {}) }
        waitForText(R.string.link_title)
        onView(isRoot()).inRoot(isDialog()).check { view, _ ->
            assertTrue("dialog window", view.rootView.filterTouchesWhenObscured)
        }
    }

    private companion object {
        const val NO_SUCH_RECORDING = 987_654_321L
    }
}
