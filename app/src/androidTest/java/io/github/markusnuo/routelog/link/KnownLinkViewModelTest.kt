package io.github.markusnuo.routelog.link

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.R
import io.github.markusnuo.routelog.data.GrantEntity
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.UploadEntity
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.ui.MainViewModel
import io.github.markusnuo.routelog.ui.StartRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What the screen's [MainViewModel] does with a valid link whose grant it has seen before (one
 * link = one recording). Works on the app's own database with rows of its own that it removes
 * again; no recording is started, so the queued start is what is looked at.
 */
@RunWith(AndroidJUnit4::class)
class KnownLinkViewModelTest {
    private val application: Application = ApplicationProvider.getApplicationContext<Context>() as Application
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph = AppGraph.get(application)
    private val token = "tok-known-" + UUID.randomUUID()
    private val address = "routelog://record?upload=https%3A%2F%2Fknown.example%2Fr&expires=2099-01-01T00:00:00Z"
    private val store = ViewModelStore()
    private lateinit var viewModel: MainViewModel
    private val recordingIds = mutableListOf<Long>()

    @Before
    fun open() {
        graph.trustedHosts.remove("known.example")
        viewModel = onMain {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainViewModel::class.java]
        }
    }

    @After
    fun close() {
        onMain { store.clear() }
        val db = graph.database.openHelper.writableDatabase
        recordingIds.forEach { db.execSQL("DELETE FROM recordings WHERE id = $it") }
        db.execSQL("DELETE FROM grants WHERE token_hash = '${GrantHash.of(token)}'")
    }

    /**
     * A recording of this link in [state], with its upload and grant (the grant without recording if
     * [keepRecording] is false). The upload is DONE, not open, so the maintenance that runs
     * when the process starts leaves it alone. [interruptedAgoMillis] is how long ago the recording ended;
     * resuming is possible for 12 hours.
     */
    private fun usedLink(state: RecordingState, keepRecording: Boolean = true, interruptedAgoMillis: Long = 60_000) {
        val recordings = graph.database.recordingDao()
        val end = System.currentTimeMillis() - interruptedAgoMillis
        val id = recordings.insertRecording(RecordingEntity(startedAtMillis = end - 60_000, endedAtMillis = end, state = state))
        recordingIds += id
        graph.database.uploadDao().insert(
            UploadEntity(id, "https://known.example/r", "known.example", null, Long.MAX_VALUE / 2, UploadState.DONE),
        )
        graph.database.grantDao().insert(GrantEntity(GrantHash.of(token), id, Long.MAX_VALUE / 2))
        if (!keepRecording) graph.database.recordingDao().delete(id)
    }

    private fun sendLink() {
        onMain { viewModel.onLink(IncomingLink(address, token)) }
    }

    @Test
    fun anInterruptionOlderThan12HoursIsNotResumedByTheLink() {
        usedLink(RecordingState.INTERRUPTED, interruptedAgoMillis = TimeUnit.HOURS.toMillis(13))

        sendLink()

        waitUntil("the message") { viewModel.ui.value.message != null }
        assertEquals(R.string.resume_too_late, viewModel.ui.value.message?.text)
        assertNull(viewModel.queuedStart.value)
        assertNull(viewModel.ui.value.linkToConfirm)
    }

    @Test
    fun anInterruptedRecordingOfTheSameLinkIsResumedWithoutAQuestion() {
        usedLink(RecordingState.INTERRUPTED)

        sendLink()

        waitUntil("the resume is queued") { viewModel.queuedStart.value != null }
        assertEquals(StartRequest.Resume(recordingIds.single()), viewModel.queuedStart.value)
        assertNull("no confirmation", viewModel.ui.value.linkToConfirm)
        assertNull(viewModel.ui.value.message)
    }

    @Test
    fun aStoppedRecordingOfTheSameLinkIsAlreadyUsed() {
        usedLink(RecordingState.FINISHED)

        sendLink()

        waitUntil("the message") { viewModel.ui.value.message != null }
        assertEquals(R.string.link_already_used, viewModel.ui.value.message?.text)
        assertNull(viewModel.queuedStart.value)
        assertNull(viewModel.ui.value.linkToConfirm)
    }

    @Test
    fun aDeletedRecordingOfTheSameLinkIsAlreadyUsed() {
        usedLink(RecordingState.FINISHED, keepRecording = false)
        assertNull(graph.database.grantDao().grant(GrantHash.of(token))?.recordingId)

        sendLink()

        waitUntil("the message") { viewModel.ui.value.message != null }
        assertEquals(R.string.link_already_used, viewModel.ui.value.message?.text)
        assertNull(viewModel.queuedStart.value)
        assertNull(viewModel.ui.value.linkToConfirm)
    }

    @Test
    fun aLinkNobodyUsedYetAsksFirst() {
        sendLink()

        waitUntil("the confirmation") { viewModel.ui.value.linkToConfirm != null }
        assertEquals("known.example", viewModel.ui.value.linkToConfirm?.host)
        assertNull(viewModel.queuedStart.value)
    }

    @Test
    fun aQueuedStartIsTakenOnceAndCanBeDiscarded() {
        onMain { viewModel.queueStart(StartRequest.Resume(5)) }
        assertEquals(StartRequest.Resume(5), onMain { viewModel.takeQueuedStart() })
        assertNull(onMain { viewModel.takeQueuedStart() })

        onMain { viewModel.queueStart(StartRequest.Resume(6)) }
        onMain { viewModel.discardQueuedStart() }
        assertNull(onMain { viewModel.takeQueuedStart() })
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
}
