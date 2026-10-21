package io.github.markusnuo.routelog.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.markusnuo.routelog.data.RecordingDao
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.data.TrackPointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class GpxExporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: RoutelogDatabase
    private lateinit var dao: RecordingDao
    private lateinit var exporter: GpxExporter

    @Before
    fun open() {
        context.deleteDatabase(TEST_DB)
        database = RoutelogDatabase.build(context, TEST_DB)
        dao = database.recordingDao()
        exporter = GpxExporter(context, dao)
    }

    @After
    fun close() {
        runBlocking { exporter.clear() }
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    private fun recording(state: RecordingState = RecordingState.FINISHED) =
        dao.insertRecording(RecordingEntity(startedAtMillis = START, endedAtMillis = START + 1_000_000, state = state))

    private fun point(
        recordingId: Long,
        i: Int,
        segment: Int = 0,
        altitude: Double? = null,
        mslAltitude: Double? = null,
    ) = TrackPointEntity(
        recordingId = recordingId, segment = segment, timeMillis = START + 1_000L * i,
        latitude = latitude(i), longitude = 9.3767, altitudeMeters = altitude, mslAltitudeMeters = mslAltitude,
        accuracyMeters = 4f, speedMetersPerSecond = null, bearingDegrees = null,
    )

    /** Moves by 1e-5 degrees per point, so every point has its own text in the 7-decimal GPX. */
    private fun latitude(i: Int) = 47.0 + i * 1e-5

    private fun insert(points: List<TrackPointEntity>) {
        database.runInTransaction(Runnable { points.forEach { dao.insertPoint(it) } })
    }

    private fun export(recordingId: Long): ByteArray {
        val uri: Uri = runBlocking { exporter.export(recordingId) }
        return context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(bytes))

    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private fun Element.children(name: String): List<Element> =
        getElementsByTagNameNS(NS, name).elements().filter { it.parentNode === this }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Node.trkpts() = (this as Element).getElementsByTagNameNS(NS, "trkpt").elements()

    @Test
    fun allPointsOfALongRecordingAreExportedInOrder() {
        // Points are read in pages of 2000. Check below, at, and past one and two pages.
        for (count in listOf(1_999, 2_000, 2_001, 4_001)) {
            val id = recording()
            insert((0 until count).map { point(id, it) })

            val trkpts = parse(export(id)).documentElement.trkpts()

            assertEquals("points exported for $count", count, trkpts.size)
            assertEquals(
                "order for $count",
                (0 until count).map { String.format(Locale.ROOT, "%.7f", latitude(it)) },
                trkpts.map { it.getAttribute("lat") },
            )
        }
    }

    @Test
    fun eachSegmentBecomesATrackSegment() {
        val id = recording()
        insert((0 until 5).map { point(id, it, segment = 0) } + (5 until 8).map { point(id, it, segment = 1) })

        val document = parse(export(id))

        val segments = document.getElementsByTagNameNS(NS, "trkseg").elements()
        assertEquals(listOf(5, 3), segments.map { it.children("trkpt").size })
        assertEquals((0 until 8).map { String.format(Locale.ROOT, "%.7f", latitude(it)) }, document.documentElement.trkpts().map { it.getAttribute("lat") })
    }

    @Test
    fun elevationIsTheHeightAboveSeaLevelAndOnlyWhenKnown() {
        val id = recording()
        insert(
            listOf(
                point(id, 0, altitude = 650.0, mslAltitude = 612.3), // ellipsoid height must not end up as <ele>
                point(id, 1, altitude = 651.0), // no sea level height: no <ele>
                point(id, 2),
            ),
        )

        val trkpts = parse(export(id)).documentElement.trkpts()

        assertEquals(listOf("612.3"), trkpts[0].children("ele").map { it.textContent })
        assertTrue(trkpts[1].children("ele").isEmpty())
        assertTrue(trkpts[2].children("ele").isEmpty())
    }

    @Test
    fun timesAreUtcAndInRecordingOrder() {
        val id = recording()
        insert((0 until 3).map { point(id, it) })

        val document = parse(export(id))

        val times = document.documentElement.trkpts().map { it.children("time").single().textContent }
        assertEquals(listOf("2026-10-06T12:20:00Z", "2026-10-06T12:20:01Z", "2026-10-06T12:20:02Z"), times)
    }

    /**
     * Android has no XML Schema validator, so the schema check of the output is in the JVM tests
     * (GpxWriterTest). This checks what that schema demands of the parts routelog writes, on the
     * file the exporter really produced.
     */
    @Test
    fun theExportHasTheStructureGpx11Requires() {
        val id = recording()
        insert(
            (0 until 2_001).map { point(id, it, segment = if (it < 1_000) 0 else 1, mslAltitude = if (it % 2 == 0) 600.0 + it else null) },
        )

        val root = parse(export(id)).documentElement

        assertEquals("gpx", root.localName)
        assertEquals(NS, root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        assertTrue(root.getAttribute("creator").isNotEmpty())
        assertEquals(listOf("metadata", "trk"), root.childElements().map { it.localName })
        assertEquals(listOf("name", "time"), root.children("metadata").single().childElements().map { it.localName })
        val trk = root.children("trk").single()
        assertEquals(listOf("name", "trkseg", "trkseg"), trk.childElements().map { it.localName })
        for (trkpt in root.trkpts()) {
            assertTrue(trkpt.getAttribute("lat").toDouble() in -90.0..90.0)
            assertTrue(trkpt.getAttribute("lon").toDouble().let { it >= -180.0 && it < 180.0 })
            // ele, if present, comes before time.
            val names = trkpt.childElements().map { it.localName }
            assertTrue(names.toString(), names == listOf("time") || names == listOf("ele", "time"))
            Instant.parse(trkpt.children("time").single().textContent)
            trkpt.children("ele").forEach { it.textContent.toDouble() }
        }
    }

    @Test
    fun clearRemovesTheExportedFile() {
        val id = recording()
        insert(listOf(point(id, 0)))
        val uri = runBlocking { exporter.export(id) }
        val file = File(File(context.cacheDir, EXPORTS), uri.lastPathSegment!!)
        assertTrue(file.exists())

        runBlocking { exporter.clear() }

        assertFalse(file.exists())
    }

    @Test
    fun clearIfForRemovesTheFileOfThatRecording() {
        val id = recording()
        insert(listOf(point(id, 0)))
        runBlocking { exporter.export(id) }
        assertEquals(1, File(context.cacheDir, EXPORTS).list()!!.size)

        runBlocking { exporter.clearIfFor(listOf(id, id + 100)) }

        assertFalse(File(context.cacheDir, EXPORTS).exists())
    }

    @Test
    fun clearIfForLeavesTheFileOfAnotherRecordingAlone() {
        // A route deleted in the background must not take away the file the user is sharing right now.
        val shared = recording()
        val other = dao.insertRecording(RecordingEntity(startedAtMillis = START + 7_200_000, state = RecordingState.FINISHED))
        insert(listOf(point(shared, 0)))
        insert(listOf(point(other, 0)))
        val uri = runBlocking { exporter.export(shared) }

        runBlocking { exporter.clearIfFor(listOf(other)) }

        assertEquals(1, File(context.cacheDir, EXPORTS).list()!!.size)
        assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.isNotEmpty())
    }

    @Test
    fun clearIfForRemovesAFileWhoseRecordingIsNotKnown() {
        // Left over from an earlier run of the app: nobody is sharing it anymore, and it holds a route.
        File(context.cacheDir, EXPORTS).apply { mkdirs() }.resolve("routelog-old.gpx").writeText("<gpx/>")

        runBlocking { exporter.clearIfFor(listOf(1)) }

        assertFalse(File(context.cacheDir, EXPORTS).exists())
    }

    @Test
    fun exportingAgainKeepsOnlyTheLatestFile() {
        val first = recording()
        val second = dao.insertRecording(RecordingEntity(startedAtMillis = START + 7_200_000, state = RecordingState.FINISHED))
        insert(listOf(point(first, 0)))
        insert(listOf(point(second, 0)))

        export(first)
        export(second)

        assertEquals(1, File(context.cacheDir, EXPORTS).list()!!.size)
    }

    @Test
    fun exportsAndClearsAtTheSameTimeNeverLeaveTwoFiles() {
        // Export and clear share the folder: two exports that ran into each other would
        // each clear the folder first and then both write, leaving two copies of routes behind.
        val first = recording()
        val second = dao.insertRecording(RecordingEntity(startedAtMillis = START + 7_200_000, state = RecordingState.FINISHED))
        insert((0 until 300).map { point(first, it) })
        insert((0 until 300).map { point(second, it) })

        repeat(20) { round ->
            runBlocking {
                coroutineScope {
                    launch(Dispatchers.IO) { exporter.export(first) }
                    launch(Dispatchers.IO) { exporter.export(second) }
                    launch(Dispatchers.IO) { exporter.clearIfFor(listOf(first)) }
                    launch(Dispatchers.IO) { exporter.export(first) }
                }
            }
            val files = File(context.cacheDir, EXPORTS).list().orEmpty()
            assertTrue("round $round: ${files.size} files", files.size <= 1)
        }
    }

    @Test
    fun theFolderIsNamedAfterTheLibraryAndTheShareUriPointsIntoIt() {
        val id = recording()
        insert(listOf(point(id, 0)))

        val uri = runBlocking { exporter.export(id) }

        assertEquals(listOf(uri.lastPathSegment), File(context.cacheDir, EXPORTS).list()!!.toList())
        assertEquals("routelog_exports", uri.pathSegments.first())
        assertEquals("${context.packageName}.routelog.fileprovider", uri.authority)
    }

    @Test
    fun aLeftoverFolderOfOlderVersionsWithOnlyOurFilesIsRemoved() {
        val legacy = File(context.cacheDir, LEGACY_EXPORTS).apply { mkdirs() }
        legacy.resolve("routelog-2026-10-06-1220.gpx").writeText("<gpx/>")
        val id = recording()
        insert(listOf(point(id, 0)))

        runBlocking { exporter.export(id) }

        assertFalse(legacy.exists())
        assertEquals(1, File(context.cacheDir, EXPORTS).list()!!.size)
    }

    @Test
    fun aLeftoverFolderWithOtherContentIsNeverTouched() {
        // A host app or plugin may use the name "exports" for its own files.
        val legacy = File(context.cacheDir, LEGACY_EXPORTS).apply { mkdirs() }
        val ours = legacy.resolve("routelog-2026-10-06-1220.gpx").apply { writeText("<gpx/>") }
        val foreign = legacy.resolve("report.pdf").apply { writeText("not ours") }
        val id = recording()
        insert(listOf(point(id, 0)))

        try {
            runBlocking { exporter.export(id) }
            runBlocking { exporter.clear() }

            assertTrue(ours.exists())
            assertEquals("not ours", foreign.readText())
        } finally {
            legacy.deleteRecursively()
        }
    }

    @Test
    fun clearAlsoRemovesALeftoverFolderWithOnlyOurFiles() {
        val legacy = File(context.cacheDir, LEGACY_EXPORTS).apply { mkdirs() }
        legacy.resolve("routelog-2026-10-06-1220.gpx").writeText("<gpx/>")

        runBlocking { exporter.clear() }

        assertFalse(legacy.exists())
    }

    private companion object {
        const val TEST_DB = "routelog-exporter-test.db"
        const val EXPORTS = "routelog_exports"
        const val LEGACY_EXPORTS = "exports"
        const val NS = "http://www.topografix.com/GPX/1/1"
        const val START = 1_791_289_200_000L // 2026-10-06T12:20:00Z
    }
}
