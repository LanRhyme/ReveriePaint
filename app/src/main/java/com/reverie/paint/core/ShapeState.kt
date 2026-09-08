/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.reverie.paint.model.*

/**
 * 形状工具的交互式编辑状态容器
 */
class ShapeState {
    /** 是否正处于交互手柄编辑模式 */
    var active by mutableStateOf(false)

    /** 当前编辑的形状类型 */
    var type by mutableStateOf(ShapeType.RECT)

    /** 样式填充模式: 仅描边 / 仅填充 / 描边与填充 */
    var fillMode by mutableStateOf(ShapeFillMode.STROKE)

    /** 独立描边粗细 (px) */
    var strokeWidth by mutableFloatStateOf(4f)

    /** 圆角矩形圆角半径 (px) */
    var cornerRadius by mutableFloatStateOf(16f)

    /** 正多边形边数 (3..16) */
    var polygonSides by mutableIntStateOf(5)

    /** 星形角数 (3..12) */
    var starPoints by mutableIntStateOf(5)

    /** 星形内径比 (0.1f..0.9f) */
    var starInnerRatio by mutableFloatStateOf(0.5f)

    /** 1:1 等比约束 (正方形 / 正圆) */
    var keepAspect by mutableStateOf(false)

    /** 多段线/贝塞尔是否闭合 */
    var closed by mutableStateOf(false)

    /** 基础几何点 (文档坐标系) */
    var p1 by mutableStateOf(Offset.Zero)
    var p2 by mutableStateOf(Offset.Zero)

    /** 旋转角度 (度数) */
    var rotationDegrees by mutableFloatStateOf(0f)

    /** 多顶点序列 (用于折线、多边形、贝塞尔曲线) */
    val nodes = mutableStateListOf<ShapeNode>()

    /** 当前选中的节点索引 (用于贝塞尔手柄调节) */
    var selectedNodeIndex by mutableIntStateOf(-1)

    // 交互拖拽过程中的瞬态变量 (无需引起重组)
    var activeHandle: Int = ShapeHandleId.NONE
    var dragStartDocPos: Offset = Offset.Zero
    var dragP1: Offset = Offset.Zero
    var dragP2: Offset = Offset.Zero
    var dragRotation: Float = 0f
    var dragCornerRadius: Float = 0f
    var dragStarInnerRatio: Float = 0.5f
    var isCreatingNewNode: Boolean = false

    /**
     * 重置并初始化新形状
     */
    fun reset(
        newType: ShapeType = type,
        initialPos: Offset = Offset.Zero,
    ) {
        active = true
        type = newType
        p1 = initialPos
        p2 = initialPos
        rotationDegrees = 0f
        activeHandle = ShapeHandleId.NONE
        nodes.clear()
        selectedNodeIndex = -1

        when (newType) {
            ShapeType.POLYGON -> {
                closed = true
                nodes.add(ShapeNode(Point2D(initialPos.x, initialPos.y)))
                selectedNodeIndex = 0
            }
            ShapeType.POLYLINE -> {
                closed = false
                nodes.add(ShapeNode(Point2D(initialPos.x, initialPos.y)))
                selectedNodeIndex = 0
            }
            ShapeType.BEZIER -> {
                closed = false
                nodes.add(ShapeNode(Point2D(initialPos.x, initialPos.y)))
                selectedNodeIndex = 0
            }
            else -> {
                closed = false
            }
        }
    }

    /**
     * 清除并退出编辑模式
     */
    fun clear() {
        active = false
        activeHandle = ShapeHandleId.NONE
        nodes.clear()
        selectedNodeIndex = -1
    }
}
