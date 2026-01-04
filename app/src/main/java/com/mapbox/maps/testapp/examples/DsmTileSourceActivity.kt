package com.mapbox.maps.testapp.examples

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CanonicalTileID
import com.mapbox.maps.CustomRasterSourceClient
import com.mapbox.maps.CustomRasterSourceTileData
import com.mapbox.maps.CustomRasterSourceTileStatus
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.atmosphere.generated.atmosphere
import com.mapbox.maps.extension.style.atmosphere.generated.setAtmosphere
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerAbove
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.layers.generated.hillshadeLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.rasterLayer
import com.mapbox.maps.extension.style.layers.generated.skyLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.ProjectionName
import com.mapbox.maps.extension.style.layers.properties.generated.SkyType
import com.mapbox.maps.extension.style.projection.generated.projection
import com.mapbox.maps.extension.style.projection.generated.setProjection
import com.mapbox.maps.extension.style.sources.TileSet
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.customRasterSource
import com.mapbox.maps.extension.style.sources.generated.Scheme
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.generated.rasterDemSource
import com.mapbox.maps.extension.style.sources.generated.rasterSource
import com.mapbox.maps.extension.style.terrain.generated.setTerrain
import com.mapbox.maps.extension.style.terrain.generated.terrain
import com.mapbox.maps.logE
import com.mapbox.maps.logW
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.testapp.R
import com.mapbox.maps.toMapboxImage
import com.mapbox.maps.util.isEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@OptIn(MapboxExperimental::class)
class DsmTileSourceActivity : AppCompatActivity(), OnMapClickListener {

