package io.github.markusnuo.routelog.upload

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** The token could not be written; the recording that needed it is not started. */
class TokenWriteException : IllegalStateException("The upload token could not be stored")

/**
 * The tokens of uploads that are still open, one per recording. They live in a file under
 * [Context.getNoBackupFilesDir], which Android never includes in a backup, a cloud backup or a
 * device transfer: a token never leaves the phone it was given to, whatever app hosts the library
 * and whatever backup rules that app has. After a restore the upload row is back but the token is
 * not, which ends as `NO_GRANT`.
 *
 * Versions before the library split kept the tokens in the shared preferences file
 * `upload_tokens.xml`. On first use they are moved into the new file and that preferences file is
 * deleted, so uploads that were open on an existing phone keep their token. The routelog app's
 * backup rules still leave `upload_tokens.xml` out, for phones that were not migrated yet. The
 * [name] comes from [io.github.markusnuo.routelog.RoutelogConfig.tokenFileName]: in a host app
 * it is a name only the library uses, so the migration never touches a file of the host's.
 *
 * The file is read and rewritten on every call (there are few tokens, each a few dozen bytes), so
 * every instance of the same [name] sees the same tokens, as with shared preferences. Calls are
 * thread-safe. The file is opened on first use, not at app start: before the first unlock after a
 * restart, the app's credential-encrypted storage can't be read.
 *
 * Never log a token.
 */
class TokenStore(private val context: Context, private val name: String = FILE_NAME) {
    private val file get() = File(context.noBackupFilesDir, "$name.properties")

    // Whether the old preferences file was handled. Guarded by [LOCK].
    private var migrated = false

    fun get(recordingId: Long): String? {
        synchronized(LOCK) {
            migrateLegacyTokens()
            return read().getProperty(key(recordingId))
        }
    }

    /** Stores the token on disk before it returns; throws [TokenWriteException] if that fails. */
    fun put(recordingId: Long, token: String) {
        synchronized(LOCK) {
            migrateLegacyTokens()
            val tokens = read()
            tokens.setProperty(key(recordingId), token)
            try {
                write(tokens)
            } catch (e: IOException) {
                throw TokenWriteException()
            }
        }
    }

    /** Best effort, as before: a token that can't be removed stays until the next try. */
    fun remove(recordingId: Long) {
        synchronized(LOCK) {
            migrateLegacyTokens()
            val tokens = read()
            if (tokens.remove(key(recordingId)) == null) return
            try {
                write(tokens)
            } catch (e: IOException) {
                // see above
            }
        }
    }

    /** For tests: forgets every token. */
    fun clear() {
        synchronized(LOCK) {
            migrateLegacyTokens()
            file.delete()
        }
    }

    private fun key(recordingId: Long) = recordingId.toString()

    // A missing or unreadable file holds no tokens; an unreadable one is overwritten by the next put.
    private fun read(): Properties {
        val tokens = Properties()
        try {
            FileInputStream(file).use { tokens.load(it) }
        } catch (e: IOException) {
            // no file yet, or not readable: no tokens
        } catch (e: IllegalArgumentException) {
            // malformed escape in a damaged file
        }
        return tokens
    }

    // Written next to the target and renamed over it: a crash leaves the old content or the new,
    // never half a file. The data is synced before the rename.
    private fun write(tokens: Properties) {
        val target = file
        if (tokens.isEmpty()) {
            target.delete()
            return
        }
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { out ->
                tokens.store(out, null)
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    // One time per instance: tokens from the old preferences file move into [file] (a token that is
    // in both stays as it is in the file), then the old file is emptied and deleted. If the new
    // file can't be written, the old one stays and the next call tries again.
    private fun migrateLegacyTokens() {
        if (migrated) return
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val old = legacy.all.filterValues { it is String }
        if (old.isNotEmpty()) {
            val tokens = read()
            old.forEach { (key, token) -> if (tokens.getProperty(key) == null) tokens.setProperty(key, token as String) }
            try {
                write(tokens)
            } catch (e: IOException) {
                return
            }
            legacy.edit(commit = true) { clear() }
        }
        context.deleteSharedPreferences(name)
        migrated = true
    }

    companion object {
        /**
         * The name of the routelog app (`RoutelogConfig.legacy`) and of the tests: of the file and,
         * for the migration, of the old preferences file without `.xml`; the app's backup rules
         * name that old `upload_tokens.xml`.
         */
        const val FILE_NAME = "upload_tokens"

        private val LOCK = Any()
    }
}
