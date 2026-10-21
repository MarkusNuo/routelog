package io.github.markusnuo.routelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutelogConfigTest {
    @Test
    fun theRoutelogAppKeepsItsOlderIds() {
        // Channel settings of its users and jobs already scheduled on their phones depend on them.
        val legacy = RoutelogConfig.legacy()
        assertEquals("", legacy.channelIdPrefix)
        assertEquals(1, legacy.recordingNotificationId())
        assertEquals(2, legacy.interruptedNotificationId())
        assertEquals(107, legacy.uploadProblemNotificationId(7))
        assertEquals(1, legacy.maintenanceJobId())
        assertEquals(16 + 14, legacy.uploadJobId(7))
        assertEquals(17 + 14, legacy.expiryJobId(7))
        assertEquals("settings", legacy.settingsName)
        // The routelog app's tokens of open uploads live in a file of this name (and migrate from
        // the old preferences file of the same name); its backup rules name it too.
        assertEquals("upload_tokens", legacy.tokenFileName)
    }

    @Test
    fun aHostGetsIdsAndNamesOfItsOwn() {
        val host = RoutelogConfig()
        assertEquals("routelog_", host.channelIdPrefix)
        assertEquals("routelog_settings", host.settingsName)
        // The token store moves tokens out of a preferences file of its name once and deletes it:
        // a host's own file of that name must never be touched.
        assertEquals("routelog_upload_tokens", host.tokenFileName)
        // Far above the small numbers a host's own notifications and WorkManager start with.
        for (id in listOf(host.recordingNotificationId(), host.interruptedNotificationId(), host.maintenanceJobId())) {
            assertTrue("id $id", id > 1_000_000_000)
        }
    }

    @Test
    fun theNamesOfAHostAreNotThoseOfTheRoutelogApp() {
        val host = RoutelogConfig()
        val legacy = RoutelogConfig.legacy()
        assertTrue(host.settingsName != legacy.settingsName)
        assertTrue(host.tokenFileName != legacy.tokenFileName)
        // The two files of the library must not be one.
        assertTrue(host.tokenFileName != host.settingsName)
        assertTrue(legacy.tokenFileName != legacy.settingsName)
        // Every name of a host carries the library's prefix; no short name a host's own file could have.
        assertTrue(host.settingsName.startsWith("routelog_"))
        assertTrue(host.tokenFileName.startsWith("routelog_"))
    }

    @Test
    fun uploadAndExpiryJobsOfDifferentRecordingsNeverShareAnId() {
        val config = RoutelogConfig()
        val ids = (1L..1_000L).flatMap { listOf(config.uploadJobId(it), config.expiryJobId(it)) } + config.maintenanceJobId()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun idsStayPositiveForManyRecordings() {
        val config = RoutelogConfig()
        // Recording ids grow by one per recording; even ten million recordings stay in range.
        assertTrue(config.expiryJobId(10_000_000) > 0)
        assertTrue(config.uploadProblemNotificationId(10_000_000) > 0)
    }
}
