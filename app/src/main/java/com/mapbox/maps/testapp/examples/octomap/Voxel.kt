package com.mapbox.maps.testapp.examples.octomap

data class Voxel(
    val x: Float,
    val y: Float,
    val z: Float,
    val size: Float = 0.1f, // Default resolution, can be overridden
    val r: Float = 1.0f,
    val g: Float = 1.0f,
    val b: Float = 1.0f
)
