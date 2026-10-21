package io.github.markusnuo.routelog.ui

import android.app.Application
import android.database.SQLException
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.BuildConfig
import io.github.markusnuo.routelog.R
import io.github.markusnuo.routelog.backup.BackupPolicy
import io.github.markusnuo.routelog.data.GrantEntity
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingListItem
import io.github.markusnuo.routelog.link.GrantDecision
import io.github.markusnuo.routelog.link.GrantHash
import io.github.markusnuo.routelog.link.IncomingLink
import io.github.markusnuo.routelog.link.LinkGrantAction
import io.github.markusnuo.routelog.link.LinkParseResult
import io.github.markusnuo.routelog.link.RecordingLink
import io.github.markusnuo.routelog.link.RecordingLinkParser
import io.github.markusnuo.routelog.link.TrustRules
import io.github.markusnuo.routelog.recording.RecorderEvent
import io.github.markusnuo.routelog.recording.RecorderState
import io.github.markusnuo.routelog.upload.UploadPolicy
import io.github.markusnuo.routelog.upload.UploadRetention
import io.github.markusnuo.routelog.upload.UploadWorker
import io.github.markusnuo.routelog.start.StartBlocker
import io.github.markusnuo.routelog.start.StartCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface StartRequest {
    data object New : StartRequest
    data class Resume(val recordingId: Long) : StartRequest

    /** From a confirmed (or trusted) "record and upload" link. */
    data class Linked(val link: RecordingLink) : StartRequest
}

data class BlockerDialog(val blocker: StartBlocker, val canAskAgain: Boolean)

enum class BackupDialog { FIRST_RUN, SETTINGS, UNAVAILABLE }

data class UiMessage(@StringRes val text: Int, val args: List<Any> = emptyList())

