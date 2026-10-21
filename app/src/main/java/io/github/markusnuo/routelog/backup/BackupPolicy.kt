package io.github.markusnuo.routelog.backup

/**
 * Whether recordings may go into an Android backup: one switch, asked on first start, off until
 * answered. With it on, recordings go into a device-to-device transfer, and into a cloud backup
 * only if that is end-to-end encrypted. Android 8.x reports neither, so nothing is backed up there
 * and the question is not asked.
 */
object BackupPolicy {
    /** First Android version whose backup transport reports encryption and device transfer. */
    const val MIN_SDK = 28

    fun allowsBackup(
        sdkInt: Int,
        consent: Boolean?,
        endToEndEncrypted: Boolean,
        deviceTransfer: Boolean,
    ): Boolean = sdkInt >= MIN_SDK && consent == true && (endToEndEncrypted || deviceTransfer)

    fun shouldAsk(sdkInt: Int, consent: Boolean?): Boolean = sdkInt >= MIN_SDK && consent == null
}
