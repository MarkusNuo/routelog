package io.github.markusnuo.routelog.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.markusnuo.routelog.library.R
import io.github.markusnuo.routelog.data.RecordingDao
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.TrackPointEntity
import io.github.markusnuo.routelog.gpx.GpxPoint
import io.github.markusnuo.routelog.gpx.GpxWriter
import io.github.markusnuo.routelog.format.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes a recording as a GPX file into the app cache for the share sheet. Only one export is
 * kept at a time: earlier files are removed before a new one is written and when a recording
 * is deleted, so no copies of routes pile up.
 */
class GpxExporter(private val context: Context, private val dao: RecordingDao) {
    private val directory get() = File(context.cacheDir, DIRECTORY)

    // Export and clear work on the same folder; without this, a clear could remove a file that
    // an export has just written, or the other way round.
    private val mutex = Mutex()

    // The recording the file in the folder was exported from; null if there is none, or if it is
    // left over from an earlier run of the app. Guarded by [mutex].
    private var exportedRecordingId: Long? = null

    suspend fun export(recordingId: Long): Uri = withContext(Dispatchers.IO) {
        mutex.withLock {
            val recording = dao.recording(recordingId) ?: error("Recording not found")
            deleteExports()
            directory.mkdirs()
            val file = File(directory, fileName(recording.startedAtMillis))
            file.bufferedWriter(Charsets.UTF_8).use { out -> writeGpx(recording, label = null, out) }
            exportedRecordingId = recordingId
            FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
        }
    }

    /**
     * Writes [recording] as GPX 1.1, for an export or an upload. The track is named after [label]
     * if there is one, otherwise after the start time. Blocking: call it off the main thread.
     */
    fun writeGpx(recording: RecordingEntity, label: String?, out: Writer) {
        val name = label ?: context.getString(R.string.routelog_gpx_track_name, formatDateTime(recording.startedAtMillis))
        GpxWriter.write(out, name, recording.startedAtMillis, points(recording.id).map { it.toGpx() })
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { deleteExports() }
    }

    /**
     * Removes the export file if it holds one of [recordingIds] (their routes were deleted in the
     * background), and leaves the file of any other recording alone: the user may be sharing it
     * right now. A file whose recording is not known (left over from an earlier run) goes too.
     */
    suspend fun clearIfFor(recordingIds: Collection<Long>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val owner = exportedRecordingId
            if (owner == null || owner in recordingIds) deleteExports()
        }
    }

    // The mutex is not reentrant, so export() can't call clear().
    private fun deleteExports() {
        directory.deleteRecursively()
        deleteLegacyExports()
        exportedRecordingId = null
    }

    // Earlier versions wrote to cache/exports, a name a host app or plugin may use as well. Remove
    // that folder only if it holds nothing but our own files; delete() on a folder fails if
    // anything else is in it, so a file that appeared meanwhile is never lost.
    private fun deleteLegacyExports() {
        val legacy = File(context.cacheDir, LEGACY_DIRECTORY)
        val files = legacy.listFiles()?.takeIf { it.isNotEmpty() } ?: return
        if (files.all { it.isFile && it.name.startsWith("routelog-") && it.name.endsWith(".gpx") }) {
            files.forEach { it.delete() }
            legacy.delete()
        }
    }

    /** Reads the points page by page, so long recordings don't have to fit in memory at once. */
    private fun points(recordingId: Long): Sequence<TrackPointEntity> = sequence {
        var afterId = 0L
        while (true) {
            val page = dao.pointsAfter(recordingId, afterId, PAGE_SIZE)
            yieldAll(page)
            if (page.size < PAGE_SIZE) break
            afterId = page.last().id
        }
    }

    private fun TrackPointEntity.toGpx() = GpxPoint(segment, latitude, longitude, timeMillis, mslAltitudeMeters)

    private companion object {
        /** Matches the provider in the library's manifest; not the host app's own FileProvider. */
        const val FILE_PROVIDER_SUFFIX = ".routelog.fileprovider"
        /** Inside the cache; named after the library so a host's own "exports" folder is safe. */
        const val DIRECTORY = "routelog_exports"

        /** Where versions before the library split wrote; see [deleteLegacyExports]. */
        const val LEGACY_DIRECTORY = "exports"
        const val PAGE_SIZE = 2_000
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

        fun fileName(startedAtMillis: Long) =
            "routelog-${FILE_TIME.format(Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()))}.gpx"
    }
}
