package io.github.markusnuo.routelog.gpx

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.StringReader
import java.io.StringWriter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class GpxWriterTest {
    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private val start = 1_791_289_200_000L // 2026-10-06T12:20:00Z

    private fun point(segment: Int, i: Int, elevation: Double? = null) =
        GpxPoint(segment, 47.4245 + i * 0.0001, 9.3767 + i * 0.0001, start + i * 1000L, elevation)

    private fun gpx(name: String = "routelog 6 Oct 2026", points: List<GpxPoint>): String =
        StringWriter().also { GpxWriter.write(it, name, start, points.asSequence()) }.toString()

    private fun assertValid(xml: String) {
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            .newSchema(StreamSource(javaClass.getResourceAsStream("/gpx/gpx-1.1.xsd")))
        schema.newValidator().validate(StreamSource(StringReader(xml)))
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(org.xml.sax.InputSource(StringReader(xml)))

    @Test
    fun outputIsValidGpx11() {
        assertValid(gpx(points = (0..9).map { point(0, it, elevation = 670.0 + it) }))
    }

    @Test
    fun longRecordingWithSegmentsAndMixedElevationIsValid() {
        // The shape of an export: several thousand points, a resume in the middle, sea level
        // height on some points only.
        val points = (0 until 4_001).map {
            point(if (it < 2_500) 0 else 1, it, elevation = if (it % 3 == 0) 400.0 + it % 50 else null)
        }
        val xml = gpx(points = points)
        assertValid(xml)
        assertEquals(2, parse(xml).getElementsByTagNameNS(NS, "trkseg").length)
        assertEquals(4_001, parse(xml).getElementsByTagNameNS(NS, "trkpt").length)
    }

    @Test
    fun emptyRecordingIsStillValid() {
        val xml = gpx(points = emptyList())
        assertValid(xml)
        assertEquals(0, parse(xml).getElementsByTagNameNS(NS, "trkseg").length)
    }

    @Test
    fun eachSegmentBecomesATrkseg() {
        val points = (0..2).map { point(0, it) } + (3..4).map { point(1, it) } + listOf(point(3, 5))
        val xml = gpx(points = points)
        assertValid(xml)
        val segments = parse(xml).getElementsByTagNameNS(NS, "trkseg")
        assertEquals(3, segments.length)
        assertEquals(listOf(3, 2, 1), (0 until segments.length).map {
            (segments.item(it) as Element).getElementsByTagNameNS(NS, "trkpt").length
        })
    }

    @Test
    fun coordinatesUseADecimalPointInEveryLocale() {
        Locale.setDefault(Locale.GERMANY)
        val xml = gpx(points = listOf(point(0, 0, elevation = 670.25)))
        assertValid(xml)
        assertTrue(xml, xml.contains("""<trkpt lat="47.4245000" lon="9.3767000">"""))
        assertTrue(xml, xml.contains("<ele>670.3</ele>") || xml.contains("<ele>670.2</ele>"))
    }

    @Test
    fun elevationOnlyWhenKnown() {
        val xml = gpx(points = listOf(point(0, 0, elevation = null), point(0, 1, elevation = 500.0)))
        assertValid(xml)
        val points = parse(xml).getElementsByTagNameNS(NS, "trkpt")
        assertEquals(0, (points.item(0) as Element).getElementsByTagNameNS(NS, "ele").length)
        assertEquals("500.0", (points.item(1) as Element).getElementsByTagNameNS(NS, "ele").item(0).textContent)
    }

    @Test
    fun timesAreUtcIso8601() {
        val xml = gpx(points = listOf(point(0, 0), point(0, 1)))
        val times = parse(xml).getElementsByTagNameNS(NS, "time")
        // metadata, then one per point
        assertEquals("2026-10-06T12:20:00Z", times.item(0).textContent)
        assertEquals("2026-10-06T12:20:01Z", times.item(2).textContent)
    }

    @Test
    fun nameIsEscaped() {
        val name = """Lesson <1> & "test" 'x'"""
        val xml = gpx(name = name, points = listOf(point(0, 0)))
        assertValid(xml)
        val names = parse(xml).getElementsByTagNameNS(NS, "name")
        assertEquals(name, names.item(0).textContent)
        assertFalse(xml.contains("<1>"))
    }

    @Test
    fun controlCharactersInTheNameAreDropped() {
        val xml = gpx(name = "a\u0001b", points = emptyList())
        assertValid(xml)
        assertEquals("ab", parse(xml).getElementsByTagNameNS(NS, "name").item(0).textContent)
    }

    @Test
    fun negativeCoordinatesAreWritten() {
        val xml = gpx(points = listOf(GpxPoint(0, -33.8688, -151.2093, start, null)))
        assertValid(xml)
        assertTrue(xml, xml.contains("""lat="-33.8688000" lon="-151.2093000""""))
    }

    @Test
    fun longitude180IsWrittenAsMinus180() {
        // The schema allows -180 up to, but not including, 180; both are the same meridian.
        val xml = gpx(points = listOf(GpxPoint(0, 10.0, 180.0, start, null), GpxPoint(0, 10.0, -180.0, start + 1000, null)))
        assertValid(xml)
        assertTrue(xml, xml.contains("""lat="10.0000000" lon="-180.0000000""""))
        assertFalse(xml, xml.contains("lon=\"180.0000000\""))
    }

    @Test
    fun longitudeThatRoundsTo180IsWrittenAsMinus180() {
        val xml = gpx(points = listOf(GpxPoint(0, 10.0, 179.99999999, start, null)))
        assertValid(xml)
        assertTrue(xml, xml.contains("""lon="-180.0000000""""))
    }

    @Test
    fun longitudeJustBelow180StaysAsItIs() {
        val xml = gpx(points = listOf(GpxPoint(0, 10.0, 179.9999999, start, null)))
        assertValid(xml)
        assertTrue(xml, xml.contains("""lon="179.9999999""""))
    }

    private companion object {
        const val NS ="http://www.topografix.com/GPX/1/1"
    }
}
