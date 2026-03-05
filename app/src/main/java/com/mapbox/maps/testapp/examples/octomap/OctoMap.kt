package com.mapbox.maps.testapp.examples.octomap

data class OctoMap(
    val origin: Waypoint, // Lat, Lng, Alt
    val resolution: Double,
    val bounds: OctoMapBounds,
    val occupiedVoxels: List<Voxel> = emptyList() // Or handled in Native
)
