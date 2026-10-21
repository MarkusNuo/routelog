package io.github.markusnuo.routelog.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** IUGG mean Earth radius. Haversine on a sphere is within ~0.5 % of the ellipsoid. */
    const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lon2 - lon1)
        val h = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_MEAN_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }
}
