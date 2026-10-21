package io.github.markusnuo.routelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.markusnuo.routelog.R
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.format.formatDateTime
import io.github.markusnuo.routelog.recording.GpsStatus
import io.github.markusnuo.routelog.recording.RecorderState
import io.github.markusnuo.routelog.recording.ResumeWindow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.markusnuo.routelog.data.RecordingListItem
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.data.UploadStatus
import io.github.markusnuo.routelog.link.RecordingLink
import io.github.markusnuo.routelog.link.TrustRules
import io.github.markusnuo.routelog.upload.UploadRetention
import io.github.markusnuo.routelog.start.StartBlocker
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStart: () -> Unit,
    onResumeRecording: (Long) -> Unit,
    onStop: () -> Unit,
    onBlockerConfirm: (BlockerDialog) -> Unit,
    onConfirmLink: (trust: Boolean) -> Unit,
    onAllowBattery: () -> Unit,
) {
    val batteryHint by viewModel.batteryHint.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    ui.message?.let { message ->
        val text = stringResource(message.text, *message.args.toTypedArray())
        LaunchedEffect(message) {
            snackbar.showSnackbar(text)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    OverflowMenu(
                        onBackup = viewModel::openBackupSettings,
                        onRetention = viewModel::openRetentionDialog,
                        onTrustedHosts = viewModel::openTrustedHostsDialog,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RecorderCard(recorderState, onStart, onStop) }
            if (batteryHint) {
                item { BatteryHint(onAllow = onAllowBattery, onHide = viewModel::hideBatteryHint) }
            }
            item {
                Text(
                    stringResource(R.string.recordings_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (recordings.isEmpty()) {
                item { Text(stringResource(R.string.recordings_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(recordings, key = { it.recording.id }) { item ->
                RecordingRow(
                    item = item,
                    canResume = recorderState is RecorderState.Idle,
                    onResume = { onResumeRecording(item.recording.id) },
                    onFinishAndUpload = { viewModel.finishAndUpload(item.recording) },
                    onExport = { viewModel.export(item.recording) },
                    onDelete = { viewModel.requestDelete(item.recording) },
                )
            }
        }
    }

    ui.blocker?.let { BlockerAlert(it, onConfirm = { onBlockerConfirm(it) }, onDismiss = viewModel::dismissBlocker) }
    ui.deleteCandidate?.let {
        DeleteAlert(it, onConfirm = viewModel::confirmDelete, onDismiss = viewModel::dismissDelete)
    }
    // Never two questions at once: a fresh install opened by a link first asks about the link. The
    // backup question stays pending and shows as soon as the link dialog is gone.
    if (ui.linkToConfirm == null) {
        ui.backupDialog?.let {
            BackupAlert(
                dialog = it,
                consent = ui.backupConsent,
                onAnswer = viewModel::answerBackup,
                onDismiss = viewModel::dismissBackupDialog,
            )
        }
    }
    ui.linkToConfirm?.let { LinkConfirmAlert(it, onConfirm = onConfirmLink, onDismiss = viewModel::cancelLink) }
    if (ui.retentionDialog) {
        val retention by viewModel.retention.collectAsStateWithLifecycle()
        RetentionAlert(retention, onChoose = viewModel::setRetention, onDismiss = viewModel::dismissRetentionDialog)
    }
    if (ui.trustedHostsDialog) {
        val hosts by viewModel.trustedHosts.collectAsStateWithLifecycle()
        TrustedHostsAlert(hosts, onRemove = viewModel::removeTrustedHost, onDismiss = viewModel::dismissTrustedHostsDialog)
    }
}

@Composable
private fun OverflowMenu(onBackup: () -> Unit, onRetention: () -> Unit, onTrustedHosts: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_backup)) },
                onClick = {
                    expanded = false
                    onBackup()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_retention)) },
                onClick = {
                    expanded = false
                    onRetention()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_trusted_hosts)) },
                onClick = {
                    expanded = false
                    onTrustedHosts()
                },
            )
        }
    }
}

