#if defined(__ANDROID__)
#include <GLES2/gl2.h>
#else
#include <OpenGL/gl3.h>
#endif
#if defined(__ANDROID__)
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#endif
#if defined(__ANDROID__)
#include <jni.h>
#endif
#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>
#if defined(__ANDROID__)
#include <octomap/AbstractOcTree.h>
#include <octomap/ColorOcTree.h>
#include <octomap/OcTree.h>
#endif

#if !defined(__ANDROID__)
struct AAssetManager;
struct AAsset;
static inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
static const int ANDROID_LOG_INFO = 4;
static const int ANDROID_LOG_ERROR = 6;
static const int AASSET_MODE_BUFFER = 0;
#endif

#define LOG_TAG "OctoMapRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace octomap_renderer {

// -------------------------------------------------------------------------
// 1. OpenGL Utilities
// -------------------------------------------------------------------------

static GLuint program = 0;
static GLuint a_pos = 0;
static GLuint u_matrix = 0;
static GLuint u_color = 0;
static GLuint u_point_size = 0;

static const char* vertexShaderSource = 
    "attribute vec3 a_pos;\n"
    "uniform mat4 u_matrix;\n"
    "uniform float u_point_size;\n"
    "void main() {\n"
    "    gl_Position = u_matrix * vec4(a_pos, 1.0);\n"
    "    gl_PointSize = u_point_size;\n"
    "}\n";

static const char* fragmentShaderSource = 
    "precision mediump float;\n"
    "uniform vec4 u_color;\n"
    "void main() {\n"
    "    gl_FragColor = u_color;\n"
    "}\n";

GLuint loadShader(GLenum type, const char* shaderSrc) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &shaderSrc, nullptr);
    glCompileShader(shader);
    // Check compile status...
    return shader;
}

// -------------------------------------------------------------------------
// 2. Data Structures
// -------------------------------------------------------------------------

struct Voxel {
    float x, y, z;
    float size;
    // float r, g, b; // For ColorOcTree
};

std::vector<float> vertexBuffer; // Flattened x, y, z (Mercator for CustomLayer)
std::vector<float> voxelBuffer; // Flattened x, y, z, r, g, b (ENU for GLRenderer)
float boundsMin[3] = {0.0f, 0.0f, 0.0f};
float boundsMax[3] = {0.0f, 0.0f, 0.0f};
bool boundsValid = false;
float treeResolution = 0.0f;

// Origin (WGS84) set by user
double originLat = 31.23;
double originLng = 121.47;
double originAlt = 15.0;

// -------------------------------------------------------------------------
// 3. Coordinate Conversion (WGS84 -> Mercator)
// -------------------------------------------------------------------------

const double PI = 3.14159265358979323846;
const double EARTH_RADIUS = 6378137.0;

double lngToMercatorX(double lng) {
    return (lng + 180.0) / 360.0;
}

double latToMercatorY(double lat) {
    double lat_rad = lat * PI / 180.0;
    return (1.0 - std::log(std::tan(lat_rad) + 1.0 / std::cos(lat_rad)) / PI) / 2.0;
}

// Convert local ENU (meters) to Mercator (0..1)
// Note: This is a simplified scale. Mapbox CustomLayer provides a projection matrix
// that typically expects coordinates in the "CustomLayerRenderParameters" space.
// However, the standard is often Mercator coordinates [0, 1].
// We need to scale meters to Mercator units at the given latitude.
double metersToMercator(double meters, double lat) {
    double scale = 1.0 / (std::cos(lat * PI / 180.0) * 2.0 * PI * EARTH_RADIUS);
    // This is rough. Mapbox's scale is 1 at Equator?
    // Mercator width is 1.0. Earth circumference is ~40075km.
    // 1 Mercator Unit = 40,075,000 meters * cos(lat)
    return meters / (2.0 * PI * EARTH_RADIUS * std::cos(lat * PI / 180.0)); // Very rough approximation
}

// -------------------------------------------------------------------------
// 4. OctoMap Parsing (Simulation / PoC)
// -------------------------------------------------------------------------

