package com.mapbox.maps

import com.mapbox.common.TelemetryUtils
import com.mapbox.common.MapboxOptions

/**
 * Mapbox SDK 初始化器，用于设置全局配置
 */
object MapboxMapInitializer {
  @Volatile
  private var isInitialized = false

  /**
   * 初始化 Mapbox SDK 全局配置
   * 这个方法应该在应用启动时调用一次
   */
  fun initialize() {
    if (isInitialized) {
      return
    }
    // Set base URL to an invalid address to block all Mapbox network traffic
//    MapboxMapsOptions.baseUrl = "http://0.0.0.0"
    MapboxMapsOptions.baseUrl = "http://127.0.0.1"
    MapboxMapsOptions.assetPath = "asset://"
    MapboxMapsOptions.tileStoreUsageMode = TileStoreUsageMode.DISABLED
    // Attempt to set global MapboxOptions if available
    MapboxOptions.accessToken = ""
    TelemetryUtils.setEventsCollectionState(false) {}
    isInitialized = true
  }
}