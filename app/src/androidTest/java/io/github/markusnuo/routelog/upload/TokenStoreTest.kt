package io.github.markusnuo.routelog.upload

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TokenStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: TokenStore

    private fun tokenFile(name: String) = File(context.noBackupFilesDir, "$name.properties")

    private fun legacyFile(name: String) = File(File(context.dataDir, "shared_prefs"), "$name.xml")

    @Before
    fun open() {
        store = TokenStore(context, TEST_FILE).also { it.clear() }
        TokenStore(context, MIGRATION_FILE).clear()
    }

    @After
    fun close() {
        store.clear()
        TokenStore(context, MIGRATION_FILE).clear()
        File(context.noBackupFilesDir, "$TEST_FILE.properties.tmp").deleteRecursively()
        context.deleteSharedPreferences(TEST_FILE)
        context.deleteSharedPreferences(MIGRATION_FILE)
    }

    @Test
    fun keepsOneTokenPerRecording() {
        assertNull(store.get(1))
        store.put(1, "token-one")
        store.put(2, "token-two")
        assertEquals("token-one", store.get(1))
        assertEquals("token-two", store.get(2))
        store.remove(1)
        assertNull(store.get(1))
        assertEquals("token-two", store.get(2))
    }

    @Test
    fun aTokenIsOnDiskWhenPutReturns() {
        store.put(3, "token-three")
        // A second store on the same file, as another process start would be, sees it.
        assertEquals("token-three", TokenStore(context, TEST_FILE).get(3))
        assertTrue(tokenFile(TEST_FILE).readText().contains("token-three"))
    }

    @Test
    fun tokensSurviveANewInstanceAndSoDoesTheirRemoval() {
        store.put(1, "token-one")
        store.put(2, "token-two")

        val reopened = TokenStore(context, TEST_FILE)
        assertEquals("token-one", reopened.get(1))
        assertEquals("token-two", reopened.get(2))

        reopened.remove(1)
        // An instance that is already open sees what another one removed.
        assertNull(store.get(1))
        assertEquals("token-two", TokenStore(context, TEST_FILE).get(2))
    }

    @Test
    fun removingTheLastTokenLeavesNoFileBehind() {
        store.put(1, "token-one")
        assertTrue(tokenFile(TEST_FILE).exists())

        store.remove(1)

        assertFalse(tokenFile(TEST_FILE).exists())
    }

    @Test
    fun removingAnUnknownTokenIsFine() {
        store.remove(99)
        assertNull(store.get(99))
    }

    @Test
    fun theTokensAreInTheNoBackupFolderAndNowhereElse() {
        store.put(4, "token-four")

        val file = tokenFile(TEST_FILE)
        assertEquals(context.noBackupFilesDir.canonicalFile, file.canonicalFile.parentFile)
        assertTrue(file.readText().contains("token-four"))
        // Android never backs up the no-backup folder, whatever a host app's backup rules say.
        val backedUp = File(context.dataDir, "shared_prefs").listFiles().orEmpty()
        assertTrue(backedUp.map { it.name }.toString(), backedUp.none { it.readText().contains("token-four") })
        assertFalse(legacyFile(TEST_FILE).exists())
    }

    @Test
    fun theTokensAreNotInTheSettingsFile() {
        store.put(4, "token-four")
        val settings = File(File(context.dataDir, "shared_prefs"), "settings.xml")
        assertFalse(settings.exists() && settings.readText().contains("token-four"))
    }

    @Test
    fun aWriteLeavesNoTemporaryFileBehind() {
        store.put(1, "token-one")
        store.put(2, "token-two")

        assertEquals(listOf("$TEST_FILE.properties"), context.noBackupFilesDir.list().orEmpty().filter { it.startsWith(TEST_FILE) })
    }

    @Test
    fun aFailedWriteThrowsAndKeepsTheTokensThatWereThere() {
        store.put(1, "token-one")
        // A folder where the temporary file goes: the write can't be done.
        assertTrue(File(context.noBackupFilesDir, "$TEST_FILE.properties.tmp").mkdirs())

        assertThrows(TokenWriteException::class.java) { store.put(2, "token-two") }

        assertNull(store.get(2))
        assertEquals("token-one", store.get(1))
        assertEquals("token-one", TokenStore(context, TEST_FILE).get(1))
    }

    @Test
    fun aDamagedFileHoldsNoTokensAndTheNextPutRepairsIt() {
        tokenFile(TEST_FILE).writeText("1=broken \\uZZZZ")

        assertNull(store.get(1))
        store.put(2, "token-two")

        assertEquals("token-two", TokenStore(context, TEST_FILE).get(2))
    }

    @Test
    fun tokensThatNeedEscapingComeBackAsTheyWere() {
        val token = "a=b:c d#e!f\\g\nhä€"
        store.put(7, token)
        assertEquals(token, TokenStore(context, TEST_FILE).get(7))
    }

    @Test
    fun tokensOfAnOlderVersionMoveFromThePreferencesFileOnFirstUse() {
        context.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).edit(commit = true) {
            putString("5", "legacy-five")
            putString("6", "legacy-six")
        }
        assertTrue(legacyFile(MIGRATION_FILE).exists())

        val migrated = TokenStore(context, MIGRATION_FILE)

        assertEquals("legacy-five", migrated.get(5))
        assertEquals("legacy-six", migrated.get(6))
        assertFalse("the old preferences file is deleted", legacyFile(MIGRATION_FILE).exists())
        assertTrue(context.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(tokenFile(MIGRATION_FILE).readText().contains("legacy-five"))
        // From then on the new file is the one that counts, also for the next instance.
        assertEquals("legacy-six", TokenStore(context, MIGRATION_FILE).get(6))
        migrated.remove(5)
        assertNull(TokenStore(context, MIGRATION_FILE).get(5))
        assertFalse(legacyFile(MIGRATION_FILE).exists())
    }

    @Test
    fun theMigrationKeepsATokenThatIsAlreadyInTheNewFile() {
        tokenFile(MIGRATION_FILE).writeText("5=new-five\n")
        context.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).edit(commit = true) {
            putString("5", "legacy-five")
            putString("6", "legacy-six")
        }

        val migrated = TokenStore(context, MIGRATION_FILE)

        assertEquals("new-five", migrated.get(5))
        assertEquals("legacy-six", migrated.get(6))
        assertFalse(legacyFile(MIGRATION_FILE).exists())
    }

    @Test
    fun aMigrationThatCannotWriteKeepsTheOldFileAndTriesAgain() {
        context.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).edit(commit = true) { putString("5", "legacy-five") }
        val blocker = File(context.noBackupFilesDir, "$MIGRATION_FILE.properties.tmp")
        assertTrue(blocker.mkdirs())
        val migrating = TokenStore(context, MIGRATION_FILE)

        try {
            assertNull(migrating.get(5))
            assertTrue("the token is still in the old file", legacyFile(MIGRATION_FILE).readText().contains("legacy-five"))
        } finally {
            blocker.deleteRecursively()
        }

        assertEquals("legacy-five", migrating.get(5))
        assertFalse(legacyFile(MIGRATION_FILE).exists())
    }

    @Test
    fun aPhoneWithoutOldTokensCreatesNoFileForTheMigration() {
        TokenStore(context, MIGRATION_FILE).get(1)

        assertFalse(tokenFile(MIGRATION_FILE).exists())
        assertFalse(legacyFile(MIGRATION_FILE).exists())
    }

    @Test
    fun aStoreOnlyEverTouchesAPreferencesFileOfItsOwnName() {
        // A host app may have a preferences file of its own with a name the library does not use.
        context.getSharedPreferences(FOREIGN_FILE, Context.MODE_PRIVATE).edit(commit = true) { putString("5", "not-ours") }
        try {
            val own = TokenStore(context, MIGRATION_FILE)

            assertNull(own.get(5))
            own.put(5, "ours")
            own.remove(5)
            own.clear()

            assertTrue("the foreign file is still there", legacyFile(FOREIGN_FILE).exists())
            assertEquals("not-ours", context.getSharedPreferences(FOREIGN_FILE, Context.MODE_PRIVATE).getString("5", null))
            assertFalse("and was not turned into a token file", tokenFile(FOREIGN_FILE).exists())
        } finally {
            context.deleteSharedPreferences(FOREIGN_FILE)
        }
    }

    private companion object {
        const val TEST_FILE = "upload_tokens_test_store"
        const val MIGRATION_FILE = "upload_tokens_test_migration"
        const val FOREIGN_FILE = "upload_tokens_test_foreign"
    }
}