bool parseOctoMap(AAssetManager* mgr, const char* filePath) {
#if defined(__ANDROID__)
    AAsset* asset = AAssetManager_open(mgr, filePath, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open %s", filePath);
        return false;
    }
    off_t length = AAsset_getLength(asset);
    const void* buffer = AAsset_getBuffer(asset);
    if (!buffer || length <= 0) {
        LOGE("Empty OctoMap asset");
        AAsset_close(asset);
        return false;
    }
    
    char header[51];
    memset(header, 0, 51);
    memcpy(header, buffer, length > 50 ? 50 : length);
    LOGI("OctoMap Header: %s", header);
    
    LOGI("Opened OctoMap file size: %ld", length);
    
    std::stringstream ss;
    ss.write(static_cast<const char*>(buffer), length);
    octomap::AbstractOcTree* tree = octomap::AbstractOcTree::read(ss);
    if (!tree) {
        LOGE("Failed to parse OctoMap");
        AAsset_close(asset);
        return false;
    }

    vertexBuffer.clear();
    voxelBuffer.clear();
    boundsValid = false;
    
    double originMercX = lngToMercatorX(originLng);
    double originMercY = latToMercatorY(originLat);
    
    double metersPerUnit = 2.0 * PI * EARTH_RADIUS * std::cos(originLat * PI / 180.0);
    double mercatorPerMeter = 1.0 / metersPerUnit;
    
    LOGI("Origin Mercator: %f, %f", originMercX, originMercY);
    LOGI("Mercator per Meter: %e", mercatorPerMeter);

    auto updateBounds = [](float x, float y, float z) {
        if (!boundsValid) {
            boundsMin[0] = x;
            boundsMin[1] = y;
            boundsMin[2] = z;
            boundsMax[0] = x;
            boundsMax[1] = y;
            boundsMax[2] = z;
            boundsValid = true;
            return;
        }
        boundsMin[0] = std::min(boundsMin[0], x);
        boundsMin[1] = std::min(boundsMin[1], y);
        boundsMin[2] = std::min(boundsMin[2], z);
        boundsMax[0] = std::max(boundsMax[0], x);
        boundsMax[1] = std::max(boundsMax[1], y);
        boundsMax[2] = std::max(boundsMax[2], z);
    };

    octomap::ColorOcTree* colorTree = dynamic_cast<octomap::ColorOcTree*>(tree);
    if (colorTree) {
        treeResolution = static_cast<float>(colorTree->getResolution());
        for (auto it = colorTree->begin_leafs(), end = colorTree->end_leafs(); it != end; ++it) {
            if (!colorTree->isNodeOccupied(*it)) continue;
            float enuX = static_cast<float>(it.getX());
            float enuY = static_cast<float>(it.getY());
            float enuZ = static_cast<float>(it.getZ());
            auto color = it->getColor();
            float r = static_cast<float>(color.r) / 255.0f;
            float g = static_cast<float>(color.g) / 255.0f;
            float b = static_cast<float>(color.b) / 255.0f;

            float mercX = static_cast<float>(originMercX + (enuX * mercatorPerMeter));
            float mercY = static_cast<float>(originMercY - (enuY * mercatorPerMeter));
            float mercZ = static_cast<float>(enuZ * mercatorPerMeter);

            vertexBuffer.push_back(mercX);
            vertexBuffer.push_back(mercY);
            vertexBuffer.push_back(mercZ);

            voxelBuffer.push_back(enuX);
            voxelBuffer.push_back(enuY);
            voxelBuffer.push_back(enuZ);
            voxelBuffer.push_back(r);
            voxelBuffer.push_back(g);
            voxelBuffer.push_back(b);

            updateBounds(enuX, enuY, enuZ);
        }
    } else {
        octomap::OcTree* ocTree = dynamic_cast<octomap::OcTree*>(tree);
        if (ocTree) {
            treeResolution = static_cast<float>(ocTree->getResolution());
            for (auto it = ocTree->begin_leafs(), end = ocTree->end_leafs(); it != end; ++it) {
                if (!ocTree->isNodeOccupied(*it)) continue;
                float enuX = static_cast<float>(it.getX());
                float enuY = static_cast<float>(it.getY());
                float enuZ = static_cast<float>(it.getZ());

                float mercX = static_cast<float>(originMercX + (enuX * mercatorPerMeter));
                float mercY = static_cast<float>(originMercY - (enuY * mercatorPerMeter));
                float mercZ = static_cast<float>(enuZ * mercatorPerMeter);

                vertexBuffer.push_back(mercX);
                vertexBuffer.push_back(mercY);
                vertexBuffer.push_back(mercZ);

                voxelBuffer.push_back(enuX);
                voxelBuffer.push_back(enuY);
                voxelBuffer.push_back(enuZ);
                voxelBuffer.push_back(0.5f);
                voxelBuffer.push_back(0.5f);
                voxelBuffer.push_back(0.5f);

                updateBounds(enuX, enuY, enuZ);
            }
        } else {
            LOGE("Unsupported OctoMap type");
            delete tree;
            AAsset_close(asset);
            return false;
        }
    }

    LOGI("Loaded %zu vertices", vertexBuffer.size() / 3);
    delete tree;
    AAsset_close(asset);
    return true;
#else
    (void)mgr;
    (void)filePath;
    return false;
#endif
}

} // namespace octomap_renderer

