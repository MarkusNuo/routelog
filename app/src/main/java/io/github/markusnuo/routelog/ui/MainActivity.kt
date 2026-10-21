package io.github.markusnuo.routelog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.markusnuo.routelog.AppGraph
import io.github.markusnuo.routelog.BuildConfig
import io.github.markusnuo.routelog.R
import io.github.markusnuo.routelog.link.IncomingLink
import io.github.markusnuo.routelog.link.LocalNetwork
import io.github.markusnuo.routelog.link.RecordingLinkParser
import io.github.markusnuo.routelog.recording.RecordingService
import io.github.markusnuo.routelog.ui.theme.RoutelogTheme
import io.github.markusnuo.routelog.start.StartBlocker
import io.github.markusnuo.routelog.start.StartCheck
import io.github.markusnuo.routelog.start.StartConditions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsAnswered() }

    // Whatever the answer, the recording goes on: the exemption only makes it more robust. The
    // conditions are checked again, as notifications, location or GPS may have been switched while
    // the system dialog was open. routelog asked already, so this does not ask a second time.
    private val batteryRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshBattery()
            viewModel.pendingAfterBattery?.let {
                viewModel.pendingAfterBattery = null
                attemptStart(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shares.collect(::share)
            }
        }
        lifecycleScope.launch {
            // From Android 14 on, a location service may only start while the app is visible, so a
            // resume from the interruption notice, a trusted link or a known link waits until the
            // activity is resumed. Collecting also covers a start that arrives while it already is
            // (no onResume then).
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.queuedStart.collect { if (it != null) viewModel.takeQueuedStart()?.let(::attemptStart) }
            }
        }
        setContent {
            RoutelogTheme {
                MainScreen(
                    viewModel = viewModel,
                    onStart = { attemptStart(StartRequest.New) },
                    onResumeRecording = { attemptStart(StartRequest.Resume(it)) },
                    onStop = { startService(RecordingService.stopIntent(this)) },
                    onBlockerConfirm = ::onBlockerConfirmed,
                    onConfirmLink = { trust -> viewModel.confirmLink(trust)?.let(::attemptStart) },
                    onAllowBattery = { askForBatteryExemption(then = null) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The list shows interrupted recordings with a Resume button; the notice is not needed.
        AppGraph.get(this).notifications.cancelInterrupted()
        viewModel.refreshBattery()
    }

    override fun onStop() {
        super.onStop()
        // A start that didn't get to run while the app was visible must not run later, unasked.
        viewModel.discardQueuedStart()
    }

    private fun handleIntent(intent: Intent) {
        resumeRecordingIdOf(intent)?.let { viewModel.queueStart(StartRequest.Resume(it)) }
        recordingLinkOf(intent)?.let { link ->
            viewModel.onLink(link)
            // The link carries the grant: don't keep address or token in the activity's intent any longer.
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    /** Checks everything a recording needs, asks for what is missing, then starts the service. */
    // POST_NOTIFICATIONS is only ever missing on Android 13+ (see startConditions).
    @SuppressLint("InlinedApi")
    private fun attemptStart(request: StartRequest) {
        viewModel.pendingStart = request
        viewModel.requestedPermission = null
        when (StartCheck.firstBlocker(startConditions(request))) {
            null -> {
                viewModel.pendingStart = null
                if (viewModel.shouldAskForBatteryExemption()) askForBatteryExemption(then = request) else startRecording(request)
            }
            StartBlocker.LOCATION_PERMISSION, StartBlocker.PRECISE_LOCATION -> {
                viewModel.requestedPermission = Manifest.permission.ACCESS_FINE_LOCATION
                viewModel.approximateBeforeRequest = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            StartBlocker.NOTIFICATION_PERMISSION -> {
                viewModel.requestedPermission = Manifest.permission.POST_NOTIFICATIONS
                permissionRequest.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            StartBlocker.LOCAL_NETWORK_PERMISSION -> {
                viewModel.requestedPermission = LocalNetwork.PERMISSION
                permissionRequest.launch(arrayOf(LocalNetwork.PERMISSION))
            }
            StartBlocker.NOTIFICATIONS_BLOCKED -> viewModel.showBlocker(StartBlocker.NOTIFICATIONS_BLOCKED, canAskAgain = false)
            StartBlocker.GPS_OFF -> viewModel.showBlocker(StartBlocker.GPS_OFF, canAskAgain = false)
            StartBlocker.NO_GPS -> viewModel.showBlocker(StartBlocker.NO_GPS, canAskAgain = false)
        }
    }

    @SuppressLint("InlinedApi")
    private fun onPermissionsAnswered() {
        val request = viewModel.pendingStart ?: return
        val requested = viewModel.requestedPermission
        viewModel.requestedPermission = null
        val blocker = StartCheck.firstBlocker(startConditions(request))
        val missing = when (blocker) {
            StartBlocker.LOCATION_PERMISSION, StartBlocker.PRECISE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
            StartBlocker.NOTIFICATION_PERMISSION -> Manifest.permission.POST_NOTIFICATIONS
            StartBlocker.LOCAL_NETWORK_PERMISSION -> LocalNetwork.PERMISSION
            else -> null
        }
        // Only the permission that was just asked for can have been refused. Anything else that is
        // missing is the next step: its dialog hasn't been shown yet, so ask for it.
        if (blocker == null || missing == null || missing != requested) return attemptStart(request)
        // Refused. If Android won't show its dialog again, only the settings can help. Exception:
        // after "approximate" was chosen just now, Android 12+ still shows its change-to-precise
        // question once, although it reports no rationale. After that, Android's rationale decides.
        val canAskAgain = StartCheck.canAskAgain(
            blocker,
            Build.VERSION.SDK_INT,
            viewModel.approximateBeforeRequest,
            shouldShowRequestPermissionRationale(missing),
        )
        viewModel.showBlocker(blocker, canAskAgain = canAskAgain)
    }

    private fun onBlockerConfirmed(dialog: BlockerDialog) {
        viewModel.dismissBlocker()
        val request = viewModel.pendingStart
        when {
            dialog.blocker == StartBlocker.NO_GPS -> viewModel.pendingStart = null
            dialog.canAskAgain && request != null -> attemptStart(request)
            else -> {
                viewModel.pendingStart = null
                openSettings(dialog.blocker)
            }
        }
    }

    /**
     * Asks Android to exempt routelog from battery optimisation and runs [then], if given, once the
     * dialog is closed. On phones without the dialog, [then] starts at once; without a start to
     * run, the system list of battery optimisation opens instead.
     */
    private fun askForBatteryExemption(then: StartRequest?) {
        viewModel.batteryExemptionAsked()
        viewModel.pendingAfterBattery = then
        val battery = AppGraph.get(this).battery
        try {
            batteryRequest.launch(battery.requestIntent())
        } catch (e: ActivityNotFoundException) {
            viewModel.pendingAfterBattery = null
            viewModel.refreshBattery()
            if (then != null) {
                startRecording(then)
            } else {
                try {
                    startActivity(battery.settingsIntent())
                } catch (e: ActivityNotFoundException) {
                    // Nothing to offer on this phone.
                }
            }
        }
    }

    private fun startRecording(request: StartRequest) {
        val intent = when (request) {
            StartRequest.New -> RecordingService.startIntent(this)
            is StartRequest.Resume -> RecordingService.resumeIntent(this, request.recordingId)
            is StartRequest.Linked -> RecordingService.linkedStartIntent(this, request.link)
        }
        try {
            // Not startForegroundService: the service goes foreground in onStartCommand, and a
            // service that is stopped before it did would make Android crash the app. A plain start
            // is allowed here because the app is visible.
            startService(intent)
        } catch (e: IllegalStateException) {
            // The app was not in the foreground after all.
            viewModel.showMessage(R.string.start_failed)
        }
    }

    private fun startConditions(request: StartRequest?): StartConditions {
        val locationManager = getSystemService(LocationManager::class.java)
        return StartConditions(
            localNetworkPermissionMissing = needsLocalNetwork(request) && !granted(LocalNetwork.PERMISSION),
            hasGpsProvider = LocationManager.GPS_PROVIDER in locationManager.allProviders,
            fineLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseLocationGranted = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            notificationPermissionGranted = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            recordingNotificationEnabled = AppGraph.get(this).notifications.recordingNotificationEnabled(),
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
        )
    }

    /**
     * Debug builds on Android 17+ only: a link that uploads into the local network, or over plain
     * http to anything but loopback, which is a development PC.
     */
    private fun needsLocalNetwork(request: StartRequest?): Boolean =
        BuildConfig.DEBUG && Build.VERSION.SDK_INT >= LocalNetwork.MIN_SDK &&
            request is StartRequest.Linked && LocalNetwork.needsPermission(request.link.host, request.link.secure)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(blocker: StartBlocker) {
        val intent = when (blocker) {
            StartBlocker.GPS_OFF -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            StartBlocker.NOTIFICATION_PERMISSION, StartBlocker.NOTIFICATIONS_BLOCKED ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(GPX_MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        startActivity(Intent.createChooser(send, getString(R.string.export_chooser_title)))
    }

    companion object {
        private const val ACTION_RESUME_RECORDING = "io.github.markusnuo.routelog.action.RESUME_RECORDING"
        private const val EXTRA_RECORDING_ID = "recording_id"
        private const val EXTRA_TOKEN = "token"
        private const val GPX_MIME_TYPE = "application/gpx+xml"

        // The not exported <activity-alias> in the manifest. Apps can start MainActivity (it is
        // the launcher), but not the alias, so an intent that arrives through the alias is ours.
        private const val RESUME_ALIAS = "io.github.markusnuo.routelog.ui.ResumeRecordingAlias"

        fun resumeIntent(context: Context, recordingId: Long) =
            Intent()
                .setComponent(ComponentName(context, RESUME_ALIAS))
                .setAction(ACTION_RESUME_RECORDING)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** The recording to resume if [intent] is the resume request of our own notification, else null. */
        internal fun resumeRecordingIdOf(intent: Intent): Long? {
            if (intent.action != ACTION_RESUME_RECORDING) return null
            // Any other app could send this action and a guessed id to the launcher activity, and
            // routelog would start recording without the user doing anything.
            if (intent.component?.className != RESUME_ALIAS) return null
            // Reopened from the recent apps list: the notification tap was handled back then.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return intent.getLongExtra(EXTRA_RECORDING_ID, -1).takeIf { it > 0 }
        }

        /**
         * The raw `routelog://record…` address of [intent] and its token extra (from `S.token=…` of
         * the `intent://` link), or null; both are validated later. The token is never read from
         * the address.
         */
        internal fun recordingLinkOf(intent: Intent): IncomingLink? {
            if (intent.action != Intent.ACTION_VIEW) return null
            if (!RecordingLinkParser.SCHEME.equals(intent.data?.scheme, ignoreCase = true)) return null
            // Reopened from the recent apps list: the link was handled back then.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val address = intent.dataString ?: return null
            val token = try {
                intent.getStringExtra(EXTRA_TOKEN)
            } catch (e: RuntimeException) {
                // Extras come from another app; a bundle that can't be read is no token.
                null
            }
            return IncomingLink(address, token)
        }
    }
}
