package io.github.markusnuo.routelog.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.data.RecordingDao
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.data.TrackPointEntity
import io.github.markusnuo.routelog.recording.Recorder.StartResult
import io.github.markusnuo.routelog.testing.MockGps
import io.github.markusnuo.routelog.testing.MockGps.Companion.STEP_METERS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the real [Recorder] through the real LocationManager, fed by [MockGps]. The service is not
 * involved: it only starts and stops the recorder.
 */
@RunWith(AndroidJUnit4::class)
class RecorderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val gps = MockGps(context)

    private lateinit var database: RoutelogDatabase
    private lateinit var dao: RecordingDao
    private lateinit var recorder: Recorder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun open() {
        context.deleteDatabase(TEST_DB)
        database = RoutelogDatabase.build(context, TEST_DB)
        dao = database.recordingDao()
        recorder = Recorder(context, database)
    }

    @After
    fun close() {
        // A failed test must not leave a GPS listener behind.
        runCatching { await<Unit> { done -> recorder.stop { done(Unit) } } }
        recorder.quit()
        scope.cancel()
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun everyFixIsStoredAndCounted() {
        assertEquals(StartResult.STARTED, startNew())
        assertTrue(Recorder.isRecordingInThisProcess)
        val id = runningId()

        val fixes = (0 until 10).map { gps.push(it * STEP_METERS) }
        waitUntil("10 points stored") { dao.recording(id)?.pointCount == 10 }

        val points = stored(id)
        assertEquals(fixes.map { it.time }, points.map { it.timeMillis })
        assertEquals(fixes.map { it.latitude }, points.map { it.latitude })
        assertEquals(fixes.map { it.longitude }, points.map { it.longitude })
        assertTrue(points.all { it.segment == 0 && it.accuracyMeters == MockGps.ACCURACY && it.speedMetersPerSecond == MockGps.STEP_SPEED })
        val recording = dao.recording(id)!!
        assertEquals(RecordingState.RECORDING, recording.state)
        assertEquals(fixes.last().time, recording.lastPointAtMillis)
        assertEquals(9 * STEP_METERS, recording.distanceMeters, 0.5)
        val state = recorder.state.value as RecorderState.Recording
        assertEquals(10, state.pointCount)
        assertEquals(9 * STEP_METERS, state.distanceMeters, 0.5)
        assertFalse(state.storageError)
    }

    @Test
    fun stoppingFinishesTheRecordingAndEndsGps() {
        assertEquals(StartResult.STARTED, startNew())
        val id = runningId()
        repeat(3) { gps.push(it * STEP_METERS) }
        waitUntil("3 points stored") { dao.recording(id)?.pointCount == 3 }

        await<Unit> { done -> recorder.stop { done(Unit) } }

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertFalse(Recorder.isRecordingInThisProcess)
        val recording = dao.recording(id)!!
        assertEquals(RecordingState.FINISHED, recording.state)
        assertNotNull(recording.endedAtMillis)
        // GPS is off: a fix that arrives now goes nowhere.
        gps.push(3 * STEP_METERS)
        Thread.sleep(1_500)
        assertEquals(3, stored(id).size)
        assertEquals(3, dao.recording(id)!!.pointCount)
    }

    @Test
    fun resumingAnInterruptedRecordingContinuesInANewSegment() {
        assertEquals(StartResult.STARTED, startNew())
        val id = runningId()
        val first = (0 until 3).map { gps.push(it * STEP_METERS) }
        waitUntil("3 points stored") { dao.recording(id)?.pointCount == 3 }

        val beforeInterrupt = System.currentTimeMillis()
        val interrupted = await<RecordingEntity?> { done -> recorder.interrupt(done) }
        assertEquals(RecordingState.INTERRUPTED, interrupted?.state)
        // The shown duration ends at the last point; the 12-hour resume window counts from now.
        assertEquals(first.last().time, interrupted!!.endedAtMillis)
        assertTrue(interrupted.aliveAtMillis!! >= beforeInterrupt)
        assertEquals(RecorderState.Idle, recorder.state.value)
        assertFalse(Recorder.isRecordingInThisProcess)

        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.resume(id, done) })
        assertTrue(Recorder.isRecordingInThisProcess)
        assertEquals(id, runningId())
        // The car went 2 km while nobody recorded; that stretch is not part of the distance.
        val second = (0 until 2).map { gps.push(2_000.0 + it * STEP_METERS) }
        waitUntil("5 points stored") { dao.recording(id)?.pointCount == 5 }

        val points = stored(id)
        assertEquals((first + second).map { it.time }, points.map { it.timeMillis })
        assertEquals(listOf(0, 0, 0, 1, 1), points.map { it.segment })
        val recording = dao.recording(id)!!
        assertEquals(RecordingState.RECORDING, recording.state)
        assertNull(recording.endedAtMillis)
        assertEquals(1, recording.segment)
        assertEquals(3 * STEP_METERS, recording.distanceMeters, 0.5)

        await<Unit> { done -> recorder.stop { done(Unit) } }
        assertEquals(RecordingState.FINISHED, dao.recording(id)?.state)
        assertEquals(1, recordingsCount())
    }

    @Test
    fun onlyAnInterruptedRecordingCanBeResumed() {
        val events = collectEvents()
        val finished = dao.insertRecording(RecordingEntity(startedAtMillis = 1_000, endedAtMillis = 2_000, state = RecordingState.FINISHED))
        val deleted = dao.insertRecording(RecordingEntity(startedAtMillis = 1_000, endedAtMillis = 2_000, state = RecordingState.INTERRUPTED))
        dao.delete(deleted)

        for (id in listOf(finished, deleted, 12_345L)) {
            assertEquals("id $id", StartResult.NOT_RESUMABLE, await<StartResult> { done -> recorder.resume(id, done) })
            assertEquals(RecorderState.Idle, recorder.state.value)
            assertFalse(Recorder.isRecordingInThisProcess)
        }

        waitUntil("event for each refusal") { events.count { it == RecorderEvent.NotResumable } == 3 }
        val untouched = dao.recording(finished)!!
        assertEquals(RecordingState.FINISHED, untouched.state)
        assertEquals(0, untouched.segment)
        assertEquals(2_000L, untouched.endedAtMillis)
    }

    @Test
    fun anInterruptionOlderThan12HoursIsNotResumed() {
        val events = collectEvents()
        val now = System.currentTimeMillis()
        val tooOld = dao.insertRecording(
            RecordingEntity(startedAtMillis = now - TimeUnit.HOURS.toMillis(14), endedAtMillis = now - TimeUnit.HOURS.toMillis(13), state = RecordingState.INTERRUPTED),
        )
        assertEquals(StartResult.RESUME_TOO_LATE, await<StartResult> { done -> recorder.resume(tooOld, done) })
        assertEquals(RecorderState.Idle, recorder.state.value)
        assertFalse(Recorder.isRecordingInThisProcess)
        waitUntil("event") { events.contains(RecorderEvent.ResumeTooLate) }
        val untouched = dao.recording(tooOld)!!
        assertEquals(RecordingState.INTERRUPTED, untouched.state)
        assertEquals(0, untouched.segment)

        val recent = dao.insertRecording(
            RecordingEntity(startedAtMillis = now - TimeUnit.HOURS.toMillis(12), endedAtMillis = now - TimeUnit.HOURS.toMillis(11), state = RecordingState.INTERRUPTED),
        )
        assertEquals(StartResult.STARTED, await<StartResult> { done -> recorder.resume(recent, done) })
        assertEquals(1, dao.recording(recent)?.segment)
    }

    @Test
    fun theRecorderNotesThatItStillRunsAlsoWithoutGps() {
        // The recorder thread reads the clock.
        val now = AtomicLong(1_000_000L)
        val ticking = Recorder(context, database, clock = now::get, aliveIntervalMillis = 200)
        try {
            assertEquals(StartResult.STARTED, await<StartResult> { done -> ticking.start(onResult = done) })
            val id = (ticking.state.value as RecorderState.Recording).recordingId
            now.set(1_060_000L)
            waitUntil("marked alive") { dao.recording(id)?.aliveAtMillis == 1_060_000L }
            now.set(1_120_000L)
            waitUntil("marked alive again") { dao.recording(id)?.aliveAtMillis == 1_120_000L }
            assertEquals(0, dao.recording(id)?.pointCount)
        } finally {
            await<Unit> { done -> ticking.stop { done(Unit) } }
            ticking.quit()
        }
    }

    @Test
    fun aResumedRecordingThatDiesWithoutGpsCountsFromTheResume() {
        // Interrupted at 08:00, resumed at 19:00, no fix indoors, the process dies.
        val hour = TimeUnit.HOURS.toMillis(1)
        val day = System.currentTimeMillis() - 12 * hour
        val id = dao.insertRecording(
            RecordingEntity(
                startedAtMillis = day + 7 * hour,
                lastPointAtMillis = day + 8 * hour,
                endedAtMillis = day + 8 * hour,
                pointCount = 1,
                state = RecordingState.INTERRUPTED,
            ),
        )
        val now = AtomicLong(day + 19 * hour)
        val dying = Recorder(context, database, clock = now::get)
        try {
            assertEquals(StartResult.STARTED, await<StartResult> { done -> dying.resume(id, done) })
            val resumed = dao.recording(id)!!
            assertEquals(day + 19 * hour, resumed.aliveAtMillis)
            // The process dies: end this recorder's session, then put the row back the way a dead
            // process leaves it (still RECORDING, no end, the alive time of the resume).
            now.set(day + 19 * hour + 5 * 60_000)
            await<Unit> { done -> dying.stop { done(Unit) } }
            database.openHelper.writableDatabase.execSQL(
                "UPDATE recordings SET state = 'RECORDING', ended_at_millis = NULL, alive_at_millis = ${resumed.aliveAtMillis} WHERE id = $id",
            )

            // The next process (the app start, the boot receiver) finds it.
            val swept = await<List<RecordingEntity>> { done -> recorder.sweepOrphans(done) }
            val orphan = swept.single { it.id == id }
            assertEquals(RecordingState.INTERRUPTED, orphan.state)
            // The shown duration still ends at the last point ...
            assertEquals(day + 8 * hour, orphan.endedAtMillis)
            val stored = dao.recording(id)!!
            assertEquals(day + 8 * hour, stored.endedAtMillis)
            assertEquals(day + 19 * hour, stored.aliveAtMillis)
            // ... but the resume window runs from the resume, not from 08:00: it can still be resumed.
            assertTrue(ResumeWindow.isOpen(stored, day + 19 * hour + 11 * hour))
            assertFalse(ResumeWindow.isOpen(stored, day + 19 * hour + 13 * hour))
        } finally {
            runCatching { await<Unit> { done -> dying.stop { done(Unit) } } }
            dying.quit()
        }
    }

    @Test
    fun aSecondStartWhileRecordingIsRefused() {
        assertEquals(StartResult.STARTED, startNew())
        val id = runningId()
        assertEquals(StartResult.ALREADY_RECORDING, startNew())
        assertEquals(id, runningId())
        assertEquals(1, recordingsCount())
    }

    @Test
    fun aFixThatCannotBeSavedDoesNotEndTheRecording() {
        assertEquals(StartResult.STARTED, startNew())
        val id = runningId()
        gps.push(0.0)
        waitUntil("1 point stored") { dao.recording(id)?.pointCount == 1 }

        // Take the table away: the next fix fails to save.
        val db = database.openHelper.writableDatabase
        db.execSQL("ALTER TABLE track_points RENAME TO track_points_away")
        try {
            gps.push(STEP_METERS)
            waitUntil("storage error shown") { (recorder.state.value as? RecorderState.Recording)?.storageError == true }
        } finally {
            db.execSQL("ALTER TABLE track_points_away RENAME TO track_points")
        }

        // Still recording: the next fix is saved and the error is gone.
        gps.push(2 * STEP_METERS)
        waitUntil("2 points stored") { dao.recording(id)?.pointCount == 2 }
        waitUntil("storage error gone") { (recorder.state.value as? RecorderState.Recording)?.storageError == false }
        assertEquals(RecordingState.RECORDING, dao.recording(id)?.state)
        assertEquals(2, stored(id).size)
    }

    @Test
    fun aClaimedStartKeepsEveryoneElseOutUntilItIsGivenBack() {
        assertTrue(recorder.claimStart())
        assertEquals(RecorderState.Starting, recorder.state.value)
        assertFalse("a second claim loses", recorder.claimStart())

        recorder.releaseStartClaim()

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertTrue("free again", recorder.claimStart())
        recorder.releaseStartClaim()
    }

    @Test
    fun aClaimedStartThatTheRecorderCarriesOutEndsInARecording() {
        assertTrue(recorder.claimStart())

        assertEquals(StartResult.STARTED, startNew())

        assertTrue(recorder.state.value is RecorderState.Recording)
        assertFalse("nobody can claim a recording that runs", recorder.claimStart())
    }

    @Test
    fun aClaimedStartThatTheRecorderRefusesEndsInIdle() {
        assertTrue(recorder.claimStart())

        // No such recording: the start fails and the claim must not stay.
        assertEquals(StartResult.NOT_RESUMABLE, await { done -> recorder.resume(987_654L, done) })

        assertEquals(RecorderState.Idle, recorder.state.value)
        assertTrue(recorder.claimStart())
        recorder.releaseStartClaim()
    }

    @Test
    fun givingBackAClaimDoesNotTouchARunningRecording() {
        assertEquals(StartResult.STARTED, startNew())

        recorder.releaseStartClaim()

        assertTrue(recorder.state.value is RecorderState.Recording)
    }

    @Test
    fun onlyAnInterruptedRecordingWithinTheResumeWindowIsResumable() {
        val now = System.currentTimeMillis()
        fun insert(state: RecordingState, endedAgoMillis: Long) = dao.insertRecording(
            RecordingEntity(startedAtMillis = now - endedAgoMillis - 60_000, endedAtMillis = now - endedAgoMillis, state = state),
        )
        val fresh = insert(RecordingState.INTERRUPTED, 60_000)
        val old = insert(RecordingState.INTERRUPTED, TimeUnit.HOURS.toMillis(13))
        val finished = insert(RecordingState.FINISHED, 60_000)

        assertEquals(fresh, await<RecordingEntity?> { done -> recorder.resumableRecording(fresh, done) }?.id)
        assertNull(await<RecordingEntity?> { done -> recorder.resumableRecording(old, done) })
        assertNull(await<RecordingEntity?> { done -> recorder.resumableRecording(finished, done) })
        assertNull(await<RecordingEntity?> { done -> recorder.resumableRecording(987_654L, done) })
    }

    private fun startNew(): StartResult = await { done -> recorder.start(onResult = done) }

    private fun runningId() = (recorder.state.value as RecorderState.Recording).recordingId

    private fun stored(recordingId: Long): List<TrackPointEntity> = dao.pointsAfter(recordingId, 0, 10_000)

    private fun recordingsCount() = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM recordings").use { it.moveToFirst(); it.getInt(0) }

    /** Events are not kept; collect from now on. UNDISPATCHED: subscribed before this returns. */
    private fun collectEvents(): List<RecorderEvent> {
        val events = CopyOnWriteArrayList<RecorderEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { recorder.events.collect { events.add(it) } }
        return events
    }

    /** Calls [call] with a callback and waits for it to be invoked. */
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
        const val TEST_DB = "routelog-recorder-test.db"
    }
}
