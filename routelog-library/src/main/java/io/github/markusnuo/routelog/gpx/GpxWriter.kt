package io.github.markusnuo.routelog.gpx

import java.io.Writer
import java.time.Instant
import java.util.Locale

data class GpxPoint(
    val segment: Int,
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    /** Height above mean sea level. GPX `ele` is not the GPS ellipsoid height. */
    val elevationMeters: Double?,
)

/** Writes a GPX 1.1 track. Points must be in recording order; a new segment starts a `trkseg`. */
object GpxWriter {
    fun write(out: Writer, name: String, startTimeMillis: Long, points: Sequence<GpxPoint>) {
        val escapedName = escape(name)
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        out.write("\n")
        out.write(
            """<gpx version="1.1" creator="routelog" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""",
        )
        out.write("\n  <metadata>\n    <name>$escapedName</name>\n    <time>${time(startTimeMillis)}</time>\n  </metadata>")
        out.write("\n  <trk>\n    <name>$escapedName</name>")
        var segment: Int? = null
        for (point in points) {
            if (point.segment != segment) {
                if (segment != null) out.write("\n    </trkseg>")
                out.write("\n    <trkseg>")
                segment = point.segment
            }
            out.write("\n      <trkpt lat=\"${coordinate(point.latitude)}\" lon=\"${longitude(point.longitude)}\">")
            point.elevationMeters?.let { out.write("<ele>${String.format(Locale.ROOT, "%.1f", it)}</ele>") }
            out.write("<time>${time(point.timeMillis)}</time></trkpt>")
        }
        if (segment != null) out.write("\n    </trkseg>")
        out.write("\n  </trk>\n</gpx>\n")
        out.flush()
    }

    /** Seven decimals are about 1 cm; Locale.ROOT keeps the decimal point in every locale. */
    private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.7f", value)

    /** GPX allows -180 up to, but not including, 180; both are the same meridian. */
    private fun longitude(value: Double): String =
        coordinate(value).let { if (it == "180.0000000") "-180.0000000" else it }

    private fun time(millis: Long) = Instant.ofEpochMilli(millis).toString()

    private fun escape(text: String) = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c >= ' ' || c == '\t' || c == '\n' || c == '\r') append(c)
            }
        }
    }
}
