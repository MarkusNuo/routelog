package io.github.markusnuo.routelog.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.data.UploadEntity
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.link.GrantHash
import io.github.markusnuo.routelog.link.RecordingLink
import io.github.markusnuo.routelog.recording.Recorder.StartResult
import io.github.markusnuo.routelog.testing.MockGps
import io.github.markusnuo.routelog.testing.MockGps.Companion.STEP_METERS
import io.github.markusnuo.routelog.upload.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A recording started from a link keeps its upload target and tells the uploads when it ends. */
@RunWith(AndroidJUnit4::class)
class LinkedRecordingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val gps = MockGps(context)

    private lateinit var database: RoutelogDatabase
    private lateinit var recorder: Recorder
    private lateinit var tokens: TokenStore
    private val created = CopyOnWriteArrayList<Pair<Long, Long>>()
    private val queued = CopyOnWriteArrayList<Long>()
    private var now = System.currentTimeMillis()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val link = RecordingLink(
        uploadUrl = "https://app.example.com/api/routes/42",
        host = "app.example.com",
        secure = true,
        token = "tok-xyz",
        label = "Lesson 14:00",
        expiresAtMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1),
    )

    @Before
    fun open() {
        context.deleteDatabase(TEST_DB)
        database = RoutelogDatabase.build(context, TEST_DB)
        tokens = TokenStore(context, TEST_TOKENS).also { it.clear() }
        recorder = Recorder(
            context,
            database,
            listener = object : Recorder.Listener {
                override fun onLinkedRecordingCreated(recordingId: Long, expiresAtMillis: Long) {
                    created += recordingId to expiresAtMillis
                }

                override fun onUploadQueued(recordingId: Long) {
                    queued += recordingId
                }
            },
            clock = { now },
            tokens = tokens,
        )
    }

    @After
    fun close() {
        runCatching { await<Unit> { done -> recorder.stop { done(Unit) } } }
        recorder.quit()
        scope.cancel()
        tokens.clear()
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun aLinkedRecordingStoresItsTargetAndReportsTheStop() {
        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.start(link, done) })
        val id = (recorder.state.value as RecorderState.Recording).recordingId

        val upload = database.uploadDao().upload(id)!!
        assertEquals(UploadState.WAITING, upload.state)
        assertEquals(link.uploadUrl, upload.uploadUrl)
        assertEquals("app.example.com", upload.host)
        assertEquals("Lesson 14:00", upload.label)
        assertEquals(link.expiresAtMillis, upload.expiresAtMillis)
        assertEquals(listOf(id to link.expiresAtMillis), created.toList())
        // The token is not in the database (and so not in a backup of it), only in the token store.
        assertEquals("tok-xyz", tokens.get(id))
        assertFalse(
            "no token column",
            database.openHelper.readableDatabase.query("SELECT * FROM uploads").use { cursor -> "token" in cursor.columnNames },
        )

        repeat(2) { gps.push(it * STEP_METERS) }
        waitUntil("2 points") { database.recordingDao().recording(id)?.pointCount == 2 }
        await<Unit> { done -> recorder.stop { done(Unit) } }

        assertEquals(RecordingState.FINISHED, database.recordingDao().recording(id)?.state)
        // Finished and queued in one go: when the stop is through, the upload is already PENDING.
        assertEquals(UploadState.PENDING, database.uploadDao().upload(id)?.state)
        assertEquals(listOf(id), queued.toList())
    }

    @Test
    fun aRecordingWithoutLinkHasNoUpload() {
        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.start(onResult = done) })
        val id = (recorder.state.value as RecorderState.Recording).recordingId
        assertEquals(null, database.uploadDao().upload(id))
        assertTrue(created.isEmpty())
        assertNull(tokens.get(id))
        await<Unit> { done -> recorder.stop { done(Unit) } }
        assertTrue("nothing to upload for a plain recording", queued.isEmpty())
    }

    @Test
    fun finishAndUploadEndsAnInterruptedRecordingOnce() {
        val recordings = database.recordingDao()
        val id = recordings.insertRecording(RecordingEntity(startedAtMillis = 1_000, endedAtMillis = 5_000, state = RecordingState.INTERRUPTED))
        database.uploadDao().insert(
            UploadEntity(id, link.uploadUrl, link.host, link.label, link.expiresAtMillis, UploadState.WAITING),
        )

        assertTrue(await { done -> recorder.finishInterrupted(id, done) })
        assertEquals(RecordingState.FINISHED, recordings.recording(id)?.state)
        assertEquals(5_000L, recordings.recording(id)?.endedAtMillis)
        assertEquals(UploadState.PENDING, database.uploadDao().upload(id)?.state)
        assertEquals(listOf(id), queued.toList())

        assertFalse("not interrupted anymore", await { done -> recorder.finishInterrupted(id, done) })
        assertFalse(await { done -> recorder.resume(id) { done(it == StartResult.STARTED) } })
        assertEquals(listOf(id), queued.toList())
    }

    @Test
    fun finishingAnInterruptedRecordingWithoutUploadQueuesNothing() {
        val id = database.recordingDao().insertRecording(RecordingEntity(startedAtMillis = 1_000, endedAtMillis = 5_000, state = RecordingState.INTERRUPTED))
        assertTrue(await { done -> recorder.finishInterrupted(id, done) })
        assertEquals(RecordingState.FINISHED, database.recordingDao().recording(id)?.state)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun aGrantThatRanOutBeforeTheStartIsRefusedAndNothingIsWritten() {
        val events = collectEvents()
        now = link.expiresAtMillis // exclusive: at this instant it is over

        assertEquals(StartResult.LINK_EXPIRED, await<StartResult> { done -> recorder.start(link, done) })

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertFalse(Recorder.isRecordingInThisProcess)
        assertEquals(0, count("recordings"))
        assertEquals(0, count("uploads"))
        assertEquals(0, count("grants"))
        assertFalse("no token was stored", File(context.noBackupFilesDir, "$TEST_TOKENS.properties").exists())
        assertTrue(created.isEmpty())
        waitUntil("event") { events.contains(RecorderEvent.LinkExpired) }
    }

    @Test
    fun aStartThatFindsTheGrantAlreadyUsedIsRefused() {
        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.start(link, done) })
        await<Unit> { done -> recorder.stop { done(Unit) } }
        val events = collectEvents()

        assertEquals(StartResult.LINK_USED, await<StartResult> { done -> recorder.start(link, done) })

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertEquals(1, count("recordings"))
        waitUntil("event") { events.contains(RecorderEvent.LinkUsed) }
    }

    @Test
    fun aLinkedStartWhileRecordingTellsTheUserItIsBusy() {
        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.start(onResult = done) })
        val events = collectEvents()

        val other = link.copy(token = "tok-other")
        assertEquals(StartResult.ALREADY_RECORDING, await<StartResult> { done -> recorder.start(other, done) })

        waitUntil("event") { events.contains(RecorderEvent.LinkBusy) }
        assertEquals(1, count("recordings"))
        assertEquals("nothing of the refused link is kept", 0, count("grants"))
        // A plain second start is refused as before, without a message of its own.
        events.clear()
        assertEquals(StartResult.ALREADY_RECORDING, await<StartResult> { done -> recorder.start(onResult = done) })
        Thread.sleep(300)
        assertFalse(events.contains(RecorderEvent.LinkBusy))
    }

    @Test
    fun theGrantIsKeptAsAHashOfTheToken() {
        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.start(link, done) })
        val id = (recorder.state.value as RecorderState.Recording).recordingId

        val grant = database.grantDao().grant(GrantHash.of("tok-xyz"))!!
        assertEquals(id, grant.recordingId)
        assertEquals(link.expiresAtMillis, grant.expiresAtMillis)
        assertEquals(64, grant.tokenHash.length)
        // No column of the grant row holds the token itself.
        database.openHelper.readableDatabase.query("SELECT * FROM grants").use { cursor ->
            while (cursor.moveToNext()) {
                for (i in 0 until cursor.columnCount) assertFalse(cursor.getString(i).contains("tok-xyz"))
            }
        }
    }

    private fun count(table: String) = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    /** Events are not kept; collect from now on. UNDISPATCHED: subscribed before this returns. */
    private fun collectEvents(): MutableList<RecorderEvent> {
        val events = CopyOnWriteArrayList<RecorderEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { recorder.events.collect { events.add(it) } }
        return events
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
        const val TEST_DB = "routelog-linked-test.db"
        const val TEST_TOKENS = "upload_tokens_test_linked"
    }
}
