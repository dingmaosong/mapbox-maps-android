package com.mapbox.maps.testapp.examples.octomap

data class OctoMapBounds(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double
) {
    fun width() = maxX - minX
    fun height() = maxY - minY
    fun depth() = maxZ - minZ
    
    fun centerX() = (minX + maxX) / 2.0
    fun centerY() = (minY + maxY) / 2.0
    fun centerZ() = (minZ + maxZ) / 2.0
}
