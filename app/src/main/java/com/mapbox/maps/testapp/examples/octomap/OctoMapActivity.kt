package com.mapbox.maps.testapp.examples.octomap

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.common.Cancelable
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.CustomLayer
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.logE
import com.mapbox.maps.logI
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.testapp.R

class OctoMapActivity : AppCompatActivity() {

    private lateinit var mapboxMap: MapboxMap
    private lateinit var octoMapLoader: OctoMapLoader
    private var glSurfaceView: GLSurfaceView? = null
    private var glRenderer: GLRenderer? = null
    private var cameraChangeCancelable: Cancelable? = null
    
    // Default Origin (Shanghai People's Square)
    private val originLat = 31.23
    private val originLng = 121.47
    private val originAlt = 15.0
    
    private val octoFileName = "octoMap/20251127T173317_simple_color_tree.ot"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_layer)
        val mapView = findViewById<MapView>(R.id.mapView)
        mapboxMap = mapView.mapboxMap

        // Setup GLSurfaceView Overlay
        setupGLSurfaceView(mapView)

        octoMapLoader = OctoMapLoader(assets, octoFileName)

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            Toast.makeText(this, "OctoMap loaded at origin ($originLat, $originLng)", Toast.LENGTH_SHORT).show()
        }

        mapboxMap.loadStyle(Style.DARK) { style ->
            octoMapLoader.setOrigin(originLat, originLng, originAlt)
            style.addLayer(CustomLayer("octo-layer", octoMapLoader))
            
            if (octoMapLoader.loadOctoMap(octoFileName)) {
                // 1. Get Bounds and Auto-Position
                val min = octoMapLoader.getBoundsMin()
                val max = octoMapLoader.getBoundsMax()
                
                val bounds = OctoMapBounds(
                    min[0].toDouble(), min[1].toDouble(), min[2].toDouble(),
                    max[0].toDouble(), max[1].toDouble(), max[2].toDouble()
                )
                
                val originWaypoint = Waypoint(originLat, originLng, originAlt)
                val centerPoint = OctoMapCameraUtil.computeCenter(originWaypoint, bounds)
                val zoomLevel = OctoMapCameraUtil.computeZoom(bounds)
                
                logI("OctoMapActivity", "Auto-positioning to: $centerPoint, Zoom: $zoomLevel")
                
                mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(centerPoint)
                        .zoom(zoomLevel)
                        .pitch(60.0)
                        .bearing(0.0)
                        .build()
                )

                // 2. Get Voxels and Build Mesh for GLRenderer
                val voxelCount = octoMapLoader.getVoxelCount()
                logI("OctoMapActivity", "Voxel count: $voxelCount")
                Toast.makeText(this, "Voxel count: $voxelCount", Toast.LENGTH_SHORT).show()
                val voxelData = octoMapLoader.getVoxels()
                
                val voxels = ArrayList<Voxel>()
                for (i in voxelData.indices step 6) {
                    voxels.add(
                        Voxel(
                            voxelData[i],
                            voxelData[i + 1],
                            voxelData[i + 2],
                            r = voxelData[i + 3],
                            g = voxelData[i + 4],
                            b = voxelData[i + 5]
                        )
                    )
                }
                
                val mesh = VoxelMeshBuilder.build(voxels)
                
                // Set to Renderer
                glSurfaceView?.queueEvent {
                    glRenderer?.setOrigin(originWaypoint)
                    glRenderer?.setMesh(mesh)
                }
            }

            // 3. Setup Camera Sync
            MapboxCameraSync.setRenderer(glRenderer)
            cameraChangeCancelable = mapboxMap.subscribeCameraChanged {
                MapboxCameraSync.sync(mapboxMap.cameraState)
            }
            
            // 4. Waypoint Click
            mapboxMap.addOnMapClickListener { point ->
                val waypoint = Waypoint(point.latitude(), point.longitude(), 0.0)
                Toast.makeText(this, "Waypoint: ${point.latitude()}, ${point.longitude()}", Toast.LENGTH_SHORT).show()
                val enu = EnuConverter.toEnu(waypoint, Waypoint(originLat, originLng, originAlt))
                logI("Waypoint", "ENU: ${enu.x}, ${enu.y}, ${enu.z}")
                true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraChangeCancelable?.cancel()
        MapboxCameraSync.setRenderer(null)
    }

    private fun setupGLSurfaceView(mapView: MapView) {
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView?.setEGLContextClientVersion(2)
        
        // Transparent background and overlay
        glSurfaceView?.setZOrderMediaOverlay(true)
        glSurfaceView?.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView?.holder?.setFormat(PixelFormat.TRANSLUCENT)
        
        glRenderer = GLRenderer()
        glSurfaceView?.setRenderer(glRenderer)
        glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Add to layout on top of MapView
        // We assume CoordinatorLayout based on layout file
        val parent = mapView.parent as ViewGroup
        val params = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.MATCH_PARENT
        )
        // Add at index 1 to be above MapView (index 0) but below FAB (index 1 originally)
        // If we just add, it goes to end.
        parent.addView(glSurfaceView, params)
    }
}
