package com.mapbox.maps.testapp.examples.octomap

import android.content.res.AssetManager
import androidx.annotation.Keep
import com.mapbox.maps.CustomLayerHost
import com.mapbox.maps.CustomLayerRenderParameters

/**
 * JNI Wrapper for OctoMap loading and rendering.
 * Acts as the bridge between Kotlin and C++.
 */
@Keep
class OctoMapLoader(private val assetManager: AssetManager, private val filePath: String) : CustomLayerHost {

    external override fun initialize()
    external override fun render(parameters: CustomLayerRenderParameters)
    external override fun contextLost()
    external override fun deinitialize()

    // Additional JNI methods for the PoC
    external fun setOrigin(lat: Double, lng: Double, alt: Double)
    external fun loadOctoMap(path: String): Boolean
    external fun getVoxelCount(): Int
    
    // Retrieve Bounds from Native
    external fun getBoundsMin(): FloatArray // [x, y, z]
    external fun getBoundsMax(): FloatArray // [x, y, z]
    external fun getResolution(): Float
    external fun getVoxels(): FloatArray


    companion object {
        init {
            System.loadLibrary("example-cpp-custom-layer")
        }
    }
}
