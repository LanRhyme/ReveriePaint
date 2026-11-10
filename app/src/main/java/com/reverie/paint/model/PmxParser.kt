/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 纯 Kotlin 高性能 MMD (.pmx) 2.0 / 2.1 二进制模型解析器
 */
object PmxParser {

    private const val PMX_MAGIC = 0x20584D50 // "PMX " in little-endian

    fun parse(stream: InputStream): SkinnedMesh3D {
        val bytes = stream.readBytes()
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): SkinnedMesh3D {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. 验证 PMX 魔数
        val magic = buf.int
        require(magic == PMX_MAGIC) { "Invalid PMX magic: 0x${Integer.toHexString(magic)}" }
        val version = buf.float // 2.0 or 2.1
        val globalsCount = buf.get().toInt() and 0xFF
        val globals = ByteArray(globalsCount)
        buf.get(globals)

        val isUtf8 = globals[0].toInt() == 1
        val charset = if (isUtf8) Charsets.UTF_8 else Charsets.UTF_16LE
        val vertexIdxSize = globals[2].toInt()
        val boneIdxSize = globals[5].toInt()

        fun readPmxString(): String {
            val len = buf.int
            if (len <= 0) return ""
            val strBytes = ByteArray(len)
            buf.get(strBytes)
            return String(strBytes, charset)
        }

        fun readIndex(size: Int): Int {
            return when (size) {
                1 -> buf.get().toInt()
                2 -> buf.short.toInt()
                4 -> buf.int
                else -> buf.get().toInt()
            }
        }

        fun readVertexIndex(size: Int): Int {
            return when (size) {
                1 -> buf.get().toInt() and 0xFF
                2 -> buf.short.toInt() and 0xFFFF
                4 -> buf.int
                else -> buf.get().toInt() and 0xFF
            }
        }

        // 跳过模型名称与注释
        readPmxString() // Name (JP)
        readPmxString() // Name (EN)
        readPmxString() // Comment (JP)
        readPmxString() // Comment (EN)

        // 2. 解析顶点数据 (Vertices)
        val vertexCount = buf.int
        if (vertexCount > MAX_3D_VERTICES) {
            throw MeshTooLargeException("模型顶点数超过上限 ($MAX_3D_VERTICES)")
        }
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val jointIndices = IntArray(vertexCount * 4) { 0 }
        val jointWeights = FloatArray(vertexCount * 4) { 0f }

        for (v in 0 until vertexCount) {
            // Position (X, Y, Z) - MMD 默认左手系，Z 向内，转换为右手系
            positions[v * 3] = buf.float
            positions[v * 3 + 1] = buf.float
            positions[v * 3 + 2] = -buf.float // Invert Z for OpenGL right-handed coordinates

            // Normal
            normals[v * 3] = buf.float
            normals[v * 3 + 1] = buf.float
            normals[v * 3 + 2] = -buf.float

            // UV
            buf.float; buf.float

            // Weight type
            val weightType = buf.get().toInt() and 0xFF
            when (weightType) {
                0 -> { // BDEF1
                    val b0 = readIndex(boneIdxSize)
                    jointIndices[v * 4] = b0.coerceAtLeast(0)
                    jointWeights[v * 4] = 1.0f
                }
                1 -> { // BDEF2
                    val b0 = readIndex(boneIdxSize)
                    val b1 = readIndex(boneIdxSize)
                    val w0 = buf.float
                    jointIndices[v * 4] = b0.coerceAtLeast(0)
                    jointIndices[v * 4 + 1] = b1.coerceAtLeast(0)
                    jointWeights[v * 4] = w0
                    jointWeights[v * 4 + 1] = 1.0f - w0
                }
                2 -> { // BDEF4
                    val b0 = readIndex(boneIdxSize); val b1 = readIndex(boneIdxSize)
                    val b2 = readIndex(boneIdxSize); val b3 = readIndex(boneIdxSize)
                    val w0 = buf.float; val w1 = buf.float; val w2 = buf.float; val w3 = buf.float
                    jointIndices[v * 4] = b0.coerceAtLeast(0); jointIndices[v * 4 + 1] = b1.coerceAtLeast(0)
                    jointIndices[v * 4 + 2] = b2.coerceAtLeast(0); jointIndices[v * 4 + 3] = b3.coerceAtLeast(0)
                    jointWeights[v * 4] = w0; jointWeights[v * 4 + 1] = w1
                    jointWeights[v * 4 + 2] = w2; jointWeights[v * 4 + 3] = w3
                }
                3 -> { // SDEF
                    val b0 = readIndex(boneIdxSize); val b1 = readIndex(boneIdxSize)
                    val w0 = buf.float
                    jointIndices[v * 4] = b0.coerceAtLeast(0); jointIndices[v * 4 + 1] = b1.coerceAtLeast(0)
                    jointWeights[v * 4] = w0; jointWeights[v * 4 + 1] = 1.0f - w0
                    // Skip C, R0, R1 (9 floats)
                    for (k in 0..8) buf.float
                }
                4 -> { // QDEF
                    val b0 = readIndex(boneIdxSize); val b1 = readIndex(boneIdxSize)
                    val b2 = readIndex(boneIdxSize); val b3 = readIndex(boneIdxSize)
                    val w0 = buf.float; val w1 = buf.float; val w2 = buf.float; val w3 = buf.float
                    jointIndices[v * 4] = b0.coerceAtLeast(0); jointIndices[v * 4 + 1] = b1.coerceAtLeast(0)
                    jointIndices[v * 4 + 2] = b2.coerceAtLeast(0); jointIndices[v * 4 + 3] = b3.coerceAtLeast(0)
                    jointWeights[v * 4] = w0; jointWeights[v * 4 + 1] = w1
                    jointWeights[v * 4 + 2] = w2; jointWeights[v * 4 + 3] = w3
                }
            }

            // Edge scale
            buf.float
        }

        // 3. 解析三角面索引 (Surfaces)
        val surfaceCount = buf.int
        if (surfaceCount / 3 > MAX_3D_TRIANGLES) {
            throw MeshTooLargeException("模型三角面数超过上限 ($MAX_3D_TRIANGLES)")
        }
        val indices = IntArray(surfaceCount)
        for (i in 0 until surfaceCount step 3) {
            // MMD 顺时针三角形，转换顶点顺序反转为逆时针
            val i0 = readVertexIndex(vertexIdxSize)
            val i1 = readVertexIndex(vertexIdxSize)
            val i2 = readVertexIndex(vertexIdxSize)
            indices[i] = if (i0 in 0 until vertexCount) i0 else 0
            indices[i + 1] = if (i2 in 0 until vertexCount) i2 else 0
            indices[i + 2] = if (i1 in 0 until vertexCount) i1 else 0
        }

        // 4. 跳过材质与纹理
        val textureCount = buf.int
        for (t in 0 until textureCount) readPmxString()

        val materialCount = buf.int
        val textureIdxSize = globals[3].toInt()
        for (m in 0 until materialCount) {
            readPmxString(); readPmxString()
            // Diffuse (4), Specular (3), Specular power (1), Ambient (3)
            for (k in 0..10) buf.float
            buf.get() // drawing flags
            for (k in 0..3) buf.float // Edge color
            buf.float // Edge size
            readIndex(textureIdxSize) // Texture index
            readIndex(textureIdxSize) // Sphere texture index
            buf.get() // Sphere mode
            val isSharedToon = buf.get().toInt() == 1
            if (isSharedToon) buf.get() else readIndex(textureIdxSize)
            readPmxString() // Memo
            buf.int // Surface count for this material
        }

        // 5. 解析骨骼 (Bones)
        val boneCount = buf.int
        val jointList = ArrayList<Joint3D>(boneCount)
        val humanoidMap = HashMap<String, Int>()

        for (b in 0 until boneCount) {
            val nameJp = readPmxString()
            val nameEn = readPmxString()
            val bx = buf.float
            val by = buf.float
            val bz = -buf.float // Invert Z

            val parentIdx = readIndex(boneIdxSize)
            val level = buf.int
            val flags = buf.short.toInt() and 0xFFFF

            // Skip optional bone extensions depending on flags
            if ((flags and 0x0001) != 0) { // Connect to bone
                readIndex(boneIdxSize)
            } else { // Connect to offset
                buf.float; buf.float; buf.float
            }
            if ((flags and (0x0100 or 0x0200)) != 0) { // Inherit rotation or translation
                readIndex(boneIdxSize)
                buf.float
            }
            if ((flags and 0x0400) != 0) { // Fixed axis
                buf.float; buf.float; buf.float
            }
            if ((flags and 0x0800) != 0) { // Local axis
                for (k in 0..5) buf.float
            }
            if ((flags and 0x2000) != 0) { // External parent
                buf.int
            }
            if ((flags and 0x0020) != 0) { // IK
                readIndex(boneIdxSize)
                buf.int; buf.float
                val linkCount = buf.int
                for (lk in 0 until linkCount) {
                    readIndex(boneIdxSize)
                    val hasLimit = buf.get().toInt() == 1
                    if (hasLimit) {
                        for (k in 0..5) buf.float
                    }
                }
            }

            // 计算局部坐标 (相对于父骨骼的偏移)
            val parentJoint = if (parentIdx in 0 until b) jointList[parentIdx] else null
            val px = parentJoint?.localPos?.get(0) ?: 0f
            val py = parentJoint?.localPos?.get(1) ?: 0f
            val pz = parentJoint?.localPos?.get(2) ?: 0f

            val localMat = Math3D.translate(Math3D.identity(), bx - px, by - py, bz - pz)
            val invBindMat = Math3D.translate(Math3D.identity(), -bx, -by, -bz)

            jointList.add(
                Joint3D(
                    index = b,
                    name = if (nameEn.isNotEmpty()) nameEn else nameJp,
                    parentIndex = parentIdx,
                    localMatrix = localMat,
                    inverseBindMatrix = invBindMat,
                    localPos = floatArrayOf(bx, by, bz),
                )
            )

            // 识别 MMD 经典日文与英文骨骼名称
            val lower = (nameJp + "_" + nameEn).lowercase()
            when {
                lower.contains("頭") || lower.contains("head") -> humanoidMap["head"] = b
                lower.contains("首") || lower.contains("neck") -> humanoidMap["neck"] = b
                lower.contains("上半身2") || lower.contains("upperchest") -> humanoidMap["upperChest"] = b
                lower.contains("上半身") || lower.contains("chest") -> humanoidMap["chest"] = b
                lower.contains("下半身") || lower.contains("spine") || lower.contains("hips") -> humanoidMap["hips"] = b
                lower.contains("肩") && (lower.contains("左") || lower.contains("l")) -> humanoidMap["leftShoulder"] = b
                lower.contains("肩") && (lower.contains("右") || lower.contains("r")) -> humanoidMap["rightShoulder"] = b
                lower.contains("腕") && (lower.contains("左") || lower.contains("l")) -> humanoidMap["leftUpperArm"] = b
                lower.contains("腕") && (lower.contains("右") || lower.contains("r")) -> humanoidMap["rightUpperArm"] = b
                lower.contains("ひじ") || lower.contains("肘") && (lower.contains("左") || lower.contains("l")) -> humanoidMap["leftLowerArm"] = b
                lower.contains("ひじ") || lower.contains("肘") && (lower.contains("右") || lower.contains("r")) -> humanoidMap["rightLowerArm"] = b
                lower.contains("手首") && (lower.contains("左") || lower.contains("l")) -> humanoidMap["leftHand"] = b
                lower.contains("手首") && (lower.contains("右") || lower.contains("r")) -> humanoidMap["rightHand"] = b
                lower.contains("足") && !lower.contains("先") && (lower.contains("左") || lower.contains("l")) -> humanoidMap["leftUpperLeg"] = b
                lower.contains("足") && !lower.contains("先") && (lower.contains("右") || lower.contains("r")) -> humanoidMap["rightUpperLeg"] = b
                lower.contains("ひざ") || lower.contains("膝") && (lower.contains("左") || lower.contains("l")) -> humanoidMap["leftLowerLeg"] = b
                lower.contains("ひざ") || lower.contains("膝") && (lower.contains("右") || lower.contains("r")) -> humanoidMap["rightLowerLeg"] = b
                lower.contains("足首") && (lower.contains("左") || lower.contains("l")) -> humanoidMap["leftFoot"] = b
                lower.contains("足首") && (lower.contains("右") || lower.contains("r")) -> humanoidMap["rightFoot"] = b
            }
        }

        val wireframeIndices = Mesh3D.extractWireframeIndices(indices)
        val edgeTriangles = Mesh3D.computeEdgeTriangles(indices, wireframeIndices)
        val baseMesh = Mesh3D(positions, normals, indices, wireframeIndices, edgeTriangles)
        val skeleton = Skeleton3D(jointList, humanoidMap)

        return SkinnedMesh3D(
            baseMesh = baseMesh,
            skeleton = skeleton,
            jointIndices = jointIndices,
            jointWeights = jointWeights,
        )
    }
}
