package io.github.markusnuo.routelog.ui

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.R
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingListItem
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.data.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The branches of one row in the list of recordings, without a database or a running recorder. */
@RunWith(AndroidJUnit4::class)
class RecordingRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()
    private val clicks = mutableListOf<String>()

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun recording(state: RecordingState, interruptedAgoMillis: Long = 60_000) = RecordingEntity(
        id = 1,
        startedAtMillis = now - interruptedAgoMillis - 600_000,
        // An interrupted recording has an end too (markInterrupted sets it); only a running one has none.
        endedAtMillis = if (state == RecordingState.RECORDING) null else now - interruptedAgoMillis,
        lastPointAtMillis = now - interruptedAgoMillis,
        state = state,
        distanceMeters = 1_234.0,
        pointCount = 600,
    )

    private fun upload(state: UploadState, routeDeleted: Boolean = false, status: Int? = null) = UploadStatus(
        recordingId = 1,
        host = "a.example",
        label = "Lesson 7",
        state = state,
        lastHttpStatus = status,
        expiresAtMillis = now + TimeUnit.DAYS.toMillis(1),
        routeDeletedAtMillis = if (routeDeleted) now else null,
    )

    private fun show(recording: RecordingEntity, upload: UploadStatus? = null, canResume: Boolean = true) {
        compose.setContent {
            RecordingRow(
                item = RecordingListItem(recording, upload),
                canResume = canResume,
                onResume = { clicks += "resume" },
                onFinishAndUpload = { clicks += "finish" },
                onExport = { clicks += "export" },
                onDelete = { clicks += "delete" },
            )
        }
    }

    @Test
    fun aFreshInterruptionCanBeResumed() {
        show(recording(RecordingState.INTERRUPTED))
        compose.onNodeWithText(string(R.string.recording_state_interrupted)).assertExists()
        compose.onNodeWithText(string(R.string.action_resume)).performClick()
        assertEquals(listOf("resume"), clicks)
    }

    @Test
    fun resumeIsDisabledWhileAnotherRecordingRuns() {
        show(recording(RecordingState.INTERRUPTED), canResume = false)
        compose.onNodeWithText(string(R.string.action_resume)).assertIsNotEnabled()
    }

    @Test
    fun noResumeAfterTwelveHours() {
        show(recording(RecordingState.INTERRUPTED, interruptedAgoMillis = TimeUnit.HOURS.toMillis(12) + 60_000))
        compose.onNodeWithText(string(R.string.recording_state_interrupted)).assertExists()
        compose.onNodeWithText(string(R.string.action_resume)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_export)).assertExists()
        compose.onNodeWithText(string(R.string.action_delete)).assertExists()
    }

    @Test
    fun anInterruptedLinkedRecordingCanBeFinishedAndUploadedEvenAfterTwelveHours() {
        show(
            recording(RecordingState.INTERRUPTED, interruptedAgoMillis = TimeUnit.HOURS.toMillis(13)),
            upload(UploadState.WAITING),
        )
        compose.onNodeWithText(string(R.string.upload_waiting_interrupted, "a.example")).assertExists()
        compose.onNodeWithText(string(R.string.action_resume)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_finish_upload)).performClick()
        assertEquals(listOf("finish"), clicks)
    }

    @Test
    fun aRestoredLinkedRecordingCanNotBeUploaded() {
        show(recording(RecordingState.FINISHED), upload(UploadState.NO_GRANT))
        compose.onNodeWithText("Lesson 7").assertExists()
        compose.onNodeWithText(string(R.string.upload_no_grant)).assertExists()
        compose.onNodeWithText(string(R.string.action_export)).assertExists()
    }

    @Test
    fun afterTheKeepTimeOnlyTheEntryRemains() {
        show(recording(RecordingState.FINISHED), upload(UploadState.DONE, routeDeleted = true))
        compose.onNodeWithText(string(R.string.upload_done, "a.example")).assertExists()
        compose.onNodeWithText(string(R.string.upload_route_removed)).assertExists()
        compose.onNodeWithText(string(R.string.action_export)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_delete)).performClick()
        assertEquals(listOf("delete"), clicks)
    }

    @Test
    fun aRefusedUploadShowsTheServersAnswer() {
        show(recording(RecordingState.FINISHED), upload(UploadState.REJECTED, status = 403))
        compose.onNodeWithText(string(R.string.upload_rejected, "a.example", 403)).assertExists()
        compose.onNodeWithText(string(R.string.action_export)).performClick()
        assertEquals(listOf("export"), clicks)
    }

    @Test
    fun theBatteryHintOffersBothWays() {
        compose.setContent { BatteryHint(onAllow = { clicks += "allow" }, onHide = { clicks += "hide" }) }
        compose.onNodeWithText(string(R.string.battery_hint)).assertExists()
        compose.onNodeWithText(string(R.string.battery_allow)).performClick()
        compose.onNodeWithText(string(R.string.battery_hide)).performClick()
        assertEquals(listOf("allow", "hide"), clicks)
    }
}
