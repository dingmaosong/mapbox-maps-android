package com.mapbox.maps.testapp.examples.octomap

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class Mesh(
    val vertexBuffer: FloatBuffer,
    val colorBuffer: FloatBuffer,
    val vertexCount: Int,
    val drawMode: Int
)

object VoxelMeshBuilder {
    fun build(voxels: List<Voxel>): Mesh {
        // For PoC, we use GL_POINTS to represent voxels.
        // This is efficient for large datasets.
        // Each point represents the center of a voxel.
        
        val vertexData = FloatArray(voxels.size * 3)
        val colorData = FloatArray(voxels.size * 3)
        voxels.forEachIndexed { i, voxel ->
            vertexData[i * 3] = voxel.x
            vertexData[i * 3 + 1] = voxel.y
            vertexData[i * 3 + 2] = voxel.z
            colorData[i * 3] = voxel.r
            colorData[i * 3 + 1] = voxel.g
            colorData[i * 3 + 2] = voxel.b
        }

        val buffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
        buffer.position(0)

        val colorBuffer = ByteBuffer.allocateDirect(colorData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(colorData)
        colorBuffer.position(0)

        return Mesh(buffer, colorBuffer, voxels.size, GLES20.GL_POINTS)
    }
}
