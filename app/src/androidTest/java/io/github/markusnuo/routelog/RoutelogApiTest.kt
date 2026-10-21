package io.github.markusnuo.routelog

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.markusnuo.routelog.data.GrantEntity
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.TrackPointEntity
import io.github.markusnuo.routelog.data.UploadEntity
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.link.GrantHash
import io.github.markusnuo.routelog.link.LinkParseResult
import io.github.markusnuo.routelog.recording.RecorderState
import io.github.markusnuo.routelog.recording.RecordingService
import io.github.markusnuo.routelog.recording.RoutelogResumeActivity
import io.github.markusnuo.routelog.start.StartBlocker
import io.github.markusnuo.routelog.testing.MockGps
import io.github.markusnuo.routelog.testing.TestServer
import io.github.markusnuo.routelog.ui.MainActivity
import io.github.markusnuo.routelog.upload.UploadRetention
import io.github.markusnuo.routelog.upload.UploadWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The library's API used the way a host app uses it: record with an upload, stop, upload,
 * the same token again, delete, export, and the resume screen of the library. Runs in the routelog
 * app's process with the app's real recorder and database.
 *
 * Leaves nothing behind: every token is new, every recording the test made (or inserted) is
 * deleted through [Routelog.delete] (which also ends its upload and expiry jobs and its notices),
 * the grants of the test's tokens are removed, and the notices it caused are cancelled.
 */
