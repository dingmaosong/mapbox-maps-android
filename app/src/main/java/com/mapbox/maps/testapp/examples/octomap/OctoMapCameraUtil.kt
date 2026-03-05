package com.mapbox.maps.testapp.examples.octomap

import com.mapbox.geojson.Point
import kotlin.math.*

object OctoMapCameraUtil {

    // Earth Radius in meters (WGS84 semi-major axis)
    private const val EARTH_RADIUS = 6378137.0

    /**
     * Compute center LatLng from OctoMap bounds (in local ENU) and Origin (WGS84).
     */
    fun computeCenter(origin: Waypoint, bounds: OctoMapBounds): Point {
        // Local center in ENU (meters)
        val enuCenterX = bounds.centerX()
        val enuCenterY = bounds.centerY()
        
        // Convert ENU offset to LatLng offset (approximate for small areas)
        val dLat = Math.toDegrees(enuCenterY / EARTH_RADIUS)
        val dLng = Math.toDegrees(enuCenterX / (EARTH_RADIUS * cos(Math.toRadians(origin.lat))))
        
        return Point.fromLngLat(
            origin.lng + dLng,
            origin.lat + dLat
        )
    }

    /**
     * Compute appropriate Zoom level to fit the bounds.
     * Mapbox Zoom 0 = 512px for the world.
     * Zoom N = 512 * 2^N pixels.
     * Meters per pixel = (cos(lat) * 2 * PI * R) / (512 * 2^zoom)
     */
    fun computeZoom(bounds: OctoMapBounds, screenWidthPixels: Int = 1080): Double {
        val maxDimMeters = max(bounds.width(), bounds.height())
        if (maxDimMeters <= 0) return 18.0 // Default fallback
        
        // We want the object to fill e.g. 50% of the screen width
        val targetMetersPerPixel = maxDimMeters / (screenWidthPixels * 0.5)
        
        // At Equator (simplification, but sufficient for zoom estimation)
        // C = 40,075,017 meters
        val C = 40075017.0
        val zoom = log2(C / (512.0 * targetMetersPerPixel))
        
        return zoom.coerceIn(0.0, 22.0)
    }
}
