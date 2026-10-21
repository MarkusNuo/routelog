package io.github.markusnuo.routelog.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyTest {
    @Test
    fun nothingWithoutConsent() {
        assertFalse(BackupPolicy.allowsBackup(34, consent = null, endToEndEncrypted = true, deviceTransfer = true))
        assertFalse(BackupPolicy.allowsBackup(34, consent = false, endToEndEncrypted = true, deviceTransfer = true))
    }

    @Test
    fun encryptedCloudBackupWithConsent() =
        assertTrue(BackupPolicy.allowsBackup(34, consent = true, endToEndEncrypted = true, deviceTransfer = false))

    @Test
    fun unencryptedCloudBackupNeverEvenWithConsent() =
        assertFalse(BackupPolicy.allowsBackup(34, consent = true, endToEndEncrypted = false, deviceTransfer = false))

    @Test
    fun deviceTransferWithConsent() =
        assertTrue(BackupPolicy.allowsBackup(34, consent = true, endToEndEncrypted = false, deviceTransfer = true))

    @Test
    fun nothingOnAndroid8() {
        assertFalse(BackupPolicy.allowsBackup(26, consent = true, endToEndEncrypted = true, deviceTransfer = true))
        assertFalse(BackupPolicy.allowsBackup(27, consent = true, endToEndEncrypted = true, deviceTransfer = true))
        assertTrue(BackupPolicy.allowsBackup(28, consent = true, endToEndEncrypted = true, deviceTransfer = false))
    }

    @Test
    fun askOnlyWhereBackupIsPossibleAndUnanswered() {
        assertTrue(BackupPolicy.shouldAsk(28, consent = null))
        assertFalse(BackupPolicy.shouldAsk(27, consent = null))
        assertFalse(BackupPolicy.shouldAsk(34, consent = false))
        assertFalse(BackupPolicy.shouldAsk(34, consent = true))
    }
}
