package io.github.markusnuo.routelog.upload

/** What to do after one upload attempt (docs/EXTERNAL_RECORDING.md). */
enum class UploadOutcome {
    /** Stored on the server (2xx) or stored before (409). */
    DONE,

    /** The grant is invalid, refused or expired (401, 403, 410). */
    REJECTED,

    /** Any other answer the server won't change its mind about (other 4xx, all 3xx). */
    FAILED,

    /** Try again later (network error, 408, 429, 5xx). */
    RETRY,
}

object UploadPolicy {
    /** [status] null means no HTTP answer at all (network error, timeout, TLS failure). */
    fun classify(status: Int?): UploadOutcome = when {
        status == null -> UploadOutcome.RETRY
        status in 200..299 -> UploadOutcome.DONE
        status == 409 -> UploadOutcome.DONE
        status == 401 || status == 403 || status == 410 -> UploadOutcome.REJECTED
        status == 408 || status == 429 -> UploadOutcome.RETRY
        status in 500..599 -> UploadOutcome.RETRY
        // 3xx are never followed: the token would go to another address.
        status in 300..499 -> UploadOutcome.FAILED
        else -> UploadOutcome.FAILED
    }

    /** The grant is valid before [expiresAtMillis], not at it. */
    fun isExpired(expiresAtMillis: Long, nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
}
