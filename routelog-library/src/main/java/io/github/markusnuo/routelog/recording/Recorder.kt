package io.github.markusnuo.routelog.recording

import android.annotation.SuppressLint
import android.content.Context
import android.database.SQLException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.github.markusnuo.routelog.data.GrantEntity
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.data.TrackPointEntity
import io.github.markusnuo.routelog.data.UploadEntity
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.geo.DistanceState
import io.github.markusnuo.routelog.geo.Fix
import io.github.markusnuo.routelog.link.GrantHash
import io.github.markusnuo.routelog.link.RecordingLink
import io.github.markusnuo.routelog.upload.TokenStore
import io.github.markusnuo.routelog.upload.TokenWriteException
import io.github.markusnuo.routelog.upload.UploadPolicy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Callable

/**
 * Owns the GPS subscription of the running recording.
 *
 * Every plausible fix is written to the database inside the location callback, on [thread]: it
 * is stored before the callback returns, so neither a crash nor the CPU going back to sleep can
 * lose it.
 * All mutable state is confined to [thread]; the public methods only post work to it, which
 * also puts start, stop and orphan sweeps in a strict order.
 */
class Recorder(
    context: Context,
    private val database: RoutelogDatabase,
    private val listener: Listener = object : Listener {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokens: TokenStore = TokenStore(context),
    private val aliveIntervalMillis: Long = ALIVE_INTERVAL_MILLIS,
) {
    /** [LINK_EXPIRED] and [LINK_USED]: the link was refused at the start and nothing was written. */
    enum class StartResult { STARTED, ALREADY_RECORDING, NOT_RESUMABLE, RESUME_TOO_LATE, FAILED, LINK_EXPIRED, LINK_USED }

    /** Told about recordings that matter to uploads. Called on the recorder thread. */
    interface Listener {
        fun onLinkedRecordingCreated(recordingId: Long, expiresAtMillis: Long) = Unit

        /** The recording was stopped and its upload is now queued in the database; only for linked recordings. */
        fun onUploadQueued(recordingId: Long) = Unit
    }

    private val dao = database.recordingDao()
    private val uploadDao = database.uploadDao()
    private val grantDao = database.grantDao()
    private val locationManager: LocationManager = context.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("routelog-recorder").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    // Nobody listening, nothing to tell: one-off events are for a UI that is looking right now.
    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

    private var session: Session? = null

    private class Session(
        val recordingId: Long,
        val startedAtMillis: Long,
        val segment: Int,
        var distance: DistanceState,
        var pointCount: Int,
        var lastPointAtMillis: Long?,
        var lastFixElapsedMillis: Long? = null,
        var lastAccuracyMeters: Float? = null,
        var providerEnabled: Boolean = true,
        var storageError: Boolean = false,
    )

    /**
     * Starts a new recording; with a [link], its upload target is stored in the same transaction.
     * [onResult] runs on the main thread.
     */
    fun start(link: RecordingLink? = null, onResult: (StartResult) -> Unit) =
        launch(onResult, linked = link != null) { startOnThread(resumeId = null, link = link) }

    /** Continues an interrupted recording in a new track segment. */
    fun resume(recordingId: Long, onResult: (StartResult) -> Unit) =
        launch(onResult) { startOnThread(resumeId = recordingId, link = null) }

    /**
     * Ends an interrupted recording without resuming it ("Finish & upload"). [onDone] gets false
     * if it was not interrupted (anymore). Runs in order with start, resume and stop.
     */
    fun finishInterrupted(recordingId: Long, onDone: (Boolean) -> Unit) {
        handler.post {
            var finished = false
            try {
                // One transaction: finished and queued, or neither.
                val queued = database.runInTransaction(
                    Callable {
                        if (dao.finishInterrupted(recordingId) == 1) {
                            finished = true
                            uploadDao.markPending(recordingId) == 1
                        } else {
                            false
                        }
                    },
                )
                if (queued) listener.onUploadQueued(recordingId)
            } catch (e: SQLException) {
                Log.e(TAG, "Recording could not be finished: ${e.javaClass.simpleName}")
                finished = false
            }
            mainHandler.post { onDone(finished) }
        }
    }

    /** Stops GPS and marks the recording as finished. */
    fun stop(onStopped: () -> Unit) {
        handler.post {
            endSession(RecordingState.FINISHED)
            mainHandler.post(onStopped)
        }
    }

    /** Stops GPS because recording can't go on (the service went away); the user can resume. */
    fun interrupt(onInterrupted: (RecordingEntity?) -> Unit) {
        handler.post {
            val recording = endSession(RecordingState.INTERRUPTED)
            mainHandler.post { onInterrupted(recording) }
        }
    }

    /**
     * Marks recordings that a dead process left in [RecordingState.RECORDING] as interrupted and
     * returns them, so the caller can tell the user. The running recording is never touched.
     */
    fun sweepOrphans(onSwept: (List<RecordingEntity>) -> Unit) {
        handler.post {
            val orphans = sweepOnThread()
            mainHandler.post { onSwept(orphans) }
        }
    }

    /**
     * Ends the recorder thread after the work already posted. The app's recorder lives as long as
     * the process; tests create one per case and end it (after stopping it) with this.
     */
    @VisibleForTesting
    fun quit() {
        thread.quitSafely()
    }

    /**
     * Claims the start of a recording before the service is asked for it: [RecorderState.Idle]
     * becomes [RecorderState.Starting] in one step, so of two quick callers only one wins and
     * the other sees "not idle". Returns whether this caller won. The winner must either start the
     * service or give the claim back with [releaseStartClaim]; the service gives it back itself
     * when the start fails, and a failed start in the recorder ends in Idle as well.
     */
    fun claimStart(): Boolean = _state.compareAndSet(RecorderState.Idle, RecorderState.Starting)

    /** Gives back a claim ([claimStart]) that led to no start. Does nothing if a start or recording went on meanwhile. */
    fun releaseStartClaim() {
        _state.compareAndSet(RecorderState.Starting, RecorderState.Idle)
    }

    /**
     * The recording [recordingId] if it is interrupted and can still be resumed (within 12
     * hours), else null. Read on the recorder thread, in order with start, stop and sweeps;
     * [onResult] runs on the main thread.
     */
    fun resumableRecording(recordingId: Long, onResult: (RecordingEntity?) -> Unit) {
        handler.post {
            val recording = try {
                dao.recording(recordingId)?.takeIf { it.state == RecordingState.INTERRUPTED && ResumeWindow.isOpen(it, clock()) }
            } catch (e: SQLException) {
                Log.e(TAG, "Recording could not be read: ${e.javaClass.simpleName}")
                null
            }
            mainHandler.post { onResult(recording) }
        }
    }

    /** The recording could not start for a reason outside the recorder (the service could not go foreground). */
    fun reportStartFailed() {
        _events.tryEmit(RecorderEvent.StartFailed)
    }

    private fun launch(onResult: (StartResult) -> Unit, linked: Boolean = false, block: () -> StartResult) {
        _state.compareAndSet(RecorderState.Idle, RecorderState.Starting)
        handler.post {
            val result = block()
            when (result) {
                StartResult.FAILED -> _events.tryEmit(RecorderEvent.StartFailed)
                StartResult.NOT_RESUMABLE -> _events.tryEmit(RecorderEvent.NotResumable)
                StartResult.RESUME_TOO_LATE -> _events.tryEmit(RecorderEvent.ResumeTooLate)
                StartResult.LINK_EXPIRED -> _events.tryEmit(RecorderEvent.LinkExpired)
                StartResult.LINK_USED -> _events.tryEmit(RecorderEvent.LinkUsed)
                // A link that arrives while something else records: the user is told, as at the link itself.
                StartResult.ALREADY_RECORDING -> if (linked) _events.tryEmit(RecorderEvent.LinkBusy)
                StartResult.STARTED -> Unit
            }
            mainHandler.post { onResult(result) }
        }
    }

    // The location permission is checked before the service is asked to start; if it was revoked
    // since, requestLocationUpdates throws and the start fails cleanly.
    @SuppressLint("MissingPermission")
    private fun startOnThread(resumeId: Long?, link: RecordingLink?): StartResult {
        if (session != null) return StartResult.ALREADY_RECORDING
        val recording: RecordingEntity
        val previousEnd: Long?
        val previousAlive: Long?
        try {
            sweepOnThread()
            if (resumeId == null) {
                val creation = create(link)
                recording = creation.recording ?: run {
                    _state.value = RecorderState.Idle
                    return creation.refusal
                }
                previousEnd = null
                previousAlive = null
            } else {
                val existing = when (val takeover = takeOverInterrupted(resumeId)) {
                    is Takeover.Taken -> takeover.recording
                    Takeover.TooLate -> {
                        _state.value = RecorderState.Idle
                        return StartResult.RESUME_TOO_LATE
                    }
                    Takeover.NotInterrupted -> {
                        _state.value = RecorderState.Idle
                        return StartResult.NOT_RESUMABLE
                    }
                }
                recording = existing.copy(state = RecordingState.RECORDING, endedAtMillis = null, segment = existing.segment + 1)
                previousEnd = existing.endedAtMillis ?: existing.lastPointAtMillis ?: existing.startedAtMillis
                previousAlive = existing.aliveAtMillis
            }
        } catch (e: SQLException) {
            return createFailed(e)
        } catch (e: TokenWriteException) {
            return createFailed(e)
        }

        // A resumed recording continues in a new segment; the route during the gap is unknown,
        // so the distance does not bridge it (no anchor).
        val session = Session(
            recordingId = recording.id,
            startedAtMillis = recording.startedAtMillis,
            segment = recording.segment,
            distance = DistanceState(totalMeters = recording.distanceMeters),
            pointCount = recording.pointCount,
            lastPointAtMillis = recording.lastPointAtMillis,
        )
        this.session = session
        sessionActive = true
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                INTERVAL_MILLIS,
                0f,
                locationListener,
                thread.looper,
            )
        } catch (e: RuntimeException) {
            // SecurityException: permission revoked. IllegalArgumentException: no GPS provider.
            Log.w(TAG, "GPS updates could not be started: ${e.javaClass.simpleName}")
            this.session = null
            sessionActive = false
            try {
                if (previousEnd == null) {
                    dao.delete(recording.id)
                    // Nothing was recorded, so the link is not used up: its grant and token go as well.
                    if (link != null) {
                        grantDao.delete(GrantHash.of(link.token))
                        tokens.remove(recording.id)
                    }
                } else {
                    // Back as it was: a failed attempt must not extend the 12 hours.
                    dao.markInterrupted(recording.id, previousEnd, previousAlive)
                }
            } catch (e: SQLException) {
                Log.e(TAG, "Failed start could not be rolled back: ${e.javaClass.simpleName}")
            }
            _state.value = RecorderState.Idle
            return StartResult.FAILED
        }
        session.providerEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        handler.postDelayed(statusTick, STATUS_TICK_MILLIS)
        handler.postDelayed(aliveTick, aliveIntervalMillis)
        publish(session)
        link?.let { listener.onLinkedRecordingCreated(recording.id, it.expiresAtMillis) }
        return StartResult.STARTED
    }

    private fun createFailed(e: Exception): StartResult {
        Log.e(TAG, "Recording could not be created: ${e.javaClass.simpleName}")
        _state.value = RecorderState.Idle
        return StartResult.FAILED
    }

    /** A recording that was created, or the reason why a link was refused ([refusal]). */
    private class Creation(val recording: RecordingEntity?, val refusal: StartResult = StartResult.FAILED)

    /**
     * A new recording and, for a link, its upload target and used grant in one transaction. The
     * token goes into the [tokens] store inside that transaction, after the rows: when the upload
     * is visible in the database, its token is on disk, and when the transaction fails nothing stays.
     */
    private fun create(link: RecordingLink?): Creation {
        val now = clock()
        val created = RecordingEntity(startedAtMillis = now, state = RecordingState.RECORDING)
        var tokenStoredFor: Long? = null
        try {
            return database.runInTransaction(
                Callable {
                    if (link == null) return@Callable Creation(created.copy(id = dao.insertRecording(created)))
                    // The link was valid when it arrived; the grant may have run out since (a confirmation can wait).
                    if (UploadPolicy.isExpired(link.expiresAtMillis, now)) return@Callable Creation(null, StartResult.LINK_EXPIRED)
                    // One link = one recording, whatever the screen said when the link arrived.
                    val tokenHash = GrantHash.of(link.token)
                    if (grantDao.grant(tokenHash) != null) return@Callable Creation(null, StartResult.LINK_USED)
                    val id = dao.insertRecording(created)
                    uploadDao.insert(
                        UploadEntity(
                            recordingId = id,
                            uploadUrl = link.uploadUrl,
                            host = link.host,
                            label = link.label,
                            expiresAtMillis = link.expiresAtMillis,
                            state = UploadState.WAITING,
                        ),
                    )
                    grantDao.insert(GrantEntity(tokenHash, id, link.expiresAtMillis))
                    tokens.put(id, link.token)
                    tokenStoredFor = id
                    Creation(created.copy(id = id))
                },
            )
        } catch (e: RuntimeException) {
            // The commit failed after the token was written: take it back.
            tokenStoredFor?.let(tokens::remove)
            throw e
        }
    }

    /**
     * Moves an interrupted recording on to its next segment, or returns null if it is gone or not
     * interrupted (anymore). Read and update are one transaction, and the update itself only
     * matches an interrupted row, so a delete or resume at the same moment can't be overwritten.
     */
    private fun takeOverInterrupted(id: Long): Takeover = database.runInTransaction(
        Callable {
            val existing = dao.recording(id)
            val now = clock()
            when {
                existing == null || existing.state != RecordingState.INTERRUPTED -> Takeover.NotInterrupted
                // Resume only within 12 hours of the interruption (ResumeWindow).
                !ResumeWindow.isOpen(existing, now) -> Takeover.TooLate
                dao.markResumed(id, existing.segment + 1, now) == 0 -> Takeover.NotInterrupted
                else -> Takeover.Taken(existing)
            }
        },
    )

    private sealed interface Takeover {
        data class Taken(val recording: RecordingEntity) : Takeover
        data object NotInterrupted : Takeover
        data object TooLate : Takeover
    }

    private fun endSession(finalState: RecordingState): RecordingEntity? {
        val session = session
        if (session == null) {
            _state.value = RecorderState.Idle
            return null
        }
        locationManager.removeUpdates(locationListener)
        handler.removeCallbacks(statusTick)
        handler.removeCallbacks(aliveTick)
        this.session = null
        sessionActive = false
        _state.value = RecorderState.Idle
        return try {
            if (finalState == RecordingState.FINISHED) {
                // One transaction: finished and queued, or neither. A crash between two separate
                // updates would leave a finished recording whose upload never starts.
                val queued = database.runInTransaction(
                    Callable {
                        dao.markFinished(session.recordingId, clock())
                        uploadDao.markPending(session.recordingId) == 1
                    },
                )
                if (queued) listener.onUploadQueued(session.recordingId)
            } else {
                // The shown end stays the last point; the 12 hours count from now: this process
                // knows it is interrupted, and the last point may be long ago without GPS.
                dao.markInterrupted(session.recordingId, session.lastPointAtMillis ?: session.startedAtMillis, clock())
            }
            dao.recording(session.recordingId)
        } catch (e: SQLException) {
            Log.e(TAG, "Recording could not be closed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun sweepOnThread(): List<RecordingEntity> {
        val owned = session?.recordingId
        return try {
            dao.recordingsInProgress().filter { it.id != owned }.map {
                // The dead process could not write when it ended; the last point is the shown end.
                // The 12 hours count from alive_at_millis too (ResumeWindow), which stays as it was.
                val endedAt = it.lastPointAtMillis ?: it.startedAtMillis
                dao.markInterrupted(it.id, endedAt, it.aliveAtMillis)
                it.copy(state = RecordingState.INTERRUPTED, endedAtMillis = endedAt)
            }
        } catch (e: SQLException) {
            Log.e(TAG, "Interrupted recordings could not be checked: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun onFix(location: Location) {
        val session = session ?: return
        if (!isPlausible(location)) return
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val speed = if (location.hasSpeed()) location.speed else null
        val distance = session.distance.add(Fix(location.latitude, location.longitude, accuracy, location.time, speed))
        val point = TrackPointEntity(
            recordingId = session.recordingId,
            segment = session.segment,
            timeMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            mslAltitudeMeters = mslAltitude(location),
            accuracyMeters = accuracy,
            speedMetersPerSecond = speed,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
        )
        try {
            database.runInTransaction(
                Runnable {
                    dao.insertPoint(point)
                    dao.recordProgress(session.recordingId, distance.totalMeters, location.time)
                },
            )
            session.distance = distance
            session.pointCount++
            session.lastPointAtMillis = location.time
            session.storageError = false
        } catch (e: SQLException) {
            // Never log the location itself.
            Log.e(TAG, "A fix could not be saved: ${e.javaClass.simpleName}")
            session.storageError = true
        } catch (e: RuntimeException) {
            // Whatever else goes wrong with one fix must not end the recording (an uncaught
            // exception on this thread would crash the app).
            Log.e(TAG, "A fix could not be saved: ${e.javaClass.simpleName}")
            session.storageError = true
        }
        session.lastFixElapsedMillis = SystemClock.elapsedRealtime()
        session.lastAccuracyMeters = accuracy
        session.providerEnabled = true
        publish(session)
    }

    private fun publish(session: Session) {
        val lastFix = session.lastFixElapsedMillis
        val gps = when {
            !session.providerEnabled -> GpsStatus.Off
            lastFix == null || SystemClock.elapsedRealtime() - lastFix > FIX_TIMEOUT_MILLIS -> GpsStatus.Searching
            else -> GpsStatus.Fix(session.lastAccuracyMeters)
        }
        _state.value = RecorderState.Recording(
            recordingId = session.recordingId,
            startedAtMillis = session.startedAtMillis,
            distanceMeters = session.distance.totalMeters,
            pointCount = session.pointCount,
            gps = gps,
            storageError = session.storageError,
        )
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val session = session ?: return
            publish(session)
            handler.postDelayed(this, STATUS_TICK_MILLIS)
        }
    }

    /**
     * Notes that the recording still runs, also without GPS (see [RecordingEntity.aliveAtMillis]).
     * Best effort: the handler counts uptime, so while the device sleeps (screen off, no fixes) a
     * tick can wait; it runs about every minute while the processor runs.
     */
    private val aliveTick = object : Runnable {
        override fun run() {
            val session = session ?: return
            try {
                dao.markAlive(session.recordingId, clock())
            } catch (e: RuntimeException) {
                // Only the guess of an interruption time gets worse; the recording goes on (an
                // uncaught exception on this thread would crash the app). SQLException included.
                Log.e(TAG, "Recording could not be marked alive: ${e.javaClass.simpleName}")
            }
            handler.postDelayed(this, aliveIntervalMillis)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)

        // Android 8 to 10 have no default implementations for these; they must exist.
        override fun onProviderEnabled(provider: String) {
            session?.let {
                it.providerEnabled = true
                publish(it)
            }
        }

        override fun onProviderDisabled(provider: String) {
            session?.let {
                it.providerEnabled = false
                publish(it)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "Recorder"
        private const val INTERVAL_MILLIS = 1_000L
        private const val STATUS_TICK_MILLIS = 5_000L
        private const val FIX_TIMEOUT_MILLIS = 10_000L
        private const val ALIVE_INTERVAL_MILLIS = 60_000L

        @Volatile
        private var sessionActive = false

        /**
         * Whether this process is writing a recording right now. Only a recording that is being
         * written can't be copied safely; one left over from a dead process is just data.
         */
        val isRecordingInThisProcess: Boolean get() = sessionActive

        private fun isPlausible(location: Location): Boolean {
            val lat = location.latitude
            val lon = location.longitude
            return lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)
        }

        private fun mslAltitude(location: Location): Double? =
            if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) location.mslAltitudeMeters else null
    }
}
