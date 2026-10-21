package io.github.markusnuo.routelog.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartCheckTest {
    private val ready = StartConditions(
        hasGpsProvider = true,
        fineLocationGranted = true,
        coarseLocationGranted = true,
        notificationPermissionGranted = true,
        recordingNotificationEnabled = true,
        gpsEnabled = true,
    )

    @Test
    fun readyToStart() = assertNull(StartCheck.firstBlocker(ready))

    @Test
    fun noGpsHardwareComesFirst() =
        assertEquals(StartBlocker.NO_GPS, StartCheck.firstBlocker(ready.copy(hasGpsProvider = false, fineLocationGranted = false)))

    @Test
    fun missingLocationPermission() = assertEquals(
        StartBlocker.LOCATION_PERMISSION,
        StartCheck.firstBlocker(ready.copy(fineLocationGranted = false, coarseLocationGranted = false, gpsEnabled = false)),
    )

    @Test
    fun approximateLocationIsNotEnough() = assertEquals(
        StartBlocker.PRECISE_LOCATION,
        StartCheck.firstBlocker(ready.copy(fineLocationGranted = false)),
    )

    @Test
    fun notificationPermissionBeforeChannelAndGps() = assertEquals(
        StartBlocker.NOTIFICATION_PERMISSION,
        StartCheck.firstBlocker(ready.copy(notificationPermissionGranted = false, recordingNotificationEnabled = false, gpsEnabled = false)),
    )

    @Test
    fun blockedRecordingNotification() = assertEquals(
        StartBlocker.NOTIFICATIONS_BLOCKED,
        StartCheck.firstBlocker(ready.copy(recordingNotificationEnabled = false, gpsEnabled = false)),
    )

    @Test
    fun localNetworkPermissionAfterNotificationsBeforeGps() {
        assertEquals(
            StartBlocker.NOTIFICATIONS_BLOCKED,
            StartCheck.firstBlocker(ready.copy(recordingNotificationEnabled = false, localNetworkPermissionMissing = true)),
        )
        assertEquals(
            StartBlocker.LOCAL_NETWORK_PERMISSION,
            StartCheck.firstBlocker(ready.copy(localNetworkPermissionMissing = true, gpsEnabled = false)),
        )
    }

    @Test
    fun gpsSwitchedOffComesLast() =
        assertEquals(StartBlocker.GPS_OFF, StartCheck.firstBlocker(ready.copy(gpsEnabled = false)))

    // canAskAgain: Android 12+ asks once more to change to precise, although it reports no rationale.

    @Test
    fun approximateChosenJustNowAsksAgainOnAndroid12AndNewer() {
        for (rationale in listOf(false, true)) {
            assertTrue(StartCheck.canAskAgain(StartBlocker.PRECISE_LOCATION, 31, approximateBeforeRequest = false, rationale = rationale))
            assertTrue(StartCheck.canAskAgain(StartBlocker.PRECISE_LOCATION, 37, approximateBeforeRequest = false, rationale = rationale))
        }
    }

    @Test
    fun approximateHeldBeforeTheRequestLeavesItToAndroidsRationale() {
        assertTrue(StartCheck.canAskAgain(StartBlocker.PRECISE_LOCATION, 37, approximateBeforeRequest = true, rationale = true))
        assertFalse(StartCheck.canAskAgain(StartBlocker.PRECISE_LOCATION, 37, approximateBeforeRequest = true, rationale = false))
    }

    @Test
    fun belowAndroid12TheRationaleDecides() {
        assertTrue(StartCheck.canAskAgain(StartBlocker.PRECISE_LOCATION, 30, approximateBeforeRequest = false, rationale = true))
        assertFalse(StartCheck.canAskAgain(StartBlocker.PRECISE_LOCATION, 30, approximateBeforeRequest = false, rationale = false))
        assertFalse(StartCheck.canAskAgain(StartBlocker.PRECISE_LOCATION, 26, approximateBeforeRequest = false, rationale = false))
    }

    @Test
    fun otherPermissionsFollowTheRationale() {
        for (blocker in listOf(StartBlocker.LOCATION_PERMISSION, StartBlocker.NOTIFICATION_PERMISSION)) {
            assertTrue(StartCheck.canAskAgain(blocker, 37, approximateBeforeRequest = false, rationale = true))
            assertFalse(StartCheck.canAskAgain(blocker, 37, approximateBeforeRequest = false, rationale = false))
        }
    }
}
