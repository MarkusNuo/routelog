package io.github.markusnuo.routelog.backup

import android.annotation.SuppressLint
import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.recording.Recorder
import io.github.markusnuo.routelog.upload.UploadWorker
import java.io.IOException

/**
 * Decides at backup time whether recordings leave the device in an Android backup
 * (see [BackupPolicy]). Without consent no app files are written.
 */
class RoutelogBackupAgent : BackupAgent() {
    // The flag constants are plain ints; below Android 9 the flags are 0 and policy says no.
    @SuppressLint("InlinedApi")
    override fun onFullBackup(data: FullBackupDataOutput) {
        val flags = if (Build.VERSION.SDK_INT >= BackupPolicy.MIN_SDK) data.transportFlags else 0
        val allowed = BackupPolicy.allowsBackup(
            sdkInt = Build.VERSION.SDK_INT,
            consent = BackupConsent(this).get(),
            endToEndEncrypted = flags and FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0,
            deviceTransfer = flags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0,
        )
        if (!allowed) return

        val database = RoutelogDatabase.get(this)
        // Copying the database file while a recording writes to it could produce a broken copy.
        // Failing the run makes Android try again later; what becomes of an earlier backup then
        // depends on the transport. A RECORDING row that no process writes to (the app died
        // mid-recording) is plain data and doesn't block the backup.
        if (Recorder.isRecordingInThisProcess) {
            throw IOException("Recording in progress, backup skipped")
        }
        // The same for an upload job: it writes its progress to the database while it runs.
        if (UploadWorker.isBusy) {
            throw IOException("Upload in progress, backup skipped")
        }
        database.checkpoint()
        super.onFullBackup(data)
    }

    // Key/value backup is not used (fullBackupOnly); these are required by the base class.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit
}
