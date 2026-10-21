package io.github.markusnuo.routelog.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.recording.Recorder
import io.github.markusnuo.routelog.recording.Recorder.StartResult
import io.github.markusnuo.routelog.recording.RecorderState
import io.github.markusnuo.routelog.testing.MockGps
import io.github.markusnuo.routelog.testing.MockGps.Companion.STEP_METERS
import io.github.markusnuo.routelog.upload.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One link = one recording (docs/EXTERNAL_RECORDING.md): what the same link does while its
 * recording runs, after an interruption, after the stop, and after the recording was deleted.
 * Real recorder and database, the decision of [GrantDecision] on what the database holds.
 */
@RunWith(AndroidJUnit4::class)
class OneLinkOneRecordingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val gps = MockGps(context)

    private lateinit var database: RoutelogDatabase
    private lateinit var recorder: Recorder
    private lateinit var tokens: TokenStore

    private val link = RecordingLink(
        uploadUrl = "https://app.example.com/api/routes/42",
        host = "app.example.com",
        secure = true,
        token = "tok-one-link",
        label = null,
        expiresAtMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1),
    )

    @Before
    fun open() {
        context.deleteDatabase(TEST_DB)
        database = RoutelogDatabase.build(context, TEST_DB)
        tokens = TokenStore(context, TEST_TOKENS).also { it.clear() }
        recorder = Recorder(context, database, tokens = tokens)
    }

    @After
    fun close() {
        runCatching { await<Unit> { done -> recorder.stop { done(Unit) } } }
        recorder.quit()
        tokens.clear()
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    /** What the app does with [link] arriving now, from what the database knows about its grant. */
    private fun decide(): LinkGrantAction {
        val grant = database.grantDao().grant(GrantHash.of(link.token))
        val recording = grant?.recordingId?.let(database.recordingDao()::recording)
        val running = (recorder.state.value as? RecorderState.Recording)?.recordingId
        return GrantDecision.decide(grant, recording, running, System.currentTimeMillis())
    }

    private fun startLinked(): StartResult = await { done -> recorder.start(link, done) }

    private fun runningId() = (recorder.state.value as RecorderState.Recording).recordingId

    private fun recordingsCount() = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM recordings").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aNewLinkIsNew() {
        assertEquals(LinkGrantAction.NewLink, decide())
    }

    @Test
    fun theSameLinkWhileItsRecordingRunsChangesNothing() {
        assertEquals(StartResult.STARTED, startLinked())
        val id = runningId()
        repeat(2) { gps.push(it * STEP_METERS) }
        waitUntil("2 points") { database.recordingDao().recording(id)?.pointCount == 2 }

        assertEquals(LinkGrantAction.KeepRecording, decide())
        // Even if the start were attempted, there is no second recording.
        assertEquals(StartResult.ALREADY_RECORDING, startLinked())

        assertEquals(1, recordingsCount())
        assertEquals(id, runningId())
        assertEquals(RecordingState.RECORDING, database.recordingDao().recording(id)?.state)
        assertEquals(2, database.recordingDao().recording(id)?.pointCount)
    }

    @Test
    fun theSameLinkAfterAnInterruptionResumesExactlyThatRecording() {
        assertEquals(StartResult.STARTED, startLinked())
        val id = runningId()
        repeat(3) { gps.push(it * STEP_METERS) }
        waitUntil("3 points") { database.recordingDao().recording(id)?.pointCount == 3 }
        await<Any?> { done -> recorder.interrupt { done(it) } }
        assertEquals(RecorderState.Idle, recorder.state.value)

        val action = decide()
        assertEquals(LinkGrantAction.Resume(id), action)

        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.resume((action as LinkGrantAction.Resume).recordingId, done) })
        assertEquals(id, runningId())
        gps.push(2_000.0)
        waitUntil("4 points") { database.recordingDao().recording(id)?.pointCount == 4 }
        val recording = database.recordingDao().recording(id)!!
        assertEquals(1, recording.segment)
        assertEquals(listOf(0, 0, 0, 1), database.recordingDao().pointsAfter(id, 0, 100).map { it.segment })
        assertEquals(1, recordingsCount())
        assertEquals("the upload is still waiting for the stop", UploadState.WAITING, database.uploadDao().upload(id)?.state)
    }

    @Test
    fun aRecordingLeftRunningByADeadProcessIsResumedToo() {
        assertEquals(StartResult.STARTED, startLinked())
        val id = runningId()
        // The process died: this recorder knows nothing about the recording, the database says RECORDING.
        val other = Recorder(context, database, tokens = tokens)
        try {
            await<Any?> { done -> recorder.interrupt { done(it) } }
            database.openHelper.writableDatabase.execSQL("UPDATE recordings SET state = 'RECORDING', ended_at_millis = NULL WHERE id = $id")

            val grant = database.grantDao().grant(GrantHash.of(link.token))
            val action = GrantDecision.decide(grant, database.recordingDao().recording(id), runningRecordingId = null, nowMillis = System.currentTimeMillis())
            assertEquals(LinkGrantAction.Resume(id), action)

            assertEquals(StartResult.STARTED, await<StartResult> { done -> other.resume(id, done) })
            assertEquals(1, database.recordingDao().recording(id)?.segment)
        } finally {
            runCatching { await<Unit> { done -> other.stop { done(Unit) } } }
            other.quit()
        }
    }

    @Test
    fun theSameLinkAfterTheStopStartsNothingNew() {
        assertEquals(StartResult.STARTED, startLinked())
        val id = runningId()
        gps.push(0.0)
        waitUntil("1 point") { database.recordingDao().recording(id)?.pointCount == 1 }
        await<Unit> { done -> recorder.stop { done(Unit) } }

        assertEquals(LinkGrantAction.AlreadyUsed, decide())
        assertEquals(StartResult.LINK_USED, startLinked())

        assertEquals(1, recordingsCount())
        assertEquals(RecorderState.Idle, recorder.state.value)
        assertEquals(RecordingState.FINISHED, database.recordingDao().recording(id)?.state)
    }

    @Test
    fun theGrantOutlivesTheDeletedRecording() {
        assertEquals(StartResult.STARTED, startLinked())
        val id = runningId()
        await<Unit> { done -> recorder.stop { done(Unit) } }
        val hash = GrantHash.of(link.token)
        assertEquals(id, database.grantDao().grant(hash)?.recordingId)

        assertEquals(1, database.recordingDao().deleteUnlessRecording(id))

        // The grant row stays, without its recording: the link is still used up.
        val grant = database.grantDao().grant(hash)
        assertNotNull(grant)
        assertNull(grant!!.recordingId)
        assertEquals(LinkGrantAction.AlreadyUsed, decide())
        assertEquals(StartResult.LINK_USED, startLinked())
        assertEquals(0, recordingsCount())
    }

    @Test
    fun aGrantThatRanOutIsGoneAfterMaintenanceAndItsLinkIsExpiredAnyway() {
        assertEquals(StartResult.STARTED, startLinked())
        await<Unit> { done -> recorder.stop { done(Unit) } }
        // After expiry the parser refuses the link before any grant is looked up, so the row may go.
        assertEquals(1, database.grantDao().deleteExpired(link.expiresAtMillis))
        assertNull(database.grantDao().grant(GrantHash.of(link.token)))
    }

    private fun <T> await(call: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var value: T? = null
        call {
            value = it
            latch.countDown()
        }
        assertTrue("callback not called in time", latch.await(10, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            assertTrue("Timed out waiting for: $what", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TEST_DB = "routelog-one-link-test.db"
        const val TEST_TOKENS = "upload_tokens_test_one_link"
    }
}