// -------------------------------------------------------------------------
// 5. JNI Bridge
// -------------------------------------------------------------------------

#if defined(__ANDROID__)
extern "C" {

JNIEXPORT void JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_initialize(JNIEnv* env, jobject obj) {
    using namespace octomap_renderer;
    
    // Asset Loading Logic
    jclass cls = env->GetObjectClass(obj);
    jfieldID assetManagerField = env->GetFieldID(cls, "assetManager", "Landroid/content/res/AssetManager;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("ERROR: Could not find field assetManager");
        assetManagerField = nullptr;
    }
    
    jfieldID filePathField = env->GetFieldID(cls, "filePath", "Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("ERROR: Could not find field filePath");
        filePathField = nullptr;
    }

    if (!assetManagerField || !filePathField) return;
    
    jobject assetManagerObj = env->GetObjectField(obj, assetManagerField);
    jstring filePathStr = (jstring)env->GetObjectField(obj, filePathField);
    const char* filePath = env->GetStringUTFChars(filePathStr, nullptr);
    
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManagerObj);
    
    // 2. Parse File & Build Mesh
    if (!parseOctoMap(mgr, filePath)) {
        env->ReleaseStringUTFChars(filePathStr, filePath);
        return;
    }
    
    env->ReleaseStringUTFChars(filePathStr, filePath);
    
    // 3. Init GL
    program = glCreateProgram();
    GLuint vs = loadShader(GL_VERTEX_SHADER, vertexShaderSource);
    GLuint fs = loadShader(GL_FRAGMENT_SHADER, fragmentShaderSource);
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    
    a_pos = glGetAttribLocation(program, "a_pos");
    u_matrix = glGetUniformLocation(program, "u_matrix");
    u_color = glGetUniformLocation(program, "u_color");
    u_point_size = glGetUniformLocation(program, "u_point_size");
}

JNIEXPORT void JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_render(JNIEnv* env, jobject obj, jobject parameters) {
    using namespace octomap_renderer;
    
    if (program == 0) return;

    // 1. Get Matrix
    jclass cls = env->FindClass("com/mapbox/maps/CustomLayerRenderParameters");
    if (!cls) {
        LOGE("ERROR: Could not find class CustomLayerRenderParameters");
        return;
    }
    jfieldID getProjectionMatrix = env->GetFieldID(cls, "projectionMatrix", "Ljava/util/List;");
    if (!getProjectionMatrix) {
        LOGE("ERROR: Could not find field projectionMatrix");
        return;
    }
    jobject projectionMatrixObj = env->GetObjectField(parameters, getProjectionMatrix);
    if (!projectionMatrixObj) {
        LOGE("ERROR: projectionMatrix is null");
        return;
    }

    // Convert List<Double> to float[16]
    jclass listCls = env->FindClass("java/util/List");
    if (!listCls) return;
    jmethodID getMethod = env->GetMethodID(listCls, "get", "(I)Ljava/lang/Object;");
    jclass doubleCls = env->FindClass("java/lang/Double");
    if (!doubleCls) return;
    jmethodID doubleValueMethod = env->GetMethodID(doubleCls, "doubleValue", "()D");

    float matrix[16];
    for (int i = 0; i < 16; i++) {
        jobject doubleObj = env->CallObjectMethod(projectionMatrixObj, getMethod, i);
        matrix[i] = (float)env->CallDoubleMethod(doubleObj, doubleValueMethod);
        env->DeleteLocalRef(doubleObj);
    }

    // 2. Render
    glUseProgram(program);
    glUniformMatrix4fv(u_matrix, 1, GL_FALSE, matrix);
    glUniform4f(u_color, 1.0f, 0.0f, 0.0f, 1.0f); // Red, solid
    glUniform1f(u_point_size, 10.0f);
    
    glEnableVertexAttribArray(a_pos);
    glVertexAttribPointer(a_pos, 3, GL_FLOAT, GL_FALSE, 0, vertexBuffer.data());
    
    // Draw Points for Voxels
    glDrawArrays(GL_POINTS, 0, vertexBuffer.size() / 3);
    
    // --- Draw Origin Axes (for visual reference) ---
    // X Axis (Red)
    glUniform4f(u_color, 1.0f, 0.0f, 0.0f, 1.0f);
    float xAxis[] = {
        (float)lngToMercatorX(originLng), (float)latToMercatorY(originLat), 0.0f,
        (float)lngToMercatorX(originLng + 0.0005), (float)latToMercatorY(originLat), 0.0f
    };
    glVertexAttribPointer(a_pos, 3, GL_FLOAT, GL_FALSE, 0, xAxis);
    glDrawArrays(GL_LINES, 0, 2);

    // Y Axis (Green)
    glUniform4f(u_color, 0.0f, 1.0f, 0.0f, 1.0f);
    float yAxis[] = {
        (float)lngToMercatorX(originLng), (float)latToMercatorY(originLat), 0.0f,
        (float)lngToMercatorX(originLng), (float)latToMercatorY(originLat + 0.0005), 0.0f
    };
    glVertexAttribPointer(a_pos, 3, GL_FLOAT, GL_FALSE, 0, yAxis);
    glDrawArrays(GL_LINES, 0, 2);

    // Z Axis (Blue)
    glUniform4f(u_color, 0.0f, 0.0f, 1.0f, 1.0f);
    // Use mercator scale for Z
    double metersPerUnit = 2.0 * PI * EARTH_RADIUS * std::cos(originLat * PI / 180.0);
    double mercatorPerMeter = 1.0 / metersPerUnit;
    float zAxis[] = {
        (float)lngToMercatorX(originLng), (float)latToMercatorY(originLat), 0.0f,
        (float)lngToMercatorX(originLng), (float)latToMercatorY(originLat), (float)(50.0 * mercatorPerMeter)
    };
    glVertexAttribPointer(a_pos, 3, GL_FLOAT, GL_FALSE, 0, zAxis);
    glDrawArrays(GL_LINES, 0, 2);
    // ---------------------------------------------
    
    glDisableVertexAttribArray(a_pos);
}

