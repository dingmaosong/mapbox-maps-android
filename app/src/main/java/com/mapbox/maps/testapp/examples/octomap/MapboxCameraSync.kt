package com.mapbox.maps.testapp.examples.octomap

import com.mapbox.maps.CameraState

object MapboxCameraSync {
    private var renderer: GLRenderer? = null

    fun setRenderer(glRenderer: GLRenderer?) {
        this.renderer = glRenderer
    }

    fun sync(cameraState: CameraState) {
        renderer?.updateCamera(
            cameraState.center.latitude(),
            cameraState.center.longitude(),
            cameraState.bearing,
            cameraState.pitch,
            cameraState.zoom
        )
    }
}
