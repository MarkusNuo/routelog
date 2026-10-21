package io.github.markusnuo.routelog.recording

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.markusnuo.routelog.Routelog
import io.github.markusnuo.routelog.RoutelogCore
import io.github.markusnuo.routelog.StartOutcome

/**
 * Target of "recording interrupted" in an app that hosts the library and brings no target of its
 * own ([io.github.markusnuo.routelog.RoutelogConfig.resumeIntent]). Android lets only a visible
 * app start recording location again, so this is an activity, without anything to show: once it is
 * resumed (visible, as the routelog app waits for before it starts) it asks the recording service
 * to continue, opens the host app and closes.
 *
 * If the resume does not start (a permission, notifications or location are missing, another
 * recording runs), the notice that the tap removed is posted again if the recording can still be
 * resumed, and then the host app is opened with [Routelog.EXTRA_RESUME_FAILED], so it can explain.
 * The notice comes first, so a host that removes it when its list opens (as it should,
 * [Routelog.recordings]) always has the last word.
 *
 * Not exported: only the library's own notification can start it, so no other app can make the
 * host record or upload.
 */
class RoutelogResumeActivity : Activity() {
    // Whether the tap on the notice was taken up: not again after a second onResume, a recreation
    // or a reopening from the recent apps list (that tap was handled back then).
    private var handled = false

    // Whether the host app was opened (or given up on), once only. Until then this screen stays.
    private var hostOpened = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState != null || fromHistory) {
            handled = true
            hostOpened = true
        }
    }

    // Not onCreate: location may only be started while the app is visible, and a started activity
    // is not yet resumed in onCreate.
    override fun onResume() {
        super.onResume()
        if (hostOpened) {
            finish()
            return
        }
        // Resumed again while the notice is being posted again: the host app follows by itself.
        if (handled) return
        handled = true
        val recordingId = intent.getLongExtra(EXTRA_RECORDING_ID, 0)
        if (recordingId > 0 && Routelog.resume(this, recordingId) != StartOutcome.Started) {
            postNoticeAgainThenOpenHost(recordingId)
        } else {
            openHostOnce(failedFor = 0)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // The tap removed the notice (auto-cancel). The recording is read on the recorder's thread; the
    // notice comes back unless it was resumed, finished or deleted meanwhile, or the window closed.
    // Only then the host app is opened, which removes the notice again once it shows its list.
    private fun postNoticeAgainThenOpenHost(recordingId: Long) {
        val core = RoutelogCore.get(this)
        val notifications = core.notifications
        core.recorder.resumableRecording(recordingId) { recording ->
            recording?.let(notifications::showInterrupted)
            openHostOnce(failedFor = recordingId)
        }
        // A recorder that does not answer must not leave this invisible screen over the host app.
        mainHandler.postDelayed({ openHostOnce(failedFor = recordingId) }, NOTICE_WAIT_MILLIS)
    }

    private fun openHostOnce(failedFor: Long) {
        if (hostOpened) return
        hostOpened = true
        mainHandler.removeCallbacksAndMessages(null)
        // Gone already (back was pressed meanwhile): the user left, nothing to open.
        if (!isFinishing && !isDestroyed) {
            // A copy, in a task of its own: whatever the host's lambda returns, this activity is not its task.
            val host = Intent(RoutelogCore.openAppIntent(this)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (failedFor > 0) host.putExtra(Routelog.EXTRA_RESUME_FAILED, failedFor)
            try {
                startActivity(host)
            } catch (e: ActivityNotFoundException) {
                // The host has no activity for its own intent: nothing to open.
                Log.w(TAG, "The host app could not be opened: ${e.javaClass.simpleName}")
            }
        }
        finish()
    }

    companion object {
        private const val TAG = "RoutelogResume"
        private const val EXTRA_RECORDING_ID = "routelog_recording_id"
        private const val NOTICE_WAIT_MILLIS = 2_000L

        /**
         * Opens this activity to resume [recordingId], in a task of its own: what the "recording
         * interrupted" notice opens when [io.github.markusnuo.routelog.RoutelogConfig.resumeIntent]
         * is null. Not exported: only the app that hosts the library can start it.
         */
        fun intent(context: Context, recordingId: Long): Intent =
            Intent(context, RoutelogResumeActivity::class.java)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