data class MainUiState(
    val blocker: BlockerDialog? = null,
    val deleteCandidate: RecordingEntity? = null,
    val backupDialog: BackupDialog? = null,
    val backupConsent: Boolean? = null,
    /** A link waiting for the user's "Record". Holds the token, so it is never saved anywhere. */
    val linkToConfirm: RecordingLink? = null,
    val retentionDialog: Boolean = false,
    val trustedHostsDialog: Boolean = false,
    val message: UiMessage? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.get(app)
    private val dao = graph.database.recordingDao()

    val recorderState: StateFlow<RecorderState> = graph.recorder.state

    val recordings: StateFlow<List<RecordingListItem>> = dao.observeRecordingList()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(MainUiState(backupConsent = graph.backupConsent.get()))
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val _retention = MutableStateFlow(graph.uploadSettings.retention)
    val retention: StateFlow<UploadRetention> = _retention.asStateFlow()

    private val _trustedHosts = MutableStateFlow(graph.trustedHosts.all())
    val trustedHosts: StateFlow<List<String>> = _trustedHosts.asStateFlow()

    private val _batteryHint = MutableStateFlow(false)

    /** Show the hint "battery optimisation can stop recording". */
    val batteryHint: StateFlow<Boolean> = _batteryHint.asStateFlow()

    /** The start that waits for the answer to the battery exemption dialog. */
    var pendingAfterBattery: StartRequest? = null

    private val _shares = Channel<Uri>(Channel.BUFFERED)
    val shares: Flow<Uri> = _shares.receiveAsFlow()

    /** A start that waits for a permission answer. */
    var pendingStart: StartRequest? = null

    /** The permission whose system dialog is open right now; only that one can have been refused. */
    var requestedPermission: String? = null

    /**
     * Approximate location was granted already when the location question was asked, so that
     * question was Android's "change to precise location" and "approximate" was not chosen just
     * now. See [StartCheck.canAskAgain].
     */
    var approximateBeforeRequest = false

    /**
     * A start that came with an intent (interruption notice, trusted link, a link whose recording
     * is resumed); the activity runs it as soon as the app is visible, because from Android 14 on a
     * location service may only start then. A flow, because it can arrive while the activity is
     * already visible, and no onResume follows then.
     */
    private val _queuedStart = MutableStateFlow<StartRequest?>(null)
    val queuedStart: StateFlow<StartRequest?> = _queuedStart.asStateFlow()

    init {
        // Recordings that a dead process left running become "interrupted" and resumable.
        graph.recorder.sweepOrphans { }
        viewModelScope.launch {
            graph.recorder.events.collect {
                showMessage(
                    when (it) {
                        RecorderEvent.StartFailed -> R.string.start_failed
                        RecorderEvent.NotResumable -> R.string.resume_failed
                        RecorderEvent.ResumeTooLate -> R.string.resume_too_late
                        RecorderEvent.LinkBusy -> R.string.link_busy
                        RecorderEvent.LinkExpired -> R.string.link_expired
                        RecorderEvent.LinkUsed -> R.string.link_already_used
                    },
                )
            }
        }
        if (BackupPolicy.shouldAsk(Build.VERSION.SDK_INT, _ui.value.backupConsent)) {
            _ui.update { it.copy(backupDialog = BackupDialog.FIRST_RUN) }
        }
    }

    fun queueStart(request: StartRequest) {
        _queuedStart.value = request
    }

    fun takeQueuedStart(): StartRequest? = _queuedStart.getAndUpdate { null }

    /** A start that wasn't run by the time the app went out of sight is not run later, unasked. */
    fun discardQueuedStart() {
        _queuedStart.value = null
    }

    /** A "record and upload" link arrived (address and token of the intent; not validated yet). */
    fun onLink(incoming: IncomingLink) {
        val result = try {
            RecordingLinkParser.parse(incoming.address, incoming.token, System.currentTimeMillis(), allowHttp = BuildConfig.DEBUG)
        } catch (e: RuntimeException) {
            // Whatever a hostile link does to the parser must not take the app down.
            Log.w(TAG, "A link could not be read: ${e.javaClass.simpleName}")
            showMessage(R.string.link_invalid)
            return
        }
        when (result) {
            is LinkParseResult.Invalid -> showMessage(
                when (result.reason) {
                    LinkParseResult.Reason.EXPIRED -> R.string.link_expired
                    LinkParseResult.Reason.INSECURE_UPLOAD -> R.string.link_insecure_rejected
                    LinkParseResult.Reason.NOT_A_RECORDING_LINK, LinkParseResult.Reason.BAD_PARAMETERS -> R.string.link_invalid
                },
            )
            is LinkParseResult.Valid -> viewModelScope.launch { onValidLink(result.link) }
        }
    }

    /** What routelog remembers of a link's grant: the grant and the recording it started. */
    private class GrantLookup(val grant: GrantEntity?, val recording: RecordingEntity?)

    /** One link = one recording: a link seen before never starts a second one. */
    private suspend fun onValidLink(link: RecordingLink) {
        val lookup = try {
            withContext(Dispatchers.IO) {
                val grant = graph.database.grantDao().grant(GrantHash.of(link.token))
                GrantLookup(grant, grant?.recordingId?.let(dao::recording))
            }
        } catch (e: SQLException) {
            Log.e(TAG, "A link could not be checked: ${e.javaClass.simpleName}")
            showMessage(R.string.start_failed)
            return
        }
        // Back on the main thread: the recorder state is read as late as possible.
        val state = recorderState.value
        val running = (state as? RecorderState.Recording)?.recordingId
        when (val action = GrantDecision.decide(lookup.grant, lookup.recording, running, System.currentTimeMillis())) {
            LinkGrantAction.KeepRecording -> Unit
            LinkGrantAction.AlreadyUsed -> showMessage(R.string.link_already_used)
            LinkGrantAction.TooLateToResume -> showMessage(R.string.resume_too_late)
            // The user confirmed this grant before: no question again.
            is LinkGrantAction.Resume ->
                if (state is RecorderState.Idle) queueStart(StartRequest.Resume(action.recordingId)) else showMessage(R.string.link_busy)
            LinkGrantAction.NewLink -> onNewLink(link, state)
        }
    }

    private fun onNewLink(link: RecordingLink, state: RecorderState) {
        if (state !is RecorderState.Idle) {
            showMessage(R.string.link_busy)
        } else if (TrustRules.isTrusted(link, graph.trustedHosts.all())) {
            queueStart(StartRequest.Linked(link))
            _ui.update {
                it.copy(
                    message = link.label?.let { label -> UiMessage(R.string.link_trusted_started_label, listOf(link.host, label)) }
                        ?: UiMessage(R.string.link_trusted_started, listOf(link.host)),
                )
            }
        } else {
            _ui.update { it.copy(linkToConfirm = link) }
        }
    }

    /** "Record" on the confirmation; returns the start to run, or null if the link can't be used anymore. */
    fun confirmLink(trust: Boolean): StartRequest? {
        val link = _ui.value.linkToConfirm ?: return null
        _ui.update { it.copy(linkToConfirm = null) }
        if (UploadPolicy.isExpired(link.expiresAtMillis, System.currentTimeMillis())) {
            showMessage(R.string.link_expired)
            return null
        }
        if (recorderState.value !is RecorderState.Idle) {
            showMessage(R.string.link_busy)
            return null
        }
        if (trust && TrustRules.canBeTrusted(link)) {
            graph.trustedHosts.add(link.host)
            _trustedHosts.value = graph.trustedHosts.all()
        }
        return StartRequest.Linked(link)
    }

    fun cancelLink() = _ui.update { it.copy(linkToConfirm = null) }

    fun showBlocker(blocker: StartBlocker, canAskAgain: Boolean) =
        _ui.update { it.copy(blocker = BlockerDialog(blocker, canAskAgain)) }

    fun dismissBlocker() = _ui.update { it.copy(blocker = null) }

    fun showMessage(@StringRes message: Int) = _ui.update { it.copy(message = UiMessage(message)) }

    fun messageShown() = _ui.update { it.copy(message = null) }

    fun requestDelete(recording: RecordingEntity) = _ui.update { it.copy(deleteCandidate = recording) }

    fun dismissDelete() = _ui.update { it.copy(deleteCandidate = null) }

    fun confirmDelete() {
        val recording = _ui.value.deleteCandidate ?: return
        _ui.update { it.copy(deleteCandidate = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (dao.deleteUnlessRecording(recording.id) == 1) {
                    // Its upload went with it (cascade); nothing left to try or to expire.
                    graph.uploads.forget(recording.id)
                    scrubDeleted()
                }
            }
            // An exported file may still hold this route.
            graph.exporter.clear()
        }
    }

    /** "Finish & upload" on an interrupted recording that came from a link. */
    fun finishAndUpload(recording: RecordingEntity) {
        graph.recorder.finishInterrupted(recording.id) { finished ->
            if (!finished) showMessage(R.string.finish_failed)
        }
    }

    /**
     * Deleted rows are overwritten in place, but older versions of those pages can still sit in the
     * write-ahead log and the database file until a checkpoint. Best effort: the delete itself
     * has worked.
     */
    private fun scrubDeleted() {
        try {
            graph.database.checkpoint()
        } catch (e: SQLException) {
            Log.w(TAG, "Checkpoint after delete failed: ${e.javaClass.simpleName}")
        }
    }

    fun export(recording: RecordingEntity) {
        viewModelScope.launch {
            try {
                _shares.send(graph.exporter.export(recording.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Export failed: ${e.javaClass.simpleName}")
                showMessage(R.string.export_failed)
            }
        }
    }

    fun openBackupSettings() {
        val dialog = if (Build.VERSION.SDK_INT >= BackupPolicy.MIN_SDK) BackupDialog.SETTINGS else BackupDialog.UNAVAILABLE
        _ui.update { it.copy(backupDialog = dialog) }
    }

    fun answerBackup(enabled: Boolean) {
        _ui.update { it.copy(backupDialog = null, backupConsent = enabled) }
        viewModelScope.launch(Dispatchers.IO) { graph.backupConsent.set(enabled) }
    }

    /** Closed without an answer: backup stays as it is, and the first-start question comes again. */
    fun dismissBackupDialog() = _ui.update { it.copy(backupDialog = null) }

    fun openRetentionDialog() = _ui.update { it.copy(retentionDialog = true) }

    fun dismissRetentionDialog() = _ui.update { it.copy(retentionDialog = false) }

    /**
     * Applies to recordings uploaded before, too: a shorter time deletes the points of those
     * uploaded longer ago right away; their entries stay.
     */
    fun setRetention(retention: UploadRetention) {
        _ui.update { it.copy(retentionDialog = false) }
        _retention.value = retention
        // On the upload worker like every other cleanup, so it never overlaps with an upload job.
        UploadWorker.execute {
            graph.uploadSettings.retention = retention
            graph.uploads.applyRetention()
        }
    }

    fun openTrustedHostsDialog() = _ui.update { it.copy(trustedHostsDialog = true) }

    fun dismissTrustedHostsDialog() = _ui.update { it.copy(trustedHostsDialog = false) }

    /** Re-read on every return to the app: the user may have changed it in the system settings. */
    fun refreshBattery() {
        val battery = graph.battery
        _batteryHint.value = !battery.isExempt() && battery.asked && !battery.hintHidden
    }

    /** routelog asks once, when the first recording starts; afterwards only the hint offers it. */
    fun shouldAskForBatteryExemption(): Boolean = !graph.battery.isExempt() && !graph.battery.asked

    fun batteryExemptionAsked() {
        graph.battery.asked = true
    }

    fun hideBatteryHint() {
        graph.battery.hintHidden = true
        refreshBattery()
    }

    fun removeTrustedHost(host: String) {
        graph.trustedHosts.remove(host)
        _trustedHosts.value = graph.trustedHosts.all()
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
