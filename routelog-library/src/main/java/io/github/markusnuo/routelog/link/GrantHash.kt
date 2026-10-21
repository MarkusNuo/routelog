package io.github.markusnuo.routelog.link

import java.security.MessageDigest

/**
 * What routelog remembers of a grant after the recording it started: a hash, not the token, so
 * the same link can be recognised (one link = one recording) without keeping the secret around.
 */
object GrantHash {
    /** Lower-case hex SHA-256 of the token's UTF-8 bytes. */
    fun of(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
