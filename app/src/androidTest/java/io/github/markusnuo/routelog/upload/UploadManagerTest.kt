package io.github.markusnuo.routelog.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.data.GrantEntity
import io.github.markusnuo.routelog.data.RecordingDao
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.data.TrackPointEntity
import io.github.markusnuo.routelog.data.UploadEntity
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.export.GpxExporter
import io.github.markusnuo.routelog.testing.TestServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The upload against a local HTTP server, with the real OkHttp client. The database is in memory,
 * and jobs and notices are recorded instead of scheduled or shown, so nothing reaches the app's own
 * database, jobs or notifications. Tokens live in a token file of their own, not the app's.
 */
@RunWith(AndroidJUnit4::class)
class UploadManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: RoutelogDatabase
    private lateinit var manager: UploadManager
    private lateinit var tokens: TokenStore
    private val jobs = FakeJobs()
    private val notices = FakeNotices()
    private var retention = UploadRetention.KEEP
    private var now = NOW
    private val servers = mutableListOf<TestServer>()

    @Before
    fun open() {
        database = RoutelogDatabase.build(context, null)
        tokens = TokenStore(context, TEST_TOKENS).also { it.clear() }
        manager = managerWith(GpxExporter(context, database.recordingDao()))
    }

    private fun managerWith(exporter: GpxExporter) = UploadManager(
        database,
        exporter,
        jobs,
        notices,
        tokens,
        retention = { retention },
        clock = { now },
    )

    @After
    fun close() {
        servers.forEach { it.close() }
        tokens.clear()
        database.close()
    }

    private fun server(answerBeforeBody: Boolean = false, respond: (TestServer.Request) -> TestServer.Response) =
        TestServer(respond, answerBeforeBody).also { servers += it }

    /** A finished recording with [points] fixes and an upload in [state] to [url]; its token is stored unless [withToken] is false. */
    private fun recording(
        url: String,
        points: Int = 5,
        state: UploadState = UploadState.PENDING,
        expiresAt: Long = NOW + TimeUnit.HOURS.toMillis(10),
        recordingState: RecordingState = RecordingState.FINISHED,
        withToken: Boolean = true,
    ): Long {
        val recordings = database.recordingDao()
        val id = recordings.insertRecording(
            RecordingEntity(startedAtMillis = NOW - 60_000, endedAtMillis = NOW, state = recordingState, pointCount = points),
        )
        database.runInTransaction(
            Runnable {
                repeat(points) {
                    recordings.insertPoint(
                        TrackPointEntity(
                            recordingId = id, segment = 0, timeMillis = NOW - 60_000 + it * 1_000L,
                            latitude = 47.4245 + it * 1e-6, longitude = 9.3767, altitudeMeters = null, mslAltitudeMeters = null,
                            accuracyMeters = 4f, speedMetersPerSecond = 14f, bearingDegrees = null,
                        ),
                    )
                }
            },
        )
        database.uploadDao().insert(
            UploadEntity(
                recordingId = id, uploadUrl = url, host = "127.0.0.1", label = "Lesson 14:00",
                expiresAtMillis = expiresAt, state = state,
            ),
        )
        if (withToken) tokens.put(id, TOKEN)
        return id
    }

    private fun upload(id: Long) = database.uploadDao().upload(id)!!

    private fun pointCount(id: Long) = database.recordingDao().pointsAfter(id, 0, 1_000_000).size

    @Test
    fun aSuccessfulUploadSendsTheRouteWithTheGrant() {
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/api/routes/7")

        assertFalse(manager.runUpload(id))

        val request = server.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("/api/routes/7", request.path)
        assertEquals("application/gpx+xml", request.headers["content-type"])
        assertEquals("Bearer $TOKEN", request.headers["authorization"])
        assertTrue(request.bodyText.startsWith("<?xml"))
        assertEquals(5, Regex("<trkpt ").findAll(request.bodyText).count())
        assertTrue(request.bodyText.contains("<name>Lesson 14:00</name>"))

        val upload = upload(id)
        assertEquals(UploadState.DONE, upload.state)
        assertNull("the grant is forgotten", tokens.get(id))
        assertEquals(204, upload.lastHttpStatus)
        assertEquals(1, upload.attempts)
        assertEquals(NOW, upload.finishedAtMillis)
        assertNotNull("kept: retention is KEEP", database.recordingDao().recording(id))
        assertEquals("kept: retention is KEEP", 5, pointCount(id))
        assertNull(upload.routeDeletedAtMillis)
        assertTrue(notices.problems.isEmpty())
        assertTrue(id in jobs.cancelledExpiries)
    }

    @Test
    fun alreadyUploadedCountsAsDone() {
        val server = server { TestServer.Response(409) }
        val id = recording("${server.baseUrl}/r")
        assertFalse(manager.runUpload(id))
        assertEquals(UploadState.DONE, upload(id).state)
        assertTrue(notices.problems.isEmpty())
    }

    @Test
    fun deleteRightAwayDeletesTheRouteButKeepsTheEntry() {
        retention = UploadRetention.DELETE_RIGHT_AWAY
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r")
        val before = database.recordingDao().recording(id)!!

        assertFalse(manager.runUpload(id))

        // The points are gone, the entry (date, duration, distance, label, upload) stays.
        assertEquals(0, pointCount(id))
        assertEquals(before, database.recordingDao().recording(id))
        val upload = upload(id)
        assertEquals(UploadState.DONE, upload.state)
        assertEquals(NOW, upload.routeDeletedAtMillis)
        assertEquals("Lesson 14:00", upload.label)
        assertEquals("127.0.0.1", upload.host)
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM track_points").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        assertTrue(notices.problems.isEmpty())
    }

    @Test
    fun aServerErrorIsTriedAgainAndKeepsTheGrant() {
        val server = server { TestServer.Response(503) }
        val id = recording("${server.baseUrl}/r")
        assertTrue(manager.runUpload(id))
        val upload = upload(id)
        assertEquals(UploadState.PENDING, upload.state)
        assertEquals(TOKEN, tokens.get(id))
        assertEquals(1, upload.attempts)
        assertEquals(503, upload.lastHttpStatus)
        assertTrue(notices.problems.isEmpty())
    }

    @Test
    fun noAnswerAtAllIsTriedAgain() {
        // A port nobody listens on.
        val port = ServerSocket(0).use { it.localPort }
        val id = recording("http://127.0.0.1:$port/r")
        assertTrue(manager.runUpload(id))
        assertEquals(UploadState.PENDING, upload(id).state)
        assertNull(upload(id).lastHttpStatus)
    }

    @Test
    fun aRefusedGrantIsFinalAndForgotten() {
        val server = server { TestServer.Response(401) }
        val id = recording("${server.baseUrl}/r")
        assertFalse(manager.runUpload(id))
        val upload = upload(id)
        assertEquals(UploadState.REJECTED, upload.state)
        assertNull(tokens.get(id))
        assertEquals(listOf(Problem(id, UploadState.REJECTED, 401)), notices.problems)
        assertNotNull("the route stays on the phone", database.recordingDao().recording(id))
        assertEquals(5, pointCount(id))
    }

    @Test
    fun anInvalidFileIsFinalAndNotSentAgain() {
        val server = server { TestServer.Response(400) }
        val id = recording("${server.baseUrl}/r")
        assertFalse(manager.runUpload(id))
        assertEquals(UploadState.FAILED, upload(id).state)
        assertEquals(listOf(Problem(id, UploadState.FAILED, 400)), notices.problems)
        assertFalse(manager.runUpload(id))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aRedirectIsNeverFollowed() {
        val elsewhere = server { TestServer.Response(204) }
        val server = server { TestServer.Response(307, mapOf("Location" to "${elsewhere.baseUrl}/steal")) }
        val id = recording("${server.baseUrl}/r")
        assertFalse(manager.runUpload(id))
        assertEquals("the token went nowhere else", 0, elsewhere.requests.size)
        assertEquals(UploadState.FAILED, upload(id).state)
        assertEquals(307, upload(id).lastHttpStatus)
    }

    @Test
    fun anExpiredGrantIsNotUsed() {
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r", expiresAt = NOW)
        assertFalse(manager.runUpload(id))
        assertEquals(0, server.requests.size)
        assertEquals(UploadState.EXPIRED, upload(id).state)
        assertNull(tokens.get(id))
        assertEquals(listOf(Problem(id, UploadState.EXPIRED, null)), notices.problems)
    }

    @Test
    fun aRecordingWithoutPointsIsNotSent() {
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r", points = 0)
        assertFalse(manager.runUpload(id))
        assertEquals(0, server.requests.size)
        assertEquals(UploadState.NOTHING_TO_UPLOAD, upload(id).state)
        assertEquals(listOf(Problem(id, UploadState.NOTHING_TO_UPLOAD, null)), notices.problems)
    }

    @Test
    fun onlyAPendingUploadIsSent() {
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r", state = UploadState.WAITING)
        assertFalse(manager.runUpload(id))
        assertEquals(0, server.requests.size)
        assertEquals(UploadState.WAITING, upload(id).state)
    }

    @Test
    fun anUploadWithoutATokenIsNotPossibleFromThisPhone() {
        // E.g. restored from a backup: the row came along, the token never does.
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r", withToken = false)
        assertFalse(manager.runUpload(id))
        assertEquals(0, server.requests.size)
        assertEquals(UploadState.NO_GRANT, upload(id).state)
        assertEquals(listOf(Problem(id, UploadState.NO_GRANT, null)), notices.problems)
        assertNotNull("the route stays on the phone", database.recordingDao().recording(id))
        assertEquals(5, pointCount(id))
        assertFalse("told once", manager.runUpload(id))
        assertEquals(1, notices.problems.size)
    }

    @Test
    fun aQueuedUploadIsScheduled() {
        val id = recording("http://127.0.0.1:1/r", state = UploadState.PENDING)
        manager.onUploadQueued(id)
        assertEquals(listOf(id), jobs.scheduledUploads)
    }

    @Test
    fun theExpiryEndsAnOpenUpload() {
        val id = recording("http://127.0.0.1:1/r", state = UploadState.WAITING, expiresAt = NOW + 1_000)
        manager.expireIfDue(id)
        assertEquals("not yet", UploadState.WAITING, upload(id).state)
        now = NOW + 1_000
        manager.expireIfDue(id)
        manager.expireIfDue(id)
        assertEquals(UploadState.EXPIRED, upload(id).state)
        assertNull(tokens.get(id))
        assertEquals("told once", listOf(Problem(id, UploadState.EXPIRED, null)), notices.problems)
        assertTrue(id in jobs.cancelledUploads)
    }

    @Test
    fun anExpiryJobThatRunsTooEarlyPlansItselfAgain() {
        val expiresAt = NOW + TimeUnit.HOURS.toMillis(5)
        val id = recording("http://127.0.0.1:1/r", state = UploadState.WAITING, expiresAt = expiresAt)

        manager.expireIfDue(id) // e.g. the clock was set back after the job was planned

        assertEquals(UploadState.WAITING, upload(id).state)
        assertEquals(listOf(Scheduled(id, expiresAt, NOW)), jobs.expiryRequests)
        assertTrue(notices.problems.isEmpty())

        // An upload that has ended needs no check anymore.
        val ended = recording("http://127.0.0.1:1/r", state = UploadState.DONE, expiresAt = expiresAt)
        manager.expireIfDue(ended)
        assertEquals(1, jobs.expiryRequests.size)
    }

    @Test
    fun maintenanceRestoresJobsAndDeletesTheRoutesOfOldUploads() {
        retention = UploadRetention.KEEP_7_DAYS
        val pending = recording("http://127.0.0.1:1/r")
        val old = recording("http://127.0.0.1:1/r", state = UploadState.DONE)
        val recent = recording("http://127.0.0.1:1/r", state = UploadState.DONE)
        val uploads = database.uploadDao()
        // DONE rows as the upload leaves them: finished at some point in the past.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE uploads SET finished_at_millis = ${NOW - TimeUnit.DAYS.toMillis(8)} WHERE recording_id = $old",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE uploads SET finished_at_millis = ${NOW - TimeUnit.DAYS.toMillis(1)} WHERE recording_id = $recent",
        )

        manager.maintenance()

        assertEquals(listOf(pending), jobs.scheduledUploads)
        assertEquals(listOf(pending), jobs.scheduledExpiries)
        assertNotNull("the entry stays", database.recordingDao().recording(old))
        assertNotNull(uploads.upload(old))
        assertEquals("older than 7 days: route deleted", 0, pointCount(old))
        assertEquals(NOW, uploads.upload(old)!!.routeDeletedAtMillis)
        assertEquals(5, pointCount(recent))
        assertNull(uploads.upload(recent)!!.routeDeletedAtMillis)
        assertNotNull(uploads.upload(pending))
        assertTrue(jobs.maintenanceScheduled)
    }

    @Test
    fun aRouteIsDeletedOnlyOnce() {
        retention = UploadRetention.DELETE_RIGHT_AWAY
        val id = recording("http://127.0.0.1:1/r", state = UploadState.DONE)
        database.openHelper.writableDatabase.execSQL("UPDATE uploads SET finished_at_millis = ${NOW - 1} WHERE recording_id = $id")

        manager.applyRetention()
        assertEquals(NOW, upload(id).routeDeletedAtMillis)

        now = NOW + 5_000
        manager.applyRetention()
        assertEquals("the first time stays", NOW, upload(id).routeDeletedAtMillis)
    }

    @Test
    fun retentionNeverTouchesRecordingsWithoutAnUploadOrOpenUploads() {
        retention = UploadRetention.DELETE_RIGHT_AWAY
        val ownRecording = database.recordingDao().insertRecording(
            RecordingEntity(startedAtMillis = 1, endedAtMillis = 2, state = RecordingState.FINISHED, pointCount = 1),
        )
        database.recordingDao().insertPoint(
            TrackPointEntity(
                recordingId = ownRecording, segment = 0, timeMillis = 1, latitude = 47.0, longitude = 9.0, altitudeMeters = null,
                mslAltitudeMeters = null, accuracyMeters = null, speedMetersPerSecond = null, bearingDegrees = null,
            ),
        )
        val pending = recording("http://127.0.0.1:1/r")
        val failed = recording("http://127.0.0.1:1/r", state = UploadState.FAILED)

        manager.applyRetention()

        assertEquals(1, pointCount(ownRecording))
        assertEquals(5, pointCount(pending))
        assertEquals(5, pointCount(failed))
    }

    @Test
    fun maintenanceEndsOpenUploadsWithoutATokenAsNoGrant() {
        val waiting = recording("http://127.0.0.1:1/r", state = UploadState.WAITING, withToken = false)
        val pending = recording("http://127.0.0.1:1/r", state = UploadState.PENDING, withToken = false)
        val fine = recording("http://127.0.0.1:1/r", state = UploadState.PENDING)

        manager.maintenance()

        assertEquals(UploadState.NO_GRANT, upload(waiting).state)
        assertEquals(UploadState.NO_GRANT, upload(pending).state)
        assertEquals(UploadState.PENDING, upload(fine).state)
        assertEquals(setOf(Problem(waiting, UploadState.NO_GRANT, null), Problem(pending, UploadState.NO_GRANT, null)), notices.problems.toSet())
        assertEquals(listOf(fine), jobs.scheduledUploads)
        assertNotNull("the route stays on the phone", database.recordingDao().recording(waiting))
    }

    @Test
    fun maintenanceQueuesAWaitingUploadOfAFinishedRecording() {
        val finished = recording("http://127.0.0.1:1/r", state = UploadState.WAITING, recordingState = RecordingState.FINISHED)
        val interrupted = recording("http://127.0.0.1:1/r", state = UploadState.WAITING, recordingState = RecordingState.INTERRUPTED)
        val running = recording("http://127.0.0.1:1/r", state = UploadState.WAITING, recordingState = RecordingState.RECORDING)

        manager.maintenance()

        assertEquals(UploadState.PENDING, upload(finished).state)
        assertEquals(UploadState.WAITING, upload(interrupted).state)
        assertEquals(UploadState.WAITING, upload(running).state)
        assertEquals(listOf(finished), jobs.scheduledUploads)
    }

    @Test
    fun maintenanceDeletesGrantsThatRanOut() {
        val grants = database.grantDao()
        grants.insert(GrantEntity("expired", null, expiresAtMillis = NOW))
        grants.insert(GrantEntity("older", null, expiresAtMillis = NOW - 1))
        grants.insert(GrantEntity("valid", null, expiresAtMillis = NOW + 1))

        manager.maintenance()

        assertNull(grants.grant("expired"))
        assertNull(grants.grant("older"))
        assertNotNull(grants.grant("valid"))
    }

    @Test
    fun deletingTheRecordingStopsTheUploadThatIsOnTheWire() {
        val release = CountDownLatch(1)
        val server = server {
            release.await(15, TimeUnit.SECONDS)
            TestServer.Response(204)
        }
        val id = recording("${server.baseUrl}/r")
        var result: Boolean? = null
        val worker = thread(name = "test-upload") { result = manager.runUpload(id) }
        try {
            waitUntil("the request reached the server") { server.requests.isNotEmpty() }

            database.recordingDao().deleteUnlessRecording(id)
            manager.forget(id)

            worker.join(5_000)
            assertFalse("the worker is free again", worker.isAlive)
            assertEquals("not an answer of the server: try again (the job is cancelled anyway)", true, result)
            assertNull("gone with the recording", database.uploadDao().upload(id))
            assertNull(tokens.get(id))
            assertTrue(id in jobs.cancelledUploads)
        } finally {
            release.countDown()
            worker.join(5_000)
        }
    }

    @Test
    fun aStoppedJobDoesNotKeepTheWorkerWaiting() {
        val release = CountDownLatch(1)
        val server = server {
            release.await(15, TimeUnit.SECONDS)
            TestServer.Response(204)
        }
        val id = recording("${server.baseUrl}/r")
        var result: Boolean? = null
        val worker = thread(name = "test-upload") { result = manager.runUpload(id) }
        try {
            waitUntil("the request reached the server") { server.requests.isNotEmpty() }

            manager.cancelInFlight(id)

            worker.join(5_000)
            assertFalse("the worker is free again", worker.isAlive)
            assertEquals("try again later", true, result)
            val upload = upload(id)
            assertEquals(UploadState.PENDING, upload.state)
            assertEquals("a stopped attempt is not counted", 0, upload.attempts)
            assertEquals(TOKEN, tokens.get(id))
            assertTrue(notices.problems.isEmpty())
        } finally {
            release.countDown()
            worker.join(5_000)
        }
    }

    @Test
    fun aRecordingDeletedWhileTheGpxIsBuiltIsNotSent() {
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r")
        val recordings = database.recordingDao()
        // Reading the points takes time in real life; here the recording is deleted right after.
        val slowDao = object : RecordingDao by recordings {
            override fun pointsAfter(recordingId: Long, afterId: Long, limit: Int): List<TrackPointEntity> =
                recordings.pointsAfter(recordingId, afterId, limit).also { recordings.delete(recordingId) }
        }

        assertFalse(managerWith(GpxExporter(context, slowDao)).runUpload(id))

        assertEquals("nothing was sent for a deleted recording", 0, server.requests.size)
        assertNull(database.uploadDao().upload(id))
    }

    @Test
    fun aTokenThatVanishedWhileTheGpxIsBuiltIsNotSentEither() {
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r")
        val recordings = database.recordingDao()
        val slowDao = object : RecordingDao by recordings {
            override fun pointsAfter(recordingId: Long, afterId: Long, limit: Int): List<TrackPointEntity> =
                recordings.pointsAfter(recordingId, afterId, limit).also { tokens.remove(recordingId) }
        }

        assertFalse(managerWith(GpxExporter(context, slowDao)).runUpload(id))

        assertEquals(0, server.requests.size)
        assertEquals(UploadState.NO_GRANT, upload(id).state)
    }

    @Test
    fun anUploadThatEndedWhileTheGpxIsBuiltIsNotSentAgain() {
        val server = server { TestServer.Response(204) }
        val id = recording("${server.baseUrl}/r")
        val recordings = database.recordingDao()
        val slowDao = object : RecordingDao by recordings {
            override fun pointsAfter(recordingId: Long, afterId: Long, limit: Int): List<TrackPointEntity> =
                recordings.pointsAfter(recordingId, afterId, limit).also {
                    database.uploadDao().finish(recordingId, UploadState.EXPIRED, null, NOW)
                }
        }

        assertFalse(managerWith(GpxExporter(context, slowDao)).runUpload(id))

        assertEquals(0, server.requests.size)
        assertEquals(UploadState.EXPIRED, upload(id).state)
    }

    // A server that answers before it has read the body and then closes the connection: the answer
    // must count, not be taken for "no network". OkHttp reads that answer for a body that
    // fits into the socket buffers (500 points are about 50 KB) and for a much larger one (the
    // 20,000-point test below). Android's HttpURLConnection lost it for a large body: the write failed
    // with a connection reset and the upload would have been tried again like one without network.
    @Test
    fun aServerThatRefusesTheBodyEarlyIsUnderstood() {
        val server = server(answerBeforeBody = true) { TestServer.Response(413) }
        val id = recording("${server.baseUrl}/r", points = 500)

        assertFalse(manager.runUpload(id))

        val upload = upload(id)
        assertEquals(UploadState.FAILED, upload.state)
        assertEquals(413, upload.lastHttpStatus)
        assertEquals(listOf(Problem(id, UploadState.FAILED, 413)), notices.problems)
        assertEquals("not sent again", 1, server.requests.size)
    }

    @Test
    fun aServerThatRefusesTheGrantBeforeReadingTheBodyIsUnderstood() {
        val server = server(answerBeforeBody = true) { TestServer.Response(401) }
        val id = recording("${server.baseUrl}/r", points = 500)

        assertFalse(manager.runUpload(id))

        assertEquals(UploadState.REJECTED, upload(id).state)
        assertEquals(401, upload(id).lastHttpStatus)
        assertNull(tokens.get(id))
    }

    @Test
    fun aServerThatRefusesALargeBodyBeforeReadingItIsUnderstood() {
        // Several megabytes, answered with 413 right after the headers and the connection closed:
        // HttpURLConnection lost this answer; OkHttp reads it.
        val server = server(answerBeforeBody = true) { TestServer.Response(413) }
        val id = recording("${server.baseUrl}/r", points = 20_000)

        assertFalse(manager.runUpload(id))

        assertEquals(UploadState.FAILED, upload(id).state)
        assertEquals(413, upload(id).lastHttpStatus)
    }

    @Test
    fun aServerThatReadsTheWholeBodyBeforeItRefusesIsUnderstoodForALargeBody() {
        // Answers 413 after the body, as a server that reads it to the end does.
        val server = server { TestServer.Response(413) }
        val id = recording("${server.baseUrl}/r", points = 20_000)

        assertFalse(manager.runUpload(id))

        assertEquals(UploadState.FAILED, upload(id).state)
        assertEquals(413, upload(id).lastHttpStatus)
        assertTrue("a body of well over a megabyte was sent", server.requests.single().body.size > 1_000_000)
    }

    @Test
    fun theUploaderReadsAnAnswerThatCameBeforeTheBodyWasWritten() {
        val server = server(answerBeforeBody = true) { TestServer.Response(413) }
        assertEquals(413, Uploader().put("${server.baseUrl}/r", TOKEN, ByteArray(50_000)))
    }

    @Test
    fun theUploaderReportsNoAnswerWhenNothingListens() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(Uploader().put("http://127.0.0.1:$port/r", TOKEN, ByteArray(10)))
    }

    @Test
    fun aCancelledCallNeverConnects() {
        val server = server { TestServer.Response(204) }
        val call = UploadCall().also { it.cancel() }
        assertNull(Uploader().put("${server.baseUrl}/r", TOKEN, ByteArray(10), call))
        assertEquals(0, server.requests.size)
    }

    private fun waitUntil(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            assertTrue("Timed out waiting for: $what", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }

    data class Problem(val recordingId: Long, val state: UploadState, val httpStatus: Int?)

    data class Scheduled(val recordingId: Long, val expiresAtMillis: Long, val nowMillis: Long)

    private class FakeNotices : UploadNotices {
        val problems = mutableListOf<Problem>()

        override fun showUploadProblem(recordingId: Long, host: String, state: UploadState, httpStatus: Int?) {
            problems += Problem(recordingId, state, httpStatus)
        }

        override fun cancelUploadProblem(recordingId: Long) = Unit
    }

    private class FakeJobs : UploadJobs {
        val scheduledUploads = mutableListOf<Long>()
        val scheduledExpiries = mutableListOf<Long>()
        val expiryRequests = mutableListOf<Scheduled>()
        val cancelledUploads = mutableListOf<Long>()
        val cancelledExpiries = mutableListOf<Long>()
        var maintenanceScheduled = false

        override fun scheduleUpload(recordingId: Long) {
            scheduledUploads += recordingId
        }

        override fun scheduleExpiry(recordingId: Long, expiresAtMillis: Long, nowMillis: Long) {
            scheduledExpiries += recordingId
            expiryRequests += Scheduled(recordingId, expiresAtMillis, nowMillis)
        }

        override fun scheduleMaintenance() {
            maintenanceScheduled = true
        }

        override fun isUploadScheduled(recordingId: Long) = recordingId in scheduledUploads

        override fun isExpiryScheduled(recordingId: Long) = recordingId in scheduledExpiries

        override fun cancelUpload(recordingId: Long) {
            cancelledUploads += recordingId
        }

        override fun cancelExpiry(recordingId: Long) {
            cancelledExpiries += recordingId
        }
    }

    private companion object {
        const val TOKEN = "tok-123"
        const val TEST_TOKENS = "upload_tokens_test"
        val NOW = TimeUnit.DAYS.toMillis(20_000)
    }
}
