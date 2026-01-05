package com.mapbox.maps.testapp.examples

import android.graphics.BitmapFactory
import android.graphics.Color
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
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.coalesce
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.eq
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.get
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.literal
import com.mapbox.maps.extension.style.layers.generated.backgroundLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.fillExtrusionLayer
import com.mapbox.maps.extension.style.layers.generated.hillshadeLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.rasterLayer
import com.mapbox.maps.extension.style.layers.properties.generated.FillExtrusionBaseAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.FillExtrusionHeightAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineElevationReference
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.ProjectionName
import com.mapbox.maps.extension.style.light.generated.ambientLight
import com.mapbox.maps.extension.style.light.generated.directionalLight
import com.mapbox.maps.extension.style.light.setLight
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
/**
 * DSM（栅格影像瓦片）+ DEM（高程瓦片）联合展示示例。
 *
 * - DSM：作为 [CustomRasterSource] 的影像纹理贴到地形表面（本身不提供高程）。
 * - DEM：通过 [rasterDemSource] + [setTerrain] 提供地形起伏与 3D 视角基础。
 * - obstacle.geojson：用于渲染障碍物（面/线/点），面用挤出，线用“抬高线”，点用圆点占位。
 */
class DsmTileSourceActivity : AppCompatActivity(), OnMapClickListener {

