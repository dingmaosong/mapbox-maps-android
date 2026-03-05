package com.mapbox.maps.testapp.examples.octomap

import kotlin.math.cos
import kotlin.math.PI

object EnuConverter {
    private const val EARTH_RADIUS = 6378137.0

    /**
     * Converts ENU (East-North-Up) coordinates to WGS84 (Lat, Lng, Alt).
     * @param x East offset in meters
     * @param y North offset in meters
     * @param z Up offset in meters
     * @param origin The origin point in WGS84
     * @return The target point in WGS84
     */
    fun toWorld(x: Double, y: Double, z: Double, origin: Waypoint): Waypoint {
        val dLat = (y / EARTH_RADIUS) * (180.0 / PI)
        val dLng = (x / (EARTH_RADIUS * cos(origin.lat * PI / 180.0))) * (180.0 / PI)
        
        return Waypoint(
            lat = origin.lat + dLat,
            lng = origin.lng + dLng,
            alt = origin.alt + z
        )
    }

    /**
     * Converts Voxel coordinates (assumed to be in ENU frame relative to origin) to WGS84.
     */
    fun toWorld(voxel: Voxel, origin: Waypoint): Waypoint {
        return toWorld(voxel.x.toDouble(), voxel.y.toDouble(), voxel.z.toDouble(), origin)
    }

    /**
     * Converts WGS84 to ENU (East-North-Up) relative to origin.
     */
    fun toEnu(target: Waypoint, origin: Waypoint): Voxel {
        val dLat = target.lat - origin.lat
        val dLng = target.lng - origin.lng
        
        val y = dLat * (PI / 180.0) * EARTH_RADIUS
        val x = dLng * (PI / 180.0) * (EARTH_RADIUS * cos(origin.lat * PI / 180.0))
        val z = target.alt - origin.alt
        
        return Voxel(x.toFloat(), y.toFloat(), z.toFloat(), 0f)
    }
}
