plugins {
  id("com.mapbox.gradle.library")
  id("com.jaredsburrows.license")
  id("maven-publish")
}

mapboxLibrary {
  dokka {
    // Include extra list of files to generate documentation if available
    val extraApiDocs = mutableListOf<String>()

    val coreApiDocFile = rootProject.file("api-doc-list-maps-core.txt")
    if (coreApiDocFile.exists()) {
      extraApiDocs.addAll(coreApiDocFile.readLines())
    }
    val commonApiDocFile = rootProject.file("api-doc-list-maps-common.txt")
    if (commonApiDocFile.exists()) {
      extraApiDocs.addAll(commonApiDocFile.readLines())
    }
    if (extraApiDocs.isNotEmpty()) {
      extraListOfSources = extraApiDocs
      // which might not have docs, so disable report undocumented
      reportUndocumented = false
    }
  }
//  publish {
//    group = "com.mapbox.maps"
//    artifactId = "android"
//    artifactTitle = "Mapbox Maps SDK"
//    artifactDescription = "Mapbox Maps SDK"
//    sdkName = "mobile-maps-android"
//  }
}

android {
  compileSdk = libs.versions.androidCompileSdkVersion.get().toInt()
  namespace = "com.mapbox.maps"
  defaultConfig {
    minSdk = libs.versions.androidMinSdkVersion.get().toInt()
    targetSdk = libs.versions.androidTargetSdkVersion.get().toInt()
    consumerProguardFiles("proguard-rules.pro")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments["clearPackageData"] = "true"

    ndk {
      val abi: String =
        if (System.getenv("ANDROID_ABI") != null) System.getenv("ANDROID_ABI") else ""
      if (abi.isNotBlank() && !project.hasProperty("android.injected.invoked.from.ide")) {
        abiFilters.add(abi)
      }
    }
  }

  buildTypes {
    debug {
      if (project.hasProperty("android.injected.invoked.from.ide")) {
        buildConfigField("boolean", "RUN_FROM_IDE", "true")
      } else {
        buildConfigField("boolean", "RUN_FROM_IDE", "false")
      }
    }
  }

  testOptions {
    unitTests.apply {
      isIncludeAndroidResources = true
    }
    unitTests.all {
      /*
      Allow Mockk to do deep reflection to access nonpublic members for the packages listed.
      https://github.com/mockk/mockk/blob/master/doc/md/jdk16-access-exceptions.md
       */
      it.jvmArgs(
        "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED",
      )
    }
    animationsDisabled = true
    if (!project.hasProperty("android.injected.invoked.from.ide")) {
      execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
    compileOptions {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }
  }
}

dependencies {
  api(libs.mapbox.base)

  implementation(libs.mapbox.annotations)
  api(project(":sdk-base"))//
  api(project(":extension-style"))//作用：Style DSL / Style 管理（非常重要）
  api(project(":extension-localization"))//作用：多语言地图（中英文路名等）     谨慎移除

  api(project(":plugin-gestures"))//作用：缩放 / 拖动 / 旋转
  api(project(":plugin-overlay"))//作用：自定义 View 覆盖
  api(project(":plugin-annotation"))//作用：Marker / Polyline / Polygon
  api(project(":plugin-lifecycle"))//作用：MapView 生命周期管理
  api(project(":plugin-logo"))//作用：Mapbox Logo                          谨慎移除
  api(project(":plugin-viewport"))//作用：视口控制（新 API）                 谨慎移除
  api(project(":plugin-animation"))//作用：Camera / 地图动画                谨慎移除

  api(project(":plugin-compass"))//作用：指南针 UI                        移除
  api(project(":plugin-locationcomponent"))//作用：定位蓝点                移除
  api(project(":plugin-scalebar"))//作用：比例尺 UI                         移除
//  api(project(":plugin-attribution"))//作用：Mapbox 版权角标               移除
//  implementation(project(":module-telemetry")) 日志上报                     必须移除
  compileOnly(libs.asyncInflater)
  api(libs.kotlin)
  api(libs.coroutines)
  implementation(libs.coroutines.android)
  implementation(libs.androidx.coreKtx)
  implementation(libs.androidx.annotations)

  testImplementation(libs.bundles.base.dependenciesTests)
  testImplementation(libs.robolectricEgl)
  testImplementation(libs.asyncInflater)
  testImplementation(libs.androidx.testJUnit)
  testImplementation(libs.coroutinesTest)
  implementation(libs.androidx.appCompat)

  androidTestImplementation(libs.bundles.base.dependenciesAndroidTests)
  androidTestImplementation(libs.androidx.testJUnit)
  androidTestImplementation(libs.androidx.jUnitTestRules)
  androidTestImplementation(libs.androidx.uiAutomator)
  androidTestUtil(libs.androidx.orchestrator)
}

project.apply {
  from("$rootDir/gradle/ktlint.gradle.kts")
  from("$rootDir/gradle/lint.gradle")
  from("$rootDir/gradle/track-public-apis.gradle")
  from("$rootDir/gradle/dependency-updates.gradle")
}

// 添加额外的发布配置
afterEvaluate {
  extensions.configure<PublishingExtension>("publishing") {
    publications {
      create<MavenPublication>("maven") {
        from(components["release"])

        groupId = "com.mapbox.maps"
        artifactId = "android-xag"
        version = "11.16.1-SNAPSHOT1"

        pom {
          name.set("Mapbox Maps SDK for Android")
          description.set("Mapbox Maps SDK for Android")
          url.set("https://github.com/mapbox/mapbox-maps-android")

          licenses {
            license {
              name.set("Mapbox Terms of Service")
              url.set("https://www.mapbox.com/legal/tos/")
              distribution.set("repo")
            }
          }

          developers {
            developer {
              id.set("mapbox")
              name.set("Mapbox")
            }
          }

          scm {
            connection.set("scm:git@github.com:mapbox/mapbox-maps-android.git")
            developerConnection.set("scm:git@github.com:mapbox/mapbox-maps-android.git")
            url.set("https://github.com/mapbox/mapbox-maps-android")
          }
        }
      }
    }

    repositories {
      maven {
        name = "CNBMavenRepository"
        // 从 gradle.properties 获取仓库地址
        url = uri(project.findProperty("cnbArtifactsHciMavenRepoUrl") ?: "")
        credentials {
          username = project.findProperty("cnbArtifactsGradleName")?.toString() ?: ""
          password = project.findProperty("cnbArtifactsGradlePassword")?.toString() ?: ""
        }
      }
    }
  }
}

// 创建一个简单的发布任务
tasks.register("publishToMavenLocalCustom") {
  group = "publishing"
  description = "Publishes the Android AAR and sources to Maven local with custom coordinates"

  doLast {
    println("This is a placeholder task for custom publishing")
    println("To publish, use: ./gradlew :maps-sdk:publishReleasePublicationToMavenLocal")
  }
}