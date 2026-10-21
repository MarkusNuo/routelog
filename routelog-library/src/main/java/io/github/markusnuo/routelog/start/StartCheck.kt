package io.github.markusnuo.routelog.start

data class StartConditions(
    val hasGpsProvider: Boolean,
    val fineLocationGranted: Boolean,
    val coarseLocationGranted: Boolean,
    /** Runtime permission, Android 13 and newer; always true below. */
    val notificationPermissionGranted: Boolean,
    /** App notifications and the recording channel are both switched on. */
    val recordingNotificationEnabled: Boolean,
    val gpsEnabled: Boolean,
    /**
     * Debug builds on Android 17+: the link uploads into the local network and the runtime
     * permission for that is missing. False in every other case.
     */
    val localNetworkPermissionMissing: Boolean = false,
)

/** Why a recording can't start yet, and what the user has to do about it. */
enum class StartBlocker {
    /** The device has no GPS receiver; recording is not possible. */
    NO_GPS,

    /** No location permission: ask for `ACCESS_FINE_LOCATION` together with `ACCESS_COARSE_LOCATION`. */
    LOCATION_PERMISSION,

    /** Only approximate location is granted; the recording needs precise location. */
    PRECISE_LOCATION,

    /** Android 13+: `POST_NOTIFICATIONS` is not granted. Without the notification nothing records. */
    NOTIFICATION_PERMISSION,

    /** The app's notifications or its recording channel are switched off in the settings. */
    NOTIFICATIONS_BLOCKED,

    /**
     * Only the routelog app's debug build reports it: on Android 17+ an upload into the local
     * network needs `ACCESS_LOCAL_NETWORK`. [io.github.markusnuo.routelog.Routelog.startProblem]
     * never does.
     */
    LOCAL_NETWORK_PERMISSION,

    /** Location is turned off in the system settings. */
    GPS_OFF,
}

/** What still prevents a recording from starting, in the order the user has to fix it. */
object StartCheck {
    fun firstBlocker(c: StartConditions): StartBlocker? = when {
        !c.hasGpsProvider -> StartBlocker.NO_GPS
        !c.fineLocationGranted && c.coarseLocationGranted -> StartBlocker.PRECISE_LOCATION
        !c.fineLocationGranted -> StartBlocker.LOCATION_PERMISSION
        !c.notificationPermissionGranted -> StartBlocker.NOTIFICATION_PERMISSION
        !c.recordingNotificationEnabled -> StartBlocker.NOTIFICATIONS_BLOCKED
        c.localNetworkPermissionMissing -> StartBlocker.LOCAL_NETWORK_PERMISSION
        !c.gpsEnabled -> StartBlocker.GPS_OFF
        else -> null
    }

    /**
     * Whether Android can be asked for the missing permission once more after a refusal; if not, only
     * the settings can help. [rationale] is Android's `shouldShowRequestPermissionRationale`.
     *
     * Exception: when "approximate" was chosen just now ([approximateBeforeRequest] false, the
     * blocker is [StartBlocker.PRECISE_LOCATION]), Android 12+ (API 31) still shows its change-to-precise
     * question once, although it reports no rationale. After that, [rationale] decides again.
     */
    fun canAskAgain(blocker: StartBlocker, sdkInt: Int, approximateBeforeRequest: Boolean, rationale: Boolean): Boolean {
        val changeToPreciseQuestion = blocker == StartBlocker.PRECISE_LOCATION && sdkInt >= 31 && !approximateBeforeRequest
        return changeToPreciseQuestion || rationale
    }
}
