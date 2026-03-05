package com.mapbox.maps.testapp.examples.octomap

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.mapbox.geojson.Point
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.tan

class GLRenderer : GLSurfaceView.Renderer {

    private var mesh: Mesh? = null
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Camera state
    private var cameraLat = 0.0
    private var cameraLng = 0.0
    private var cameraBearing = 0.0
    private var cameraPitch = 0.0
    private var cameraZoom = 0.0

    private var origin: Waypoint? = null

    // Shader program
    private var program: Int = 0

    // Handles for shaders
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var colorHandle: Int = 0

    fun setMesh(mesh: Mesh) {
        this.mesh = mesh
    }

    fun setOrigin(origin: Waypoint) {
        this.origin = origin
    }

    fun updateCamera(lat: Double, lng: Double, bearing: Double, pitch: Double, zoom: Double) {
        this.cameraLat = lat
        this.cameraLng = lng
        this.cameraBearing = bearing
        this.cameraPitch = pitch
        this.cameraZoom = zoom
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) // Transparent
        
        val vertexShaderCode = """
            uniform mat4 uMVPMatrix;
            attribute vec4 vPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMVPMatrix * vPosition;
                gl_PointSize = 5.0;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        Matrix.setIdentityM(modelMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio: Float = width.toFloat() / height.toFloat()
        // Mapbox FOV is typically around 30-45 degrees depending on usage, but let's assume 45 for now
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 10000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (mesh == null || origin == null) return

        // Use the program
        GLES20.glUseProgram(program)

        // Get handles
        positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")

        // Calculate View Matrix
        // 1. Convert Camera Center (Lat/Lng) to ENU relative to Origin
        // Note: EnuConverter.toWorld is the inverse. We need toEnu.
        // Let's implement inline logic or update EnuConverter.
        // x = (lng - originLng) * ...
        // y = (lat - originLat) * ...
        
        val originLatRad = Math.toRadians(origin!!.lat)
        val metersPerDegreeLat = 111319.9
        val metersPerDegreeLng = 111319.9 * cos(originLatRad)

        val targetX = (cameraLng - origin!!.lng) * metersPerDegreeLng
        val targetY = (cameraLat - origin!!.lat) * metersPerDegreeLat
        val targetZ = 0.0 // Looking at ground

        // 2. Calculate Camera Position (Eye) based on Zoom, Pitch, Bearing
        // Mapbox Zoom 0 = world size 512 pixels.
        // At latitude 0, 1 pixel = 78271.484 meters / 2^zoom
        // Distance ~ altitude.
        // Approximate altitude calculation:
        val earthCircumference = 40075017.0
        val altitude = earthCircumference * cos(Math.toRadians(cameraPitch)) / 2.0.pow(cameraZoom) / 2.0 // Rough approx

        // Bearing rotation (CW from North) -> GL is CCW from X? 
        // Mapbox Bearing 0 = North (Up, +Y in ENU). 90 = East (+X in ENU).
        // GL View Matrix usually LookAt.
        
        // Let's simplify:
        // We look at (targetX, targetY, targetZ).
        // We are at distance 'altitude' away, rotated by bearing and tilted by pitch.
        
        val pitchRad = Math.toRadians(cameraPitch)
        val bearingRad = Math.toRadians(cameraBearing)
        
        // Offset from target
        // If pitch is 0 (looking down), eye is directly above.
        // If pitch is 90 (looking horizon), eye is on ground? Mapbox pitch 0 is down.
        // Mapbox: Pitch 0 = looking straight down. Pitch 60 = tilted.
        
        // Distance from target on ground plane
        val groundDist = altitude * tan(pitchRad) 
        // Wait, altitude is height above ground.
        // EyeZ = altitude.
        // EyeXY distance from TargetXY = altitude * tan(pitch)?
        // No, usually camera is "at" altitude looking at center.
        // The "distance" from center to eye is dist = altitude / cos(pitch).
        
        // Let's assume 'altitude' is the Z height of the camera.
        val eyeZ = altitude
        
        // The camera is "behind" the target based on bearing.
        // Bearing 0 = North. Camera is South of target.
        // Offset in Y = -groundDist * cos(bearing)
        // Offset in X = -groundDist * sin(bearing)
        // groundDist = altitude * tan(pitch)
        val offsetDist = altitude * tan(pitchRad)
        
        val eyeX = targetX - offsetDist * sin(bearingRad)
        val eyeY = targetY - offsetDist * cos(bearingRad)
        
        // Up vector.
        // If looking down (pitch 0), Up is North (bearing 0 -> +Y).
        // With bearing, Up vector rotates.
        // This is tricky.
        // Let's use Matrix.setLookAtM which handles this if we give correct Eye, Center, Up.
        // Up vector usually points "up" in world (0,0,1) if pitch < 90.
        // But mapbox camera rolls? No, bearing rotates around Z.
        // So Up is (0,0,1)? No, that's world up.
        // If we look down, Up is North.
        
        // Simplified Up vector: (0, 0, 1) usually works for 3D scenes unless looking straight down.
        // When looking straight down, Up is -Z? No.
        // Let's try Up = (0, 0, 1).
        
        Matrix.setLookAtM(viewMatrix, 0, 
            eyeX.toFloat(), eyeY.toFloat(), eyeZ.toFloat(),
            targetX.toFloat(), targetY.toFloat(), targetZ.toFloat(),
            0f, 0f, 1f // Up vector
        )
        
        // Combine matrices
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)

        // Pass to shader
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        
        // Draw points
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, mesh!!.vertexBuffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 0, mesh!!.colorBuffer)
        
        GLES20.glDrawArrays(mesh!!.drawMode, 0, mesh!!.vertexCount)
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
