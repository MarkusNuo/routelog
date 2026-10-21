package io.github.markusnuo.routelog.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UploadPolicyTest {
    private fun assertOutcome(outcome: UploadOutcome, vararg statuses: Int?) {
        for (status in statuses) assertEquals("status $status", outcome, UploadPolicy.classify(status))
    }

    @Test
    fun doneOnSuccessAndAlreadyUploaded() = assertOutcome(UploadOutcome.DONE, 200, 201, 204, 299, 409)

    @Test
    fun grantRefused() = assertOutcome(UploadOutcome.REJECTED, 401, 403, 410)

    @Test
    fun retryLater() = assertOutcome(UploadOutcome.RETRY, null, 408, 429, 500, 502, 503, 504, 599)

    @Test
    fun otherClientErrorsAreFinal() = assertOutcome(UploadOutcome.FAILED, 400, 404, 405, 413, 415, 422, 499)

    @Test
    fun redirectsAreNeverFollowedAndFinal() = assertOutcome(UploadOutcome.FAILED, 300, 301, 302, 303, 307, 308)

    @Test
    fun oddStatusCodesAreFinal() = assertOutcome(UploadOutcome.FAILED, 100, 199, 600, 999)

    @Test
    fun expiryIsExclusive() {
        assertFalse(UploadPolicy.isExpired(expiresAtMillis = 1_000, nowMillis = 999))
        assertTrue(UploadPolicy.isExpired(expiresAtMillis = 1_000, nowMillis = 1_000))
        assertTrue(UploadPolicy.isExpired(expiresAtMillis = 1_000, nowMillis = 1_001))
    }

    @Test
    fun retentionCutoffs() {
        val now = TimeUnit.DAYS.toMillis(100)
        assertEquals(now, UploadRetention.DELETE_RIGHT_AWAY.cutoff(now))
        assertEquals(now - TimeUnit.DAYS.toMillis(7), UploadRetention.KEEP_7_DAYS.cutoff(now))
        assertEquals(now - TimeUnit.DAYS.toMillis(30), UploadRetention.KEEP_30_DAYS.cutoff(now))
        assertNull(UploadRetention.KEEP.cutoff(now))
    }

    @Test
    fun retentionDefaultsToDeleteRightAway() {
        assertEquals(UploadRetention.DELETE_RIGHT_AWAY, UploadRetention.DEFAULT)
        assertEquals(UploadRetention.DELETE_RIGHT_AWAY, UploadRetention.fromName(null))
        assertEquals(UploadRetention.DELETE_RIGHT_AWAY, UploadRetention.fromName("unknown"))
        assertEquals(UploadRetention.KEEP_30_DAYS, UploadRetention.fromName("KEEP_30_DAYS"))
    }
}