@Composable
private fun RecorderCard(state: RecorderState, onStart: () -> Unit, onStop: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                RecorderState.Idle, RecorderState.Starting -> {
                    val starting = state == RecorderState.Starting
                    Text(
                        stringResource(if (starting) R.string.status_starting else R.string.status_idle),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = onStart, enabled = !starting, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_start_recording))
                    }
                }
                is RecorderState.Recording -> {
                    val now by produceState(System.currentTimeMillis(), state.startedAtMillis) {
                        while (true) {
                            value = System.currentTimeMillis()
                            delay(1_000)
                        }
                    }
                    Text(
                        stringResource(R.string.status_recording),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(Durations.clock(now - state.startedAtMillis), style = MaterialTheme.typography.displaySmall)
                    Text(
                        stringResource(
                            R.string.recording_summary,
                            distanceText(state.distanceMeters),
                            pluralStringResource(R.plurals.point_count, state.pointCount, state.pointCount),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val (statusText, problem) = statusLine(state)
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.action_stop_recording))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLine(state: RecorderState.Recording): Pair<String, Boolean> = when {
    state.storageError -> stringResource(R.string.storage_error) to true
    state.gps is GpsStatus.Off -> stringResource(R.string.gps_off) to true
    state.gps is GpsStatus.Searching -> stringResource(R.string.gps_searching) to true
    else -> {
        val accuracy = (state.gps as GpsStatus.Fix).accuracyMeters
        if (accuracy != null) {
            stringResource(R.string.gps_accuracy, accuracy.roundToInt()) to false
        } else {
            stringResource(R.string.gps_fix) to false
        }
    }
}

@Composable
private fun durationText(millis: Long): String {
    val (h, m, s) = Durations.split(millis)
    return when {
        h > 0 -> stringResource(R.string.duration_hours_minutes, h.toInt(), m.toInt())
        m > 0 -> stringResource(R.string.duration_minutes_seconds, m.toInt(), s.toInt())
        else -> stringResource(R.string.duration_seconds, s.toInt())
    }
}

@Composable
private fun distanceText(meters: Double): String = stringResource(R.string.distance_km, meters / 1000)

@Composable
internal fun RecordingRow(
    item: RecordingListItem,
    canResume: Boolean,
    onResume: () -> Unit,
    onFinishAndUpload: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val recording = item.recording
    val upload = item.upload
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDateTime(recording.startedAtMillis),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                when (recording.state) {
                    RecordingState.RECORDING -> Text(
                        stringResource(R.string.recording_state_recording),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    RecordingState.INTERRUPTED -> Text(
                        stringResource(R.string.recording_state_interrupted),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    RecordingState.FINISHED -> Unit
                }
            }
            upload?.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            upload?.let { UploadLine(it, recording.state) }
            // After the retention time only the points are gone; the entry stays until the user deletes it.
            val routeRemoved = upload?.routeDeletedAtMillis != null
            if (routeRemoved) {
                Text(
                    stringResource(R.string.upload_route_removed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recording.state != RecordingState.RECORDING) {
                val end = recording.endedAtMillis ?: recording.lastPointAtMillis ?: recording.startedAtMillis
                Text(
                    stringResource(
                        R.string.recording_summary,
                        durationText(end - recording.startedAtMillis),
                        distanceText(recording.distanceMeters),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (recording.state == RecordingState.INTERRUPTED) {
                        // Only within 12 hours of the recording's last sign of life (ResumeWindow).
                        if (ResumeWindow.isOpen(recording, System.currentTimeMillis())) {
                            TextButton(onClick = onResume, enabled = canResume) { Text(stringResource(R.string.action_resume)) }
                        }
                        if (upload?.state == UploadState.WAITING) {
                            TextButton(onClick = onFinishAndUpload) { Text(stringResource(R.string.action_finish_upload)) }
                        }
                    }
                    if (!routeRemoved) {
                        TextButton(onClick = onExport) { Text(stringResource(R.string.action_export)) }
                    }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                }
            } else {
                Text(
                    distanceText(recording.distanceMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** Where the route goes and how far the upload is. */
@Composable
private fun UploadLine(upload: UploadStatus, recordingState: RecordingState) {
    val status = upload.lastHttpStatus ?: 0
    val (text, problem) = when (upload.state) {
        UploadState.WAITING -> if (recordingState == RecordingState.INTERRUPTED) {
            stringResource(R.string.upload_waiting_interrupted, upload.host) to false
        } else {
            stringResource(R.string.upload_waiting, upload.host) to false
        }
        UploadState.PENDING -> stringResource(R.string.upload_pending, upload.host) to false
        UploadState.DONE -> stringResource(R.string.upload_done, upload.host) to false
        UploadState.EXPIRED -> stringResource(R.string.upload_expired, upload.host) to true
        UploadState.REJECTED -> stringResource(R.string.upload_rejected, upload.host, status) to true
        UploadState.FAILED -> stringResource(R.string.upload_failed, upload.host, status) to true
        UploadState.NOTHING_TO_UPLOAD -> stringResource(R.string.upload_nothing) to true
        UploadState.NO_GRANT -> stringResource(R.string.upload_no_grant) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BlockerAlert(dialog: BlockerDialog, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, message, settingsMessage) = when (dialog.blocker) {
        StartBlocker.NO_GPS -> Triple(R.string.blocker_no_gps_title, R.string.blocker_no_gps_message, R.string.blocker_no_gps_message)
        StartBlocker.LOCATION_PERMISSION ->
            Triple(R.string.blocker_location_title, R.string.blocker_location_message, R.string.blocker_location_settings_message)
        StartBlocker.PRECISE_LOCATION ->
            Triple(R.string.blocker_precise_title, R.string.blocker_precise_message, R.string.blocker_precise_settings_message)
        StartBlocker.NOTIFICATION_PERMISSION, StartBlocker.NOTIFICATIONS_BLOCKED ->
            Triple(R.string.blocker_notifications_title, R.string.blocker_notifications_message, R.string.blocker_notifications_settings_message)
        StartBlocker.GPS_OFF -> Triple(R.string.blocker_gps_off_title, R.string.blocker_gps_off_message, R.string.blocker_gps_off_message)
        StartBlocker.LOCAL_NETWORK_PERMISSION ->
            Triple(R.string.blocker_local_network_title, R.string.blocker_local_network_message, R.string.blocker_local_network_settings_message)
    }
    val confirm = when {
        dialog.blocker == StartBlocker.NO_GPS -> R.string.ok
        dialog.canAskAgain -> R.string.action_try_again
        else -> R.string.action_open_settings
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(if (dialog.canAskAgain) message else settingsMessage)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = if (dialog.blocker == StartBlocker.NO_GPS) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
        },
    )
}

@Composable
private fun DeleteAlert(recording: RecordingEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_message, formatDateTime(recording.startedAtMillis))) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BackupAlert(dialog: BackupDialog, consent: Boolean?, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    if (dialog == BackupDialog.UNAVAILABLE) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.menu_backup)) },
            text = { Text(stringResource(R.string.backup_unavailable)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_message))
                if (dialog == BackupDialog.SETTINGS) {
                    Text(
                        stringResource(if (consent == true) R.string.backup_state_on else R.string.backup_state_off),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.backup_yes)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.backup_no)) } },
    )
}

@Composable
internal fun LinkConfirmAlert(link: RecordingLink, onConfirm: (trust: Boolean) -> Unit, onDismiss: () -> Unit) {
    // The "Always allow" box belongs to the link on screen: another link replacing it starts unchecked.
    var trust by rememberSaveable(link) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_title)) },
        text = {
            IgnoreTouchesThroughOverlays()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.link_upload_to))
                Text(link.host, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    link.uploadUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                link.label?.let { Text(stringResource(R.string.link_label, it)) }
                Text(stringResource(R.string.link_expires, formatDateTime(link.expiresAtMillis)))
                if (!link.secure) {
                    Text(stringResource(R.string.link_insecure_warning), color = MaterialTheme.colorScheme.error)
                }
                if (TrustRules.canBeTrusted(link)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(value = trust, role = Role.Checkbox, onValueChange = { trust = it }),
                    ) {
                        Checkbox(checked = trust, onCheckedChange = null)
                        Text(stringResource(R.string.link_trust, link.host), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(trust) }) { Text(stringResource(R.string.link_record)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Makes the dialog it is part of ignore touches while another app's window covers it (tapjacking:
 * an overlay that lets taps through could confirm the upload target for the user, Android 8 to 11).
 * Call it inside the dialog's content.
 */
@Composable
private fun IgnoreTouchesThroughOverlays() {
    val view = LocalView.current
    SideEffect {
        view.filterTouchesWhenObscured = true
        (view.parent as? DialogWindowProvider)?.window?.decorView?.filterTouchesWhenObscured = true
    }
}

@Composable
private fun RetentionAlert(current: UploadRetention, onChoose: (UploadRetention) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retention_title)) },
        text = {
            Column {
                for (option in UploadRetention.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == current, role = Role.RadioButton, onClick = { onChoose(option) })
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Text(stringResource(retentionLabel(option)), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    stringResource(R.string.retention_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private fun retentionLabel(retention: UploadRetention): Int = when (retention) {
    UploadRetention.DELETE_RIGHT_AWAY -> R.string.retention_delete_right_away
    UploadRetention.KEEP_7_DAYS -> R.string.retention_keep_7_days
    UploadRetention.KEEP_30_DAYS -> R.string.retention_keep_30_days
    UploadRetention.KEEP -> R.string.retention_keep
}

@Composable
private fun TrustedHostsAlert(hosts: List<String>, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trusted_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.trusted_message), style = MaterialTheme.typography.bodyMedium)
                if (hosts.isEmpty()) {
                    Text(
                        stringResource(R.string.trusted_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                for (host in hosts) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(host, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { onRemove(host) }) { Text(stringResource(R.string.trusted_remove)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Shown while Android still optimises routelog's battery use. */
@Composable
internal fun BatteryHint(onAllow: () -> Unit, onHide: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.battery_hint), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onHide) { Text(stringResource(R.string.battery_hide)) }
                TextButton(onClick = onAllow) { Text(stringResource(R.string.battery_allow)) }
            }
        }
    }
}
