package io.github.markusnuo.routelog.backup

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.R
import io.github.markusnuo.routelog.recording.BatteryOptimization
import io.github.markusnuo.routelog.upload.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * An upload token never goes into a backup, neither a cloud backup nor a device transfer. The
 * same goes for the battery exemption flags: the exemption belongs to the phone, so a restored
 * flag would keep routelog from asking on a new one. The rules are read from the compiled
 * resources the system uses.
 */
@RunWith(AndroidJUnit4::class)
class BackupRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class Rule(val section: String, val tag: String, val domain: String?, val path: String?)

    private fun rules(resource: Int): List<Rule> {
        val parser = context.resources.getXml(resource)
        val rules = mutableListOf<Rule>()
        var section = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "include", "exclude" ->
                        rules += Rule(section, parser.name, parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path"))
                    else -> section = parser.name // the root, "cloud-backup" or "device-transfer"
                }
            }
            parser.next()
        }
        return rules
    }

    private val tokenFile = Rule("", "exclude", "sharedpref", TokenStore.FILE_NAME + ".xml")

    @Test
    fun theLegacyRulesExcludeTheTokenFile() {
        val rules = rules(R.xml.backup_rules)
        assertTrue(rules.toString(), Rule("full-backup-content", "exclude", "sharedpref", "upload_tokens.xml") in rules)
    }

    @Test
    fun theCloudBackupRulesExcludeTheTokenFile() {
        val rules = rules(R.xml.data_extraction_rules)
        assertTrue(rules.toString(), Rule("cloud-backup", "exclude", "sharedpref", "upload_tokens.xml") in rules)
    }

    @Test
    fun theDeviceTransferRulesExcludeTheTokenFile() {
        val rules = rules(R.xml.data_extraction_rules)
        assertTrue(rules.toString(), Rule("device-transfer", "exclude", "sharedpref", "upload_tokens.xml") in rules)
    }

    @Test
    fun theExcludedFileIsTheOneOlderVersionsWroteAndTheStoreNowUsesTheNoBackupFolder() {
        // The rules name the preferences file of older versions (covers phones whose tokens were not
        // moved yet). The store itself writes under noBackupFilesDir, which no backup includes,
        // whatever backup rules a host app has.
        assertEquals("upload_tokens.xml", tokenFile.path)
        // A token for an id no recording has; taken back at once.
        val store = TokenStore(context)
        store.put(Long.MAX_VALUE, "not-a-real-token")
        try {
            val file = File(context.noBackupFilesDir, TokenStore.FILE_NAME + ".properties")
            assertTrue("the token file is ${file.path}", file.exists())
            assertFalse(File(File(context.dataDir, "shared_prefs"), tokenFile.path!!).let { it.exists() && it.readText().contains("not-a-real-token") })
        } finally {
            store.remove(Long.MAX_VALUE)
        }
    }

    @Test
    fun theLegacyRulesExcludeTheBatteryFile() {
        val rules = rules(R.xml.backup_rules)
        assertTrue(rules.toString(), Rule("full-backup-content", "exclude", "sharedpref", "device_state.xml") in rules)
    }

    @Test
    fun theCloudBackupRulesExcludeTheBatteryFile() {
        val rules = rules(R.xml.data_extraction_rules)
        assertTrue(rules.toString(), Rule("cloud-backup", "exclude", "sharedpref", "device_state.xml") in rules)
    }

    @Test
    fun theDeviceTransferRulesExcludeTheBatteryFile() {
        val rules = rules(R.xml.data_extraction_rules)
        assertTrue(rules.toString(), Rule("device-transfer", "exclude", "sharedpref", "device_state.xml") in rules)
    }

    @Test
    fun theExcludedBatteryFileIsTheOneBatteryOptimizationWrites() {
        assertEquals("device_state.xml", BatteryOptimization.FILE_NAME + ".xml")
        // The flag keeps its value; writing it makes the file exist.
        val battery = BatteryOptimization(context)
        val asked = battery.asked
        battery.asked = asked
        val file = File(File(context.dataDir, "shared_prefs"), BatteryOptimization.FILE_NAME + ".xml")
        // SharedPreferences writes in the background.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!file.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("the battery file is ${file.path}", file.exists())
    }

    @Test
    fun theRecordingsAndSettingsAreStillPartOfTheBackup() {
        // Only the tokens and the per-phone flags are left out: everything else is decided by
        // consent at backup time.
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val rules = rules(resource)
            assertTrue(rules.any { it.tag == "include" && it.domain == "database" })
            assertTrue(rules.any { it.tag == "include" && it.domain == "sharedpref" && it.path == "." })
        }
    }
}