JNIEXPORT void JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_contextLost(JNIEnv* env, jobject obj) {
    using namespace octomap_renderer;
    program = 0;
}

JNIEXPORT void JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_deinitialize(JNIEnv* env, jobject obj) {
    using namespace octomap_renderer;
    if (program) glDeleteProgram(program);
}

// Stubs for extra methods
JNIEXPORT jboolean JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_loadOctoMap(JNIEnv* env, jobject obj, jstring path) {
    using namespace octomap_renderer;
    jclass cls = env->GetObjectClass(obj);
    jfieldID assetManagerField = env->GetFieldID(cls, "assetManager", "Landroid/content/res/AssetManager;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("ERROR: Could not find field assetManager");
        return JNI_FALSE;
    }
    jobject assetManagerObj = env->GetObjectField(obj, assetManagerField);
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManagerObj);
    bool ok = parseOctoMap(mgr, filePath);
    env->ReleaseStringUTFChars(path, filePath);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_getVoxelCount(JNIEnv* env, jobject obj) { return octomap_renderer::voxelBuffer.size() / 6; }

JNIEXPORT jfloatArray JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_getBoundsMin(JNIEnv* env, jobject obj) {
    jfloatArray result = env->NewFloatArray(3);
    float min[3] = {0.0f, 0.0f, 0.0f};
    if (octomap_renderer::boundsValid) {
        min[0] = octomap_renderer::boundsMin[0];
        min[1] = octomap_renderer::boundsMin[1];
        min[2] = octomap_renderer::boundsMin[2];
    }
    env->SetFloatArrayRegion(result, 0, 3, min);
    return result;
}

JNIEXPORT jfloatArray JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_getBoundsMax(JNIEnv* env, jobject obj) {
    jfloatArray result = env->NewFloatArray(3);
    float max[3] = {0.0f, 0.0f, 0.0f};
    if (octomap_renderer::boundsValid) {
        max[0] = octomap_renderer::boundsMax[0];
        max[1] = octomap_renderer::boundsMax[1];
        max[2] = octomap_renderer::boundsMax[2];
    }
    env->SetFloatArrayRegion(result, 0, 3, max);
    return result;
}

JNIEXPORT jfloat JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_getResolution(JNIEnv* env, jobject obj) {
    return octomap_renderer::treeResolution;
}

JNIEXPORT void JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_setOrigin(JNIEnv* env, jobject obj, jdouble lat, jdouble lng, jdouble alt) {
    using namespace octomap_renderer;
    originLat = lat;
    originLng = lng;
    originAlt = alt;
    LOGI("Origin updated to: %f, %f, %f", originLat, originLng, originAlt);
}

JNIEXPORT jfloatArray JNICALL Java_com_mapbox_maps_testapp_examples_octomap_OctoMapLoader_getVoxels(JNIEnv* env, jobject obj) {
    using namespace octomap_renderer;
    jfloatArray result = env->NewFloatArray(voxelBuffer.size());
    env->SetFloatArrayRegion(result, 0, voxelBuffer.size(), voxelBuffer.data());
    return result;
}

}
#endif
