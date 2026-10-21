package io.github.markusnuo.routelog

import android.app.Application
import android.content.Intent
import io.github.markusnuo.routelog.ui.MainActivity

class RoutelogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ids and file names the app used before the library existed (notification, channel
        // and job ids, settings and token files), so that users keep their channel settings and
        // scheduled uploads. Its notifications open this app's screen;
        // "recording interrupted" goes through the resume alias, which only this app can start.
        Routelog.configure(
            RoutelogConfig.legacy(
                openAppIntent = { context ->
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                resumeIntent = { context, recordingId -> MainActivity.resumeIntent(context, recordingId) },
            ),
        )
        // Notification channels must exist before the first recording or interruption notice.
        AppGraph.get(this)
    }
}