  private lateinit var mapboxMap: MapboxMap
  private lateinit var dsmRasterSource: com.mapbox.maps.extension.style.sources.CustomRasterSource
  private val dsmRootDirCandidates: List<File> by lazy {
    listOf(
      File(filesDir, "dsm/xag_dsm_webp_tiles"),
    )
  }
  private val resolvedDsmRootDir: File? by lazy {
    // 优先选择包含 boundary.json 的目录（可用于自动定位视野）。
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
          // Mapbox 官方 DEM tileset：
          // - 只提供高程（解码为地形网格），不提供颜色纹理
          // - url 必须是 raster-dem 类型 tileset
          url(TERRAIN_URL_TILE_RESOURCE)
          // DEM tileSize 常用 512（与官方 tileset 推荐一致），可减少采样噪声/块状感。
          tileSize(512)
        }
      )
      style.setTerrain(
        terrain(TERRAIN_SOURCE_ID) {
          // 地形夸张倍数：越大起伏越明显，也会放大 DEM 噪声与误差。
          exaggeration(TERRAIN_EXAGGERATION)
        }
      )
      style.setAtmosphere(
        atmosphere {
          // 大气颜色：用于远处雾化与天空过渡（抬头时也会参与背景渲染）。
          color(ATMOSPHERE_COLOR)
          highColor(ATMOSPHERE_HIGH_COLOR)
          // spaceColor：地平线以上更高区域的底色；设置为深色可避免“仰望纯白”。
          spaceColor(ATMOSPHERE_SPACE_COLOR)
          // starIntensity：星空强度，数值越大星点越明显（会随 zoom 表现）。
          starIntensity(ATMOSPHERE_STAR_INTENSITY)
        }
      )
      // 使用地球投影：倾斜/抬头时视觉更真实，但对部分样式/设备开销更高。
      style.setProjection(projection(ProjectionName.GLOBE))
      style.setLight(
        ambientLight {
          color(Color.WHITE)
          // 环境光强度：越大整体越亮，阴影对比越弱。
          intensity(0.65)
        },
        directionalLight {
          color(Color.WHITE)
          // 方向光强度：越大方向性越强，立体感越明显。
          intensity(0.7)
          // castShadows：开启阴影增强 3D 观感；关闭可提升性能。
          castShadows(true)
          // direction：[azimuth, polar]，控制光照方向；调这两个值可改变“阳光方向”。
          direction(listOf(-10.0, 60.0))
        }
      )

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

      val background = backgroundLayer(BACKGROUND_LAYER_ID) {
        // BackgroundLayer 是整个 Style 的“底色兜底”。
        // 当抬头导致天空/雾化背景区域显露时，深色底能避免纯白闪屏/空白观感。
        backgroundColor(BACKGROUND_COLOR)
        backgroundOpacity(1.0)
      }
      if (!style.styleLayerExists(BACKGROUND_LAYER_ID)) {
        style.addLayerBelow(background, TIANDITU_LAYER_ID)
      }

      val hillshade = hillshadeLayer(HILLSHADE_LAYER_ID, TERRAIN_SOURCE_ID) {
        // hillshade：基于 DEM 的明暗阴影，辅助增强地形起伏观感。
        hillshadeExaggeration(HILLSHADE_EXAGGERATION)
        hillshadeShadowColor(HILLSHADE_SHADOW_COLOR)
        hillshadeHighlightColor(HILLSHADE_HIGHLIGHT_COLOR)
        hillshadeAccentColor(HILLSHADE_ACCENT_COLOR)
      }
      style.addLayerAbove(hillshade, TIANDITU_LAYER_ID)

      style.addSource(dsmRasterSource)

      val dsmLayer = rasterLayer(DSM_LAYER_ID, DSM_SOURCE_ID) {
        // DSM 影像透明度：越高越“贴图化”，越低越能看到底图/阴影。
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
          // obstacle.geojson：可以同时包含 Polygon / LineString / Point。
          data(obstacleGeoJsonText)
        }
      )
    }

    val obstacleExtrusionLayer = fillExtrusionLayer(OBSTACLE_FILL_LAYER_ID, OBSTACLE_SOURCE_ID) {
      // 面（Polygon）挤出：用于“有体积”的障碍物（例如建筑/围栏区域等）。
      fillExtrusionColor(OBSTACLE_COLOR)
      fillExtrusionOpacity(OBSTACLE_FILL_OPACITY)
      // 挤出高度，单位米（取自 GeoJSON properties.height）。
      fillExtrusionHeight(get(OBSTACLE_HEIGHT_PROPERTY_NAME))
      // 挤出基底高度，通常设 0；需要“悬空/分层”时可调整。
      fillExtrusionBase(0.0)
      // baseAlignment/heightAlignment = TERRAIN：
      // - 使挤出几何贴合地形（否则在起伏地形上会出现“漂浮/插地”）。
      fillExtrusionBaseAlignment(FillExtrusionBaseAlignment.TERRAIN)
      fillExtrusionHeightAlignment(FillExtrusionHeightAlignment.TERRAIN)
      // 垂直渐变：让侧面有渐变，立体感更强。
      fillExtrusionVerticalGradient(true)
      filter(
        eq(
          literal("\$type"),
          literal("Polygon")
        )
      )
    }

    if (!style.styleLayerExists(OBSTACLE_FILL_LAYER_ID)) {
      style.addLayerAbove(obstacleExtrusionLayer, DSM_LAYER_ID)
    }

    val obstacleLineLayer = lineLayer(OBSTACLE_LINE_LAYER_ID, OBSTACLE_SOURCE_ID) {
      // 面的轮廓线：只描 Polygon 边界（避免 LineString 也走到这里）。
      lineColor(OBSTACLE_OUTLINE_COLOR)
      lineOpacity(OBSTACLE_LINE_OPACITY)
      lineWidth(OBSTACLE_LINE_WIDTH)
      lineJoin(LineJoin.ROUND)
      lineCap(LineCap.ROUND)
      filter(
        eq(
          literal("\$type"),
          literal("Polygon")
        )
      )
    }

    if (!style.styleLayerExists(OBSTACLE_LINE_LAYER_ID)) {
      style.addLayerAbove(obstacleLineLayer, OBSTACLE_FILL_LAYER_ID)
    }

    val obstacleWireLayer = lineLayer(OBSTACLE_WIRE_LAYER_ID, OBSTACLE_SOURCE_ID) {
      // 电线（LineString）不能“挤出”，需要用 LineLayer 的高程能力。
      // 这里用“贴地 + Z 偏移”的方式把线抬离地面。
      lineColor(OBSTACLE_OUTLINE_COLOR)
      lineOpacity(OBSTACLE_LINE_OPACITY)
      lineWidth(OBSTACLE_WIRE_LINE_WIDTH)
      lineJoin(LineJoin.ROUND)
      lineCap(LineCap.ROUND)
      // 以地面为基准：地形/地面高度 + lineZOffset。
      lineElevationReference(LineElevationReference.GROUND)
      lineZOffset(
        // Z 偏移（米）：优先使用 properties.height；没有则用默认高度，避免“贴地走线”。
        coalesce(
          get(OBSTACLE_HEIGHT_PROPERTY_NAME),
          literal(DEFAULT_WIRE_HEIGHT_METERS)
        )
      )
      filter(
        eq(
          literal("\$type"),
          literal("LineString")
        )
      )
    }

    if (!style.styleLayerExists(OBSTACLE_WIRE_LAYER_ID)) {
      style.addLayerAbove(obstacleWireLayer, OBSTACLE_FILL_LAYER_ID)
    }

    val obstaclePoleLayer = circleLayer(OBSTACLE_POLE_LAYER_ID, OBSTACLE_SOURCE_ID) {
      // 电线杆（Point）占位：Point 本身不支持挤出；如需真实 3D 杆体可改为 modelLayer，
      // 或把杆体建模为 Polygon 再走挤出逻辑。
      circleColor(OBSTACLE_COLOR)
      circleOpacity(OBSTACLE_LINE_OPACITY)
      circleRadius(OBSTACLE_POLE_RADIUS)
      filter(
        eq(
          literal("\$type"),
          literal("Point")
        )
      )
    }

    if (!style.styleLayerExists(OBSTACLE_POLE_LAYER_ID)) {
      style.addLayerAbove(obstaclePoleLayer, OBSTACLE_WIRE_LAYER_ID)
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
        // 便于排查：提示把离线瓦片目录放到 app 私有目录下的固定位置。
        "未找到DSM目录/资源，请把 xag_dsm_webp_tiles 放到以下任一路径，或确保被打包进assets：\n$candidates"
      }
    }
    Log.e(TAG, message)
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
  }

  private companion object {
    /** 日志 tag。 */
    const val TAG = "DsmTileSourceActivity"

    /** 底图样式中默认文字图层 id（存在则用于插层，避免盖住文字）。 */
    const val TEXT_LAYER_ID = "text-default"

    /** Style 的背景兜底图层 id。 */
    private const val BACKGROUND_LAYER_ID = "background-layer"
    /** 背景兜底颜色（深色可避免抬头看到纯白）。 */
    private const val BACKGROUND_COLOR = "#0a1929"

    /** 天地图影像瓦片 source id。 */
    const val TIANDITU_SOURCE_ID = "tianditu-source"
    /** 天地图影像瓦片 layer id。 */
    const val TIANDITU_LAYER_ID = "tianditu-layer"
    /** 天地图瓦片像素尺寸（与服务端实际返回一致，一般为 256）。 */
    const val TIANDITU_TILE_SIZE_PIXELS = 256L

    /** DSM 自定义栅格 source id（本地 webp 瓦片）。 */
    const val DSM_SOURCE_ID = "dsm-source"
    /** DSM 栅格图层 id。 */
    const val DSM_LAYER_ID = "dsm-layer"
    /** DSM 瓦片像素尺寸（与本地瓦片输出一致，一般为 256）。 */
    const val DSM_TILE_SIZE_PIXELS = 256

    /** 边界文件名：用于自动定位初始视野。 */
    const val BOUNDARY_FILE_NAME = "boundary.json"
    /** 障碍物文件名：包含电线杆/电线/面状障碍物等要素。 */
    const val OBSTACLE_FILE_NAME = "obstacle.geojson"

    /** TileJSON 版本号（按规范固定即可）。 */
    const val TILE_JSON_VERSION = "2.0.0"
    /** 天地图 TileSet 最小缩放级别。 */
    const val TILE_JSON_MIN_ZOOM = 0
    /** 天地图 TileSet 最大缩放级别。 */
    const val TILE_JSON_MAX_ZOOM = 18

    /** 没有 boundary.json 时的兜底初始 zoom。 */
    const val DEFAULT_ZOOM = 16.0
    /** 初始 pitch（倾斜角，越大越“3D 视角”）。 */
    const val DEFAULT_PITCH = 60.0
    /** 初始 bearing（朝向角）。 */
    const val DEFAULT_BEARING = 0.0

    /** 地形 DEM source id。 */
    private const val TERRAIN_SOURCE_ID = "terrain-source"
    /** 预留 sky layer id（当前示例使用 atmosphere，未启用 skyLayer）。 */
    private const val SKY_LAYER_ID = "sky"
    /** Mapbox 官方 DEM tileset：用于 terrain 高程采样（不是影像底图）。 */
    private const val TERRAIN_URL_TILE_RESOURCE = "mapbox://mapbox.mapbox-terrain-dem-v1"
    /** 地形夸张倍数：为了更明显的 3D 起伏效果；1.0 表示不夸张。 */
    private const val TERRAIN_EXAGGERATION = 1.0

    /** Atmosphere：地平线附近雾化/天空过渡颜色。 */
    private const val ATMOSPHERE_COLOR = "#7aa6ff"
    /** Atmosphere：地平线以上更高区域的颜色。 */
    private const val ATMOSPHERE_HIGH_COLOR = "#245cdf"
    /** Atmosphere：更高处（“太空背景”）的颜色；深色可避免抬头发白。 */
    private const val ATMOSPHERE_SPACE_COLOR = "#010b19"
    /** Atmosphere：星点强度（0~1 常用）。 */
    private const val ATMOSPHERE_STAR_INTENSITY = 0.35

    /** assets 或 filesDir 下 DSM 数据目录名。 */
    private const val DSM_ASSET_ROOT = "dsm/xag_dsm_webp_tiles"

    /** hillshade 图层 id（基于 DEM 生成阴影）。 */
    private const val HILLSHADE_LAYER_ID = "hillshade-layer"
    /** hillshade 强度：只影响阴影明暗，不改变真实高程。 */
    private const val HILLSHADE_EXAGGERATION = 0.8
    /** hillshade 阴影颜色。 */
    private const val HILLSHADE_SHADOW_COLOR = 0x99000000.toInt()
    /** hillshade 高光颜色。 */
    private const val HILLSHADE_HIGHLIGHT_COLOR = 0x99FFFFFF.toInt()
    /** hillshade 强调色（地形细节）。 */
    private const val HILLSHADE_ACCENT_COLOR = 0x66000000.toInt()

    /** DSM 影像透明度：平衡 DSM 与底图/阴影的可见性。 */
    private const val DSM_OPACITY = 0.55

    /** 障碍物 GeoJSON source id。 */
    private const val OBSTACLE_SOURCE_ID = "obstacle-source"
    /** 面状障碍物挤出图层 id（只过滤 Polygon）。 */
    private const val OBSTACLE_FILL_LAYER_ID = "obstacle-fill-layer"
    /** 面状障碍物轮廓线图层 id（只过滤 Polygon）。 */
    private const val OBSTACLE_LINE_LAYER_ID = "obstacle-line-layer"
    /** 电线图层 id（只过滤 LineString）。 */
    private const val OBSTACLE_WIRE_LAYER_ID = "obstacle-wire-layer"
    /** 电线杆点位图层 id（只过滤 Point，用圆点占位）。 */
    private const val OBSTACLE_POLE_LAYER_ID = "obstacle-pole-layer"
    /** GeoJSON properties 中的高度字段名（单位：米）。 */
    private const val OBSTACLE_HEIGHT_PROPERTY_NAME = "height"
    /** 障碍物主色（面挤出、点位等）。 */
    private const val OBSTACLE_COLOR = "#ff0000"
    /** 障碍物描边色（轮廓线、电线等）。 */
    private const val OBSTACLE_OUTLINE_COLOR = "#ff0000"
    /** 面挤出透明度。 */
    private const val OBSTACLE_FILL_OPACITY = 0.35
    /** 线透明度（轮廓线/电线/点位）。 */
    private const val OBSTACLE_LINE_OPACITY = 0.9
    /** 面轮廓线宽度。 */
    private const val OBSTACLE_LINE_WIDTH = 2.0
    /** 电线线宽度。 */
    private const val OBSTACLE_WIRE_LINE_WIDTH = 3.0
    /** 电线杆点位半径。 */
    private const val OBSTACLE_POLE_RADIUS = 10.0
    /** 当 LineString 没有提供 height 时的兜底高度（米），避免电线“贴地”。 */
    private const val DEFAULT_WIRE_HEIGHT_METERS = 24.0

    /** 缺省定位点纬度（WGS84）。 */
    const val GUANGZHOU_LAT_WGS84 = 23.1788
    /** 缺省定位点经度（WGS84）。 */
    const val GUANGZHOU_LNG_WGS84 = 113.4101

    /** 天地图 TileSet 名称。 */
    const val TIANDITU_TILE_JSON_NAME = "Tianditu"
    /** 天地图 TileSet 描述。 */
    const val TIANDITU_TILE_JSON_DESCRIPTION = "China National Geomatics Center Tianditu"
    /** 天地图 attribution（按服务条款展示）。 */
    const val TIANDITU_TILE_JSON_ATTRIBUTION = "&copy; Tianditu contributors"
    /** 天地图影像瓦片 URL 模板。 */
    const val TIANDITU_RASTER_TILE_URL =
      "http://t0.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk=37f0b876e40ce7a0dc91c338f1d6d8d7"

    /** 天地图使用的 WebMercator 有效经纬范围（TileJSON bounds）。 */
    val MERCATOR_BOUNDS = listOf(-180.0, -85.0, 180.0, 85.0)
  }
}
