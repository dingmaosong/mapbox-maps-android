package com.mapbox.maps.testapp.examples

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.common.ValueConverter
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.rasterLayer
import com.mapbox.maps.extension.style.sources.TileSet
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.RasterSource
import com.mapbox.maps.extension.style.sources.generated.Scheme
import com.mapbox.maps.extension.style.sources.generated.rasterSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.logI
import com.mapbox.maps.testapp.R

/**
 * Activity showcases usage of custom tile sources to load map data from Chinese providers.
 *
 * This example uses Gaode (高德) or Tianditu (天地图) tiles as a raster source and visualises 
 * them using a raster layer, eliminating the need to access Mapbox servers.
 */
class CustomTileSourceActivity : AppCompatActivity() {

  private lateinit var mapboxMap: MapboxMap
  private var currentProvider = TileProvider.GAODE

  enum class TileProvider {
    GAODE,
    TIANDITU
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_custom_layer)
    val mapView: MapView = findViewById(R.id.mapView)
    
    setupMapWithProvider(mapView, currentProvider)

    // Click on button to print out tile set information
    findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
      if (::mapboxMap.isInitialized) {
        mapboxMap.getStyle {
          val properties = it.getStyleSourceProperties(SOURCE_ID).value!!
          val propertiesJson = ValueConverter.toJson(properties)
          logI(TAG, propertiesJson)
          Toast.makeText(this, propertiesJson, Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  private fun setupMapWithProvider(mapView: MapView, provider: TileProvider) {
    val tileSet = when(provider) {
      TileProvider.GAODE -> {
        TileSet.Builder(TILE_JSON_VERSION, listOf(GAODE_RASTER_TILE_URL))
          .name(GAODE_TILE_JSON_NAME)
          .description(GAODE_TILE_JSON_DESCRIPTION)
          .attribution(GAODE_TILE_JSON_ATTRIBUTION)
          .scheme(Scheme.XYZ)
          .minZoom(TILE_JSON_MIN_ZOOM)
          .maxZoom(TILE_JSON_MAX_ZOOM)
          .bounds(MERCATOR_BOUNDS)
          .center(CENTER_GUANGZHOU)
          .build()
      }
      TileProvider.TIANDITU -> {
        TileSet.Builder(TILE_JSON_VERSION, listOf(TIANDITU_RASTER_TILE_URL))
          .name(TIANDITU_TILE_JSON_NAME)
          .description(TIANDITU_TILE_JSON_DESCRIPTION)
          .attribution(TIANDITU_TILE_JSON_ATTRIBUTION)
          .scheme(Scheme.XYZ)
          .minZoom(TILE_JSON_MIN_ZOOM)
          .maxZoom(TILE_JSON_MAX_ZOOM)
          .bounds(MERCATOR_BOUNDS)
          .center(CENTER_GUANGZHOU)
          .build()
      }
    }

    mapboxMap = mapView.mapboxMap
    mapboxMap.setCamera(
      com.mapbox.maps.CameraOptions.Builder()
        .center(com.mapbox.geojson.Point.fromLngLat(GUANGZHOU_LNG, GUANGZHOU_LAT))
        .zoom(INITIAL_ZOOM)
        .build()
    )
    mapboxMap.loadStyle(Style.LIGHT) { style ->
      // Remove default sources and layers to ensure we only use our custom source
      style.removeStyleLayer("land")
      
      style.addSource(
        rasterSource(SOURCE_ID) {
          tileSet(tileSet)
          tileSize(RASTER_TILE_SIZE_PIXELS)
        }
      )
      style.addLayer(rasterLayer(LAYER_ID, SOURCE_ID) {})
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_tile_provider, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.menu_switch_to_gaode -> {
        currentProvider = TileProvider.GAODE
        val mapView: MapView = findViewById(R.id.mapView)
        setupMapWithProvider(mapView, currentProvider)
        true
      }
      R.id.menu_switch_to_tianditu -> {
        currentProvider = TileProvider.TIANDITU
        val mapView: MapView = findViewById(R.id.mapView)
        setupMapWithProvider(mapView, currentProvider)
        true
      }
      else -> {
        super.onOptionsItemSelected(item)
      }
    }
  }

  private companion object {
    const val SOURCE_ID = "custom"
    const val LAYER_ID = SOURCE_ID
    const val TAG = "CustomTileSource"

    const val TILE_JSON_VERSION = "2.0.0"
    const val TILE_JSON_MIN_ZOOM = 0
    const val TILE_JSON_MAX_ZOOM = 18
    const val INITIAL_ZOOM = 16.0

    // Guangzhou GaoPu Road 115 coordinates
    const val GUANGZHOU_LAT = 23.175
    const val GUANGZHOU_LNG = 113.4147
    val CENTER_GUANGZHOU = listOf(GUANGZHOU_LNG, GUANGZHOU_LAT)

    // Gaode Maps (高德地图)
    const val GAODE_TILE_JSON_NAME = "Gaode Maps"
    const val GAODE_TILE_JSON_DESCRIPTION = "Gaode Maps with road network"
    const val GAODE_TILE_JSON_ATTRIBUTION = "&copy; Gaode Maps contributors"
    const val GAODE_RASTER_TILE_URL = "https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"
    
    // Tianditu (天地图)
    const val TIANDITU_TILE_JSON_NAME = "Tianditu"
    const val TIANDITU_TILE_JSON_DESCRIPTION = "China National Geomatics Center Tianditu"
    const val TIANDITU_TILE_JSON_ATTRIBUTION = "&copy; Tianditu contributors"
    const val TIANDITU_RASTER_TILE_URL = "http://t4.tianditu.com/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}"

    const val RASTER_TILE_SIZE_PIXELS = 256L

    val MERCATOR_BOUNDS = listOf(-180.0, -85.0, 180.0, 85.0)
  }
}