package io.github.markusnuo.routelog.geo

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class GeoTest {
    private val metersPerDegree = Geo.EARTH_MEAN_RADIUS_METERS * PI / 180

    @Test
    fun oneDegreeOfLatitude() {
        assertEquals(111_195.08, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 0.01)
    }

    @Test
    fun longitudeShrinksWithLatitude() {
        // 0.001° of longitude at 47° N (St. Gallen) is about 75.8 m.
        val expected = metersPerDegree * 0.001 * cos(Math.toRadians(47.0))
        assertEquals(expected, Geo.distanceMeters(47.0, 9.0, 47.0, 9.001), 0.01)
    }

    @Test
    fun samePointIsZero() {
        assertEquals(0.0, Geo.distanceMeters(47.4245, 9.3767, 47.4245, 9.3767), 0.0)
    }

    @Test
    fun isSymmetric() {
        val there = Geo.distanceMeters(47.4245, 9.3767, 47.3769, 8.5417)
        val back = Geo.distanceMeters(47.3769, 8.5417, 47.4245, 9.3767)
        assertEquals(there, back, 1e-6)
    }

    @Test
    fun matchesFlatApproximationOverRegionalDistances() {
        // St. Gallen to Zürich, about 63 km. Over this range a local flat projection is accurate
        // to far better than 0.01 %, which makes it an independent reference.
        val (lat1, lon1, lat2, lon2) = listOf(47.4245, 9.3767, 47.3769, 8.5417)
        val meanLat = Math.toRadians((lat1 + lat2) / 2)
        val dy = (lat2 - lat1) * metersPerDegree
        val dx = (lon2 - lon1) * metersPerDegree * cos(meanLat)
        val flat = Math.hypot(dx, dy)
        assertEquals(flat, Geo.distanceMeters(lat1, lon1, lat2, lon2), flat * 1e-4)
    }

    @Test
    fun antipodesDoNotOverflow() {
        assertEquals(PI * Geo.EARTH_MEAN_RADIUS_METERS, Geo.distanceMeters(0.0, 0.0, 0.0, 180.0), 1.0)
    }
}
