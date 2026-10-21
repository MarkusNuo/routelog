package io.github.markusnuo.routelog.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.markusnuo.routelog.RoutelogCore

/**
 * After a device restart or an app update the recording process is gone and GPS can't be
 * restarted from the background. Tell the user, so they can resume with a tap.
 */
class InterruptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val graph = RoutelogCore.get(context)
        graph.recorder.sweepOrphans { orphans ->
            orphans.maxByOrNull { it.startedAtMillis }?.let(graph.notifications::showInterrupted)
            pending.finish()
        }
    }
}
