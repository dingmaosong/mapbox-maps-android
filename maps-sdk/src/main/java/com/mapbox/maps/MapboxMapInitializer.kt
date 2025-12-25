package com.mapbox.maps

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
    MapboxMapsOptions.baseUrl = "https://baidu.com"
    MapboxMapsOptions.assetPath = "asset://"
  }
}