  private lateinit var mapboxMap: MapboxMap
  private lateinit var dsmRasterSource: com.mapbox.maps.extension.style.sources.CustomRasterSource
  private val dsmRootDirCandidates: List<File> by lazy {
    listOf(
      File(filesDir, "dsm/xag_dsm_webp_tiles"),
    )
  }
  private val resolvedDsmRootDir: File? by lazy {
    dsmRootDirCandidates.firstOrNull { File(it, BOUNDARY_FILE_NAME).exists() }
      ?: dsmRootDirCandidates.firstOrNull { it.exists() && it.isDirectory }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_custom_layer)
    val mapView: MapView = findViewById(R.id.mapView)
    mapboxMap = mapView.mapboxMap
    dsmRasterSource = createDsmRasterSource()
    mapboxMap.addOnMapClickListener(this)
    findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
      showDsmStatus()
    }
    setupMap(Style.OFFLINE_TEST)
  }

  private fun setupMap(styleUri: String) {
    mapboxMap.loadStyle(styleUri) { style ->
      if (styleUri == Style.OFFLINE && style.styleLayerExists("land")) {
        style.removeStyleLayer("land")
      }

      style.addSource(
        rasterDemSource(TERRAIN_SOURCE_ID) {
          url(TERRAIN_URL_TILE_RESOURCE)
          tileSize(512)
        }
      )
      style.setTerrain(
        terrain(TERRAIN_SOURCE_ID) {
          exaggeration(TERRAIN_EXAGGERATION)
        }
      )
      style.addLayer(
        skyLayer(SKY_LAYER_ID) {
          skyType(SkyType.ATMOSPHERE)
          skyAtmosphereSun(listOf(-50.0, 90.2))
        }
      )
      style.setAtmosphere(atmosphere { })
      style.setProjection(projection(ProjectionName.GLOBE))

      val tiandituTileSet = TileSet.Builder(TILE_JSON_VERSION, listOf(TIANDITU_RASTER_TILE_URL))
        .name(TIANDITU_TILE_JSON_NAME)
        .description(TIANDITU_TILE_JSON_DESCRIPTION)
        .attribution(TIANDITU_TILE_JSON_ATTRIBUTION)
        .scheme(Scheme.XYZ)
        .minZoom(TILE_JSON_MIN_ZOOM)
        .maxZoom(TILE_JSON_MAX_ZOOM)
        .bounds(MERCATOR_BOUNDS)
        .build()

      style.addSource(
        rasterSource(TIANDITU_SOURCE_ID) {
          tileSet(tiandituTileSet)
          tileSize(TIANDITU_TILE_SIZE_PIXELS)
        }
      )

      val tiandituLayer = rasterLayer(TIANDITU_LAYER_ID, TIANDITU_SOURCE_ID) {}
      if (style.styleLayerExists(TEXT_LAYER_ID)) {
        style.addLayerBelow(tiandituLayer, TEXT_LAYER_ID)
      } else {
        style.addLayer(tiandituLayer)
      }

      val hillshade = hillshadeLayer(HILLSHADE_LAYER_ID, TERRAIN_SOURCE_ID) {
        hillshadeExaggeration(HILLSHADE_EXAGGERATION)
        hillshadeShadowColor(HILLSHADE_SHADOW_COLOR)
        hillshadeHighlightColor(HILLSHADE_HIGHLIGHT_COLOR)
        hillshadeAccentColor(HILLSHADE_ACCENT_COLOR)
      }
      style.addLayerAbove(hillshade, TIANDITU_LAYER_ID)

      style.addSource(dsmRasterSource)

      val dsmLayer = rasterLayer(DSM_LAYER_ID, DSM_SOURCE_ID) {
        rasterOpacity(DSM_OPACITY)
      }
      if (style.styleLayerExists(TEXT_LAYER_ID)) {
        style.addLayerBelow(dsmLayer, TEXT_LAYER_ID)
      } else {
        style.addLayer(dsmLayer)
      }

      addObstacleLayers(style)
      setInitialCamera()
    }
  }

  private fun addObstacleLayers(style: Style) {
    val obstacleGeoJsonText = readObstacleGeoJsonText() ?: return

    if (!style.styleSourceExists(OBSTACLE_SOURCE_ID)) {
      style.addSource(
        geoJsonSource(OBSTACLE_SOURCE_ID) {
          data(obstacleGeoJsonText)
        }
      )
    }

    val obstacleFillLayer = fillLayer(OBSTACLE_FILL_LAYER_ID, OBSTACLE_SOURCE_ID) {
      fillColor(OBSTACLE_COLOR)
      fillOpacity(OBSTACLE_FILL_OPACITY)
      fillOutlineColor(OBSTACLE_OUTLINE_COLOR)
    }

    if (!style.styleLayerExists(OBSTACLE_FILL_LAYER_ID)) {
      style.addLayerAbove(obstacleFillLayer, DSM_LAYER_ID)
    }

    val obstacleLineLayer = lineLayer(OBSTACLE_LINE_LAYER_ID, OBSTACLE_SOURCE_ID) {
      lineColor(OBSTACLE_OUTLINE_COLOR)
      lineOpacity(OBSTACLE_LINE_OPACITY)
      lineWidth(OBSTACLE_LINE_WIDTH)
      lineJoin(LineJoin.ROUND)
      lineCap(LineCap.ROUND)
    }

    if (!style.styleLayerExists(OBSTACLE_LINE_LAYER_ID)) {
      style.addLayerAbove(obstacleLineLayer, OBSTACLE_FILL_LAYER_ID)
    }
  }

  private fun createDsmRasterSource(): com.mapbox.maps.extension.style.sources.CustomRasterSource {
    return customRasterSource(DSM_SOURCE_ID) {
      tileSize(DSM_TILE_SIZE_PIXELS.toShort())
      clientCallback(
        CustomRasterSourceClient { tileId: CanonicalTileID, status: CustomRasterSourceTileStatus ->
          when (status) {
            CustomRasterSourceTileStatus.REQUIRED -> loadDsmTile(tileId)
            else -> dsmRasterSource.setTileData(listOf(CustomRasterSourceTileData(tileId, null)))
          }
        }
      )
    }
  }

  private fun loadDsmTile(tileId: CanonicalTileID) {
    lifecycleScope.launch {
      val image = withContext(Dispatchers.IO) {
        val bitmap = loadDsmTileBitmap(tileId) ?: return@withContext null
        bitmap.toMapboxImage()
      }
      dsmRasterSource.setTileData(listOf(CustomRasterSourceTileData(tileId, image)))
    }
  }

  override fun onMapClick(point: Point): Boolean {
    val elevationWithExaggeration = mapboxMap.getElevation(point)
    val elevation = elevationWithExaggeration?.div(TERRAIN_EXAGGERATION)
    val message = if (elevationWithExaggeration == null) {
      "海拔：未获取到（DEM未加载或不在可见区域）"
    } else {
      val lng = String.format("%.6f", point.longitude())
      val lat = String.format("%.6f", point.latitude())
      val h = String.format("%.2f", elevation)
      val hx = String.format("%.2f", elevationWithExaggeration)
      "经纬度：$lng, $lat\n海拔：$h m（exaggeration=${TERRAIN_EXAGGERATION}，显示高度=$hx m）"
    }
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    return true
  }

  private fun setInitialCamera() {
    val boundary = readBoundaryCoordinates()
    if (boundary == null) {
      showDsmStatus()
      mapboxMap.setCamera(
        CameraOptions.Builder()
          .center(Point.fromLngLat(GUANGZHOU_LNG_WGS84, GUANGZHOU_LAT_WGS84))
          .zoom(DEFAULT_ZOOM)
          .pitch(DEFAULT_PITCH)
          .build()
      )
      return
    }

    mapboxMap.cameraForCoordinates(
      coordinates = boundary,
      camera = CameraOptions.Builder().pitch(DEFAULT_PITCH).build(),
      coordinatesPadding = EdgeInsets(80.0, 80.0, 80.0, 80.0),
      maxZoom = 18.0,
      offset = null
    ) { cameraOptions ->
      if (!cameraOptions.isEmpty) {
        mapboxMap.setCamera(cameraOptions)
        mapboxMap.setCamera(
          CameraOptions.Builder()
            .pitch(DEFAULT_PITCH)
            .bearing(DEFAULT_BEARING)
            .build()
        )
      } else {
        val center = boundary.firstOrNull()
        if (center != null) {
          mapboxMap.setCamera(
            CameraOptions.Builder()
              .center(center)
              .zoom(DEFAULT_ZOOM)
              .pitch(DEFAULT_PITCH)
              .build()
          )
        }
      }
    }
  }

  private fun readBoundaryCoordinates(): List<Point>? {
    val boundaryJson = readBoundaryJsonText() ?: return null
    return parseBoundaryCoordinates(boundaryJson)
  }

  private fun readBoundaryJsonText(): String? {
    resolvedDsmRootDir?.let { rootDir ->
      val boundaryFile = File(rootDir, BOUNDARY_FILE_NAME)
      if (boundaryFile.exists()) {
        return boundaryFile.readText()
      }
      logW(TAG, "Boundary file not found: ${boundaryFile.absolutePath}")
    }
    return runCatching {
      assets.open("$DSM_ASSET_ROOT/$BOUNDARY_FILE_NAME").bufferedReader().use { it.readText() }
    }.getOrNull()
  }

  private fun readObstacleGeoJsonText(): String? {
    resolvedDsmRootDir?.let { rootDir ->
      val obstacleFile = File(rootDir, OBSTACLE_FILE_NAME)
      if (obstacleFile.exists()) {
        return obstacleFile.readText()
      }
      logW(TAG, "Obstacle file not found: ${obstacleFile.absolutePath}")
    }
    return runCatching {
      assets.open("$DSM_ASSET_ROOT/$OBSTACLE_FILE_NAME").bufferedReader().use { it.readText() }
    }.getOrNull()
  }

  private fun parseBoundaryCoordinates(boundaryJsonText: String): List<Point>? {
    return runCatching {
      val json = JSONObject(boundaryJsonText)
      val boundary = json.getJSONArray("boundary")
      val points = ArrayList<Point>(boundary.length() / 2 + 1)
      var i = 0
      while (i + 1 < boundary.length()) {
        val lng = boundary.getDouble(i)
        val lat = boundary.getDouble(i + 1)
        points.add(Point.fromLngLat(lng, lat))
        i += 2
      }
      if (points.isNotEmpty() && points.first() != points.last()) {
        points.add(points.first())
      }
      points
    }.getOrElse {
      logE(TAG, "Failed to parse boundary.json: ${it.message}")
      null
    }
  }

  private fun loadDsmTileBitmap(tileId: CanonicalTileID): android.graphics.Bitmap? {
    resolvedDsmRootDir?.let { rootDir ->
      val tileFile = File(rootDir, "${tileId.z}/${tileId.x}/${tileId.y}.webp")
      if (tileFile.exists()) {
        return BitmapFactory.decodeFile(tileFile.absolutePath)
      }
    }
    return runCatching {
      assets.open("$DSM_ASSET_ROOT/${tileId.z}/${tileId.x}/${tileId.y}.webp").use { input ->
        BitmapFactory.decodeStream(input)
      }
    }.getOrNull()
  }

  private fun showDsmStatus() {
    val resolved = resolvedDsmRootDir
    val boundaryInAssets = runCatching { assets.open("$DSM_ASSET_ROOT/$BOUNDARY_FILE_NAME").close(); true }.getOrDefault(false)
    val message = if (resolved != null) {
      val boundaryExists = File(resolved, BOUNDARY_FILE_NAME).exists()
      "DSM目录：${resolved.absolutePath}\nboundary.json：${if (boundaryExists) "存在" else "不存在"}"
    } else {
      val candidates = dsmRootDirCandidates.joinToString(separator = "\n") { it.absolutePath }
      if (boundaryInAssets) {
        "DSM来自APK资源（assets/$DSM_ASSET_ROOT）"
      } else {
        "未找到DSM目录/资源，请把 xag_dsm_webp_tiles 放到以下任一路径，或确保被打包进assets：\n$candidates"
      }
    }
    Log.e(TAG, message)
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
  }

  private companion object {
    const val TAG = "DsmTileSourceActivity"

    const val TEXT_LAYER_ID = "text-default"

    const val TIANDITU_SOURCE_ID = "tianditu-source"
    const val TIANDITU_LAYER_ID = "tianditu-layer"
    const val TIANDITU_TILE_SIZE_PIXELS = 256L

    const val DSM_SOURCE_ID = "dsm-source"
    const val DSM_LAYER_ID = "dsm-layer"
    const val DSM_TILE_SIZE_PIXELS = 256

    const val BOUNDARY_FILE_NAME = "boundary.json"
    const val OBSTACLE_FILE_NAME = "obstacle.geojson"

    const val TILE_JSON_VERSION = "2.0.0"
    const val TILE_JSON_MIN_ZOOM = 0
    const val TILE_JSON_MAX_ZOOM = 18

    const val DEFAULT_ZOOM = 16.0
    const val DEFAULT_PITCH = 60.0
    const val DEFAULT_BEARING = 0.0

    private const val TERRAIN_SOURCE_ID = "terrain-source"
    private const val SKY_LAYER_ID = "sky"
    private const val TERRAIN_URL_TILE_RESOURCE = "mapbox://mapbox.mapbox-terrain-dem-v1"
    private const val TERRAIN_EXAGGERATION = 8.0

    private const val DSM_ASSET_ROOT = "dsm/xag_dsm_webp_tiles"

    private const val HILLSHADE_LAYER_ID = "hillshade-layer"
    private const val HILLSHADE_EXAGGERATION = 0.8
    private const val HILLSHADE_SHADOW_COLOR = 0x99000000.toInt()
    private const val HILLSHADE_HIGHLIGHT_COLOR = 0x99FFFFFF.toInt()
    private const val HILLSHADE_ACCENT_COLOR = 0x66000000.toInt()

    private const val DSM_OPACITY = 0.55

    private const val OBSTACLE_SOURCE_ID = "obstacle-source"
    private const val OBSTACLE_FILL_LAYER_ID = "obstacle-fill-layer"
    private const val OBSTACLE_LINE_LAYER_ID = "obstacle-line-layer"
    private const val OBSTACLE_COLOR = "#ff0000"
    private const val OBSTACLE_OUTLINE_COLOR = "#ff0000"
    private const val OBSTACLE_FILL_OPACITY = 0.35
    private const val OBSTACLE_LINE_OPACITY = 0.9
    private const val OBSTACLE_LINE_WIDTH = 2.0

    const val GUANGZHOU_LAT_WGS84 = 23.1788
    const val GUANGZHOU_LNG_WGS84 = 113.4101

    const val TIANDITU_TILE_JSON_NAME = "Tianditu"
    const val TIANDITU_TILE_JSON_DESCRIPTION = "China National Geomatics Center Tianditu"
    const val TIANDITU_TILE_JSON_ATTRIBUTION = "&copy; Tianditu contributors"
    const val TIANDITU_RASTER_TILE_URL =
      "http://t0.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk=37f0b876e40ce7a0dc91c338f1d6d8d7"

    val MERCATOR_BOUNDS = listOf(-180.0, -85.0, 180.0, 85.0)
  }
}