@RunWith(AndroidJUnit4::class)
class RoutelogApiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    // Keeps the app visible: only a visible app may start a location foreground service.
    @get:Rule
    val gps = MockGps(context)

    private val graph get() = AppGraph.get(context)
    private val dao get() = graph.database.recordingDao()
    private lateinit var server: TestServer
    private var testStartedAtMillis = 0L

    // What the test made itself, so that the clean-up removes exactly that.
    private val tokens = mutableListOf<String>()
    private val insertedIds = mutableListOf<Long>()
    private var retentionBefore: UploadRetention? = null
    private val startedActivities = IntentSpy()

    /** Records every activity that is started in the process, without changing what happens. */
    private class IntentSpy : Instrumentation.ActivityMonitor() {
        val intents = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            intents += Intent(intent)
            return null
        }

        /** What the resume screen opened: the host app's (here: the routelog app's) screen. */
        fun hostIntents() = intents.filter { it.component?.className == MainActivity::class.java.name }
    }

    @Before
    fun setUp() {
        testStartedAtMillis = System.currentTimeMillis()
        MockGps.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        server = TestServer()
        assertEquals("the app's recorder must be idle before the test", RecorderState.Idle, Routelog.state(context).value)
        graph.notifications.cancelInterrupted()
        instrumentation.addMonitor(startedActivities)
    }

    @After
    fun cleanUp() {
        try {
            Routelog.stop(context)
            waitFor { Routelog.state(context).value == RecorderState.Idle }
        } finally {
            instrumentation.removeMonitor(startedActivities)
            retentionBefore?.let { Routelog.setUploadRetention(context, it) }
            val own = runBlocking { dao.observeRecordingList().first() }
                .map { it.recording.id }
                .filter { dao.recording(it)?.startedAtMillis?.let { started -> started >= testStartedAtMillis } == true }
            val ids = (own + insertedIds).toSet()
            // An upload that is just being finished would post its problem notice after the delete
            // has removed it: let the uploads of this test end first (the server still answers).
            waitFor { ids.none { graph.database.uploadDao().upload(it)?.state == UploadState.PENDING } }
            waitFor { !UploadWorker.isBusy }
            server.close()
            ids.forEach(::remove)
            tokens.forEach { graph.database.grantDao().delete(GrantHash.of(it)) }
            graph.notifications.cancelInterrupted()
            assertNothingLeft(ids)
        }
    }

    // The test must leave nothing of its own behind: no row, token, grant, scheduled job or notice.
    private fun assertNothingLeft(ids: Set<Long>) {
        val left = mutableListOf<String>()
        for (id in ids) {
            if (dao.recording(id) != null) left += "recording $id"
            if (graph.tokens.get(id) != null) left += "token of $id"
            if (graph.uploadScheduler.isUploadScheduled(id)) left += "upload job of $id"
            if (graph.uploadScheduler.isExpiryScheduled(id)) left += "expiry job of $id"
        }
        for (token in tokens) {
            if (graph.database.grantDao().grant(GrantHash.of(token)) != null) left += "a grant"
        }
        waitFor(3_000) { notificationsOfTheTest(ids).isEmpty() }
        left += notificationsOfTheTest(ids).map { "notification $it" }
        assertTrue("left behind: $left", left.isEmpty())
    }

    private fun notificationsOfTheTest(ids: Set<Long>): List<Int> {
        val own = ids.map { RoutelogCore.get(context).config.uploadProblemNotificationId(it) } + graph.notifications.interruptedNotificationId
        return notificationManager.activeNotifications.map { it.id }.filter { it in own }
    }

    // Through the library's delete: it ends the jobs, the token and the notices of the recording as well.
    private fun remove(id: Long) {
        if (!runBlocking { Routelog.delete(context, id) }) {
            // Still running (the test failed before it stopped) or gone: take it out by force.
            graph.uploads.forget(id)
            dao.delete(id)
        }
    }

    private fun newToken() = ("api-" + UUID.randomUUID()).also { tokens += it }

    private fun request(token: String, expiresInMillis: Long = TimeUnit.HOURS.toMillis(6)) = UploadRequest(
        uploadUrl = "${server.baseUrl}/r/1",
        token = token,
        label = "Lesson 7",
        expiresAtMillis = System.currentTimeMillis() + expiresInMillis,
    )

    private fun startWithUpload(request: UploadRequest) = runBlocking { Routelog.startWithUpload(context, request) }

    private fun runningId(): Long {
        waitUntil("recording") { Routelog.state(context).value is RecorderState.Recording }
        return (Routelog.state(context).value as RecorderState.Recording).recordingId
    }

    private fun stopAndWait() {
        Routelog.stop(context)
        waitUntil("idle") { Routelog.state(context).value == RecorderState.Idle }
    }

    /** A row as a dead process or an earlier interruption would have left it. */
    private fun insertRecording(state: RecordingState, endedAgoMillis: Long = 60_000, points: Int = 0): Long {
        val end = System.currentTimeMillis() - endedAgoMillis
        val id = dao.insertRecording(
            RecordingEntity(
                startedAtMillis = end - 60_000,
                endedAtMillis = if (state == RecordingState.RECORDING) null else end,
                state = state,
            ),
        )
        insertedIds += id
        repeat(points) {
            dao.insertPoint(
                TrackPointEntity(
                    recordingId = id, segment = 0, timeMillis = end - 60_000 + it * 1_000L, latitude = 47.4 + it * 0.0001, longitude = 9.37,
                    altitudeMeters = null, mslAltitudeMeters = null, accuracyMeters = 5f, speedMetersPerSecond = null, bearingDegrees = null,
                ),
            )
        }
        return id
    }

    /** A recording that [token] started: its row and its grant (the upload is not needed for what is tested). */
    private fun insertRecordingOf(token: String, state: RecordingState, endedAgoMillis: Long): Long {
        val id = insertRecording(state, endedAgoMillis)
        graph.database.grantDao().insert(GrantEntity(GrantHash.of(token), id, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(6)))
        return id
    }

    private fun interruptedNoticeShown() = notificationManager.activeNotifications.any { it.id == graph.notifications.interruptedNotificationId }

    private fun showInterruptedNotice(recordingId: Long) {
        graph.notifications.showInterrupted(dao.recording(recordingId)!!)
        waitUntil("the notice") { interruptedNoticeShown() }
    }

    private fun withGpsOff(block: () -> Unit) {
        // (The test's GPS provider is switched off directly: on Android 17 it stays on when
        // location as a whole is switched off.)
        val locationManager = context.getSystemService(LocationManager::class.java)
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        try {
            block()
        } finally {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        }
    }

    private fun recordingIdsSinceTestStart() = runBlocking { dao.observeRecordingList().first() }
        .map { it.recording }
        .filter { it.startedAtMillis >= testStartedAtMillis && it.id !in insertedIds }
        .map { it.id }

    @Test
    fun aRecordingWithUploadReachesTheServerWithItsToken() {
        val token = newToken()
        assertEquals(StartOutcome.Started, startWithUpload(request(token)))
        val id = runningId()
        repeat(3) { gps.push(it * 20.0) }
        waitUntil("3 points") { (dao.recording(id)?.pointCount ?: 0) >= 3 }
        stopAndWait()

        // The upload job usually runs at once; if JobScheduler holds it back, run it now.
        waitFor(20_000) { server.requests.isNotEmpty() }
        if (server.requests.isEmpty()) {
            MockGps.shell("cmd jobscheduler run -f ${context.packageName} ${RoutelogConfig.legacy().uploadJobId(id)}")
        }
        waitUntil("upload done", timeoutMillis = 30_000) { graph.database.uploadDao().upload(id)?.state == UploadState.DONE }
        val put = server.requests.first()
        assertEquals("PUT", put.method)
        assertEquals("/r/1", put.path)
        assertEquals("Bearer $token", put.headers["authorization"])
        assertEquals("application/gpx+xml", put.headers["content-type"])
        assertTrue(put.bodyText.contains("<name>Lesson 7</name>"))
        assertEquals("one upload", 1, server.requests.size)
    }

    @Test
    fun oneTokenGivesOneRecording() {
        val first = request(newToken())
        assertEquals(StartOutcome.Started, startWithUpload(first))
        val id = runningId()

        assertEquals("same token while it records", StartOutcome.AlreadyRecording, startWithUpload(first))
        assertEquals("another token while one records", StartOutcome.Busy, startWithUpload(request(newToken())))

        stopAndWait()
        // The state is Idle a moment before the row is written as finished.
        waitUntil("finished") { dao.recording(id)?.state == RecordingState.FINISHED }
        assertEquals("stopped: used", StartOutcome.AlreadyUsed, startWithUpload(first))
    }

    @Test
    fun theSameTokenAfterAnInterruptionContinuesThatRecording() {
        val upload = request(newToken())
        assertEquals(StartOutcome.Started, startWithUpload(upload))
        val id = runningId()
        await<Any?> { done -> graph.recorder.interrupt { done(it) } }
        assertEquals(RecordingState.INTERRUPTED, dao.recording(id)?.state)

        assertEquals(StartOutcome.Started, startWithUpload(upload))
        assertEquals(id, runningId())
        assertEquals(1, dao.recording(id)?.segment)
    }

    @Test
    fun invalidRequestsAreRefusedBeforeAnythingStarts() {
        val expired = request(newToken(), expiresInMillis = -1_000)
        assertEquals(StartOutcome.Invalid(LinkParseResult.Reason.EXPIRED), startWithUpload(expired))
        val badToken = request("with space")
        assertEquals(StartOutcome.Invalid(LinkParseResult.Reason.BAD_PARAMETERS), startWithUpload(badToken))
        assertEquals(RecorderState.Idle, Routelog.state(context).value)
    }

    @Test
    fun aMissingConditionIsReportedNotStarted() {
        // (Revoking a permission would end the app's process, which is this test; a GPS that is
        // switched off can be switched on again.)
        withGpsOff {
            assertEquals(StartOutcome.Blocked(StartBlocker.GPS_OFF), Routelog.start(context))
            assertEquals(StartOutcome.Blocked(StartBlocker.GPS_OFF), startWithUpload(request(newToken())))
            // Nothing was claimed: the recorder is free for the next start.
            assertEquals(RecorderState.Idle, Routelog.state(context).value)
        }
        assertEquals(StartOutcome.Started, Routelog.start(context))
    }

    // --- a start is claimed before the service is asked ----------------------------------------------

    @Test
    fun twoQuickStartsWithTheSameTokenGiveOneRecording() {
        val upload = request(newToken())

        // The second call comes before the service got to the first: the state says Starting.
        val first = startWithUpload(upload)
        val second = startWithUpload(upload)

        assertEquals(StartOutcome.Started, first)
        assertEquals(StartOutcome.AlreadyRecording, second)
        runningId()
        assertEquals("one recording", 1, recordingIdsSinceTestStart().size)
    }

    @Test
    fun twoStartsAtTheSameMomentWithTheSameTokenGiveOneRecording() {
        repeat(5) { round ->
            val upload = request(newToken())
            val outcomes = both { Routelog.startWithUpload(context, upload) }

            assertEquals("round $round: $outcomes", listOf(StartOutcome.AlreadyRecording, StartOutcome.Started), outcomes.sortedBy { it.toString() })
            runningId()
            stopAndWait()
            assertEquals("round $round: one recording more", round + 1, recordingIdsSinceTestStart().size)
        }
    }

    @Test
    fun twoStartsAtTheSameMomentWithDifferentTokensGiveOneRecording() {
        repeat(5) { round ->
            val a = request(newToken())
            val b = request(newToken())
            val outcomes = runBlocking {
                val gate = CompletableDeferred<Unit>()
                val calls = listOf(a, b).map { async(Dispatchers.Default) { gate.await(); Routelog.startWithUpload(context, it) } }
                gate.complete(Unit)
                calls.awaitAll()
            }

            assertEquals("round $round: $outcomes", listOf(StartOutcome.Busy, StartOutcome.Started), outcomes.sortedBy { it.toString() })
            runningId()
            stopAndWait()
            assertEquals("round $round: one recording more", round + 1, recordingIdsSinceTestStart().size)
        }
    }

    @Test
    fun aSecondStartRightAfterTheFirstIsBusyForEveryKindOfStart() {
        assertEquals(StartOutcome.Started, Routelog.start(context))
        assertEquals(StartOutcome.Busy, Routelog.start(context))
        assertEquals(StartOutcome.Busy, startWithUpload(request(newToken())))
        assertEquals(StartOutcome.Busy, Routelog.resume(context, insertRecording(RecordingState.INTERRUPTED)))
        runningId()
        assertEquals(1, recordingIdsSinceTestStart().size)
    }

    // --- what is known about a token comes before the conditions -------------------------------------

    @Test
    fun whatIsKnownAboutATokenIsAnsweredWithoutLookingAtTheConditions() {
        val upload = request(newToken())
        assertEquals(StartOutcome.Started, startWithUpload(upload))
        runningId()

        withGpsOff {
            // Running: "already", not "GPS off".
            assertEquals(StartOutcome.AlreadyRecording, startWithUpload(upload))
            val id = runningId()
            stopAndWait()
            // The state is Idle a moment before the row is written as finished.
            waitUntil("finished") { dao.recording(id)?.state == RecordingState.FINISHED }
            // Used: the same.
            assertEquals(StartOutcome.AlreadyUsed, startWithUpload(upload))
            // A token routelog knows nothing about is checked against the conditions.
            assertEquals(StartOutcome.Blocked(StartBlocker.GPS_OFF), startWithUpload(request(newToken())))
        }
    }

    @Test
    fun anInterruptionOlderThan12HoursIsAnsweredWithoutLookingAtTheConditions() {
        val token = newToken()
        insertRecordingOf(token, RecordingState.INTERRUPTED, endedAgoMillis = TimeUnit.HOURS.toMillis(13))

        withGpsOff { assertEquals(StartOutcome.TooLateToResume, startWithUpload(request(token))) }
    }

    @Test
    fun aRecentInterruptionMeetsTheConditionsAndThenResumes() {
        val token = newToken()
        val id = insertRecordingOf(token, RecordingState.INTERRUPTED, endedAgoMillis = 60_000)

        withGpsOff { assertEquals(StartOutcome.Blocked(StartBlocker.GPS_OFF), startWithUpload(request(token))) }
        assertEquals(RecorderState.Idle, Routelog.state(context).value)

        assertEquals(StartOutcome.Started, startWithUpload(request(token)))
        assertEquals(id, runningId())
        assertEquals(1, dao.recording(id)?.segment)
    }

    // --- no crash, no undocumented exception ---------------------------------------------------------

    @Test
    fun stoppingWhileNothingRecordsDoesNothing() {
        waitUntil("the service from an earlier test is gone") { !RecordingService.isRunning }

        Routelog.stop(context)
        Routelog.stop(context)

        Thread.sleep(500)
        assertEquals(RecorderState.Idle, Routelog.state(context).value)
        assertFalse("no service was started just to stop it", RecordingService.isRunning)
    }

    @Test
    fun anExportOfARecordingThatIsGoneIsNullAndADeleteIsFalse() {
        assertNull(runBlocking { Routelog.exportGpx(context, 987_654_321L) })
        assertFalse(runBlocking { Routelog.delete(context, 987_654_321L) })
    }

    @Test
    fun aRecordingWithoutUploadCanBeExportedAndDeleted() {
        assertEquals(StartOutcome.Started, Routelog.start(context))
        val id = runningId()
        repeat(2) { gps.push(it * 20.0) }
        waitUntil("2 points") { (dao.recording(id)?.pointCount ?: 0) >= 2 }
        assertFalse("a running recording is not deleted", runBlocking { Routelog.delete(context, id) })
        stopAndWait()

        val uri = runBlocking { Routelog.exportGpx(context, id) }!!
        assertEquals("${context.packageName}.routelog.fileprovider", uri.authority)
        val gpx = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertTrue(gpx.contains("<trkpt"))

        assertTrue(runBlocking { Routelog.delete(context, id) })
        assertEquals(null, dao.recording(id))
        assertTrue("the export went with it", runCatching { context.contentResolver.openInputStream(uri)!!.close() }.isFailure)
    }

    // --- a new retention applies at once -------------------------------------------------------------

    @Test
    fun aNewRetentionAppliesToUploadsThatAreDoneAlready() {
        retentionBefore = Routelog.uploadRetention(context)
        Routelog.setUploadRetention(context, UploadRetention.KEEP)
        val id = insertRecording(RecordingState.FINISHED, endedAgoMillis = TimeUnit.DAYS.toMillis(8), points = 3)
        graph.database.uploadDao().insert(
            UploadEntity(
                id, "https://done.example/r", "done.example", null, Long.MAX_VALUE / 2, UploadState.DONE,
                finishedAtMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8),
            ),
        )
        assertEquals(UploadRetention.KEEP, Routelog.uploadRetention(context))
        assertEquals("kept for good: the route is still there", 3, dao.pointsAfter(id, 0, 100).size)

        Routelog.setUploadRetention(context, UploadRetention.KEEP_7_DAYS)

        // The setting itself is there when the call returns.
        assertEquals(UploadRetention.KEEP_7_DAYS, Routelog.uploadRetention(context))
        waitUntil("the route of the old upload is deleted") { dao.pointsAfter(id, 0, 100).isEmpty() }
        assertNotNull("the entry stays", dao.recording(id))
        assertNotNull(graph.database.uploadDao().upload(id)?.routeDeletedAtMillis)
    }

    // --- the notice "recording interrupted" and recordings a dead process left running ---------------

    @Test
    fun openingTheListRemovesTheInterruptedNoticeAndMarksOrphansInterrupted() {
        val interrupted = insertRecording(RecordingState.INTERRUPTED)
        val orphan = insertRecording(RecordingState.RECORDING)
        showInterruptedNotice(interrupted)

        runBlocking { Routelog.recordings(context).first() }

        waitUntil("the notice is gone") { !interruptedNoticeShown() }
        waitUntil("the orphan is interrupted") { dao.recording(orphan)?.state == RecordingState.INTERRUPTED }
    }

    @Test
    fun deletingARecordingRemovesTheInterruptedNotice() {
        val id = insertRecording(RecordingState.INTERRUPTED)
        showInterruptedNotice(id)

        assertTrue(runBlocking { Routelog.delete(context, id) })

        waitUntil("the notice is gone") { !interruptedNoticeShown() }
    }

    @Test
    fun finishingARecordingRemovesTheInterruptedNotice() {
        val id = insertRecording(RecordingState.INTERRUPTED)
        showInterruptedNotice(id)

        assertTrue(await<Boolean> { done -> Routelog.finishAndUpload(context, id, done) })

        assertEquals(RecordingState.FINISHED, dao.recording(id)?.state)
        waitUntil("the notice is gone") { !interruptedNoticeShown() }
    }

    @Test
    fun aRecordingThatADeadProcessLeftRunningCanBeDeleted() {
        val id = insertRecording(RecordingState.RECORDING)

        assertTrue(runBlocking { Routelog.delete(context, id) })

        assertNull(dao.recording(id))
    }

    @Test
    fun aRecordingThatADeadProcessLeftRunningCanBeFinished() {
        val id = insertRecording(RecordingState.RECORDING)

        assertTrue(await<Boolean> { done -> Routelog.finishAndUpload(context, id, done) })

        assertEquals(RecordingState.FINISHED, dao.recording(id)?.state)
    }

    @Test
    fun aRecordingThatIsNotInterruptedCannotBeFinished() {
        val id = insertRecording(RecordingState.FINISHED)

        assertFalse(await<Boolean> { done -> Routelog.finishAndUpload(context, id, done) })
    }

    // --- the resume screen ---------------------------------------------------------------------------

    @Test
    fun theLibrarysResumeScreenContinuesTheRecording() {
        // What a host without a resume screen of its own gets from the "recording interrupted" notice.
        assertEquals(StartOutcome.Started, Routelog.start(context))
        val id = runningId()
        await<Any?> { done -> graph.recorder.interrupt { done(it) } }

        context.startActivity(RoutelogResumeActivity.intent(context, id))

        assertEquals(id, runningId())
        assertEquals(1, dao.recording(id)?.segment)
        // It opened the host app, with a new task of its own, and without a complaint.
        val host = waitForHostIntent()
        assertTrue(host.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(host.hasExtra(Routelog.EXTRA_RESUME_FAILED))
    }

    @Test
    fun aResumeThatDoesNotStartTellsTheHostApp() {
        // (That the notice comes back is in RoutelogResumeScreenTest: the app's own screen, which is
        // visible here, removes it again when it opens, as a host's list should.)
        val id = insertRecording(RecordingState.INTERRUPTED)

        withGpsOff {
            context.startActivity(RoutelogResumeActivity.intent(context, id))

            val host = waitForHostIntent()
            assertEquals(id, host.getLongExtra(Routelog.EXTRA_RESUME_FAILED, 0))
            assertTrue(host.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        assertEquals(RecorderState.Idle, Routelog.state(context).value)
        assertEquals(RecordingState.INTERRUPTED, dao.recording(id)?.state)
    }

    @Test
    fun aResumeWhileAnotherRecordingRunsTellsTheHostApp() {
        assertEquals(StartOutcome.Started, Routelog.start(context))
        runningId()
        val interrupted = insertRecording(RecordingState.INTERRUPTED)

        context.startActivity(RoutelogResumeActivity.intent(context, interrupted))

        assertEquals(interrupted, waitForHostIntent().getLongExtra(Routelog.EXTRA_RESUME_FAILED, 0))
        assertEquals(RecordingState.INTERRUPTED, dao.recording(interrupted)?.state)
    }

    @Test
    fun theResumeScreenHandlesAnOnResumeOnlyOnce() {
        val id = insertRecording(RecordingState.INTERRUPTED)
        val activity = instrumentation.startActivitySync(
            RoutelogResumeActivity.intent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertEquals(id, runningId())
        waitForHostIntent()
        val hostStarts = startedActivities.hostIntents().size

        // A second onResume of the same instance (the system can resume an activity again).
        instrumentation.runOnMainSync { instrumentation.callActivityOnResume(activity) }
        Thread.sleep(500)

        assertEquals("the host app was opened once", hostStarts, startedActivities.hostIntents().size)
        assertEquals("resumed once", 1, dao.recording(id)?.segment)
    }

    @Test
    fun theResumeScreenCannotBeStartedByOtherApps() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, RoutelogResumeActivity::class.java),
            0,
        )
        assertFalse(info.exported)
        // And the service is not exported either: no other app can start a recording directly.
        val service = context.packageManager.getServiceInfo(ComponentName(context, RecordingService::class.java), 0)
        assertFalse(service.exported)
    }

    private fun waitForHostIntent(): Intent {
        waitUntil("the host app is opened") { startedActivities.hostIntents().isNotEmpty() }
        return startedActivities.hostIntents().last()
    }

    /** The same call twice at the same moment, from two threads. */
    private fun both(call: suspend () -> StartOutcome): List<StartOutcome> = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = List(2) { async(Dispatchers.Default) { gate.await(); call() } }
        gate.complete(Unit)
        calls.awaitAll()
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

    // Never fails (for clean-up, and where a fallback follows).
    private fun waitFor(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
    }
}
