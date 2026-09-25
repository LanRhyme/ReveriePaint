/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import com.reverie.paint.BuildConfig
import com.reverie.paint.model.CanvasViewTransform
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * Phase 2B: 液化交互态预览的 **AGSL(RuntimeShader)** 版本。
 *
 * 放在 `core/` 的原因: 数据(裁剪像素/网格)由引擎线程取, 绘制在 `ui/painting/canvas` 里发生,
 * 而依赖方向只能 `ui/ → core/ → model/`, 所以共享状态必须留在 core(ui 只调用 [draw])。
 *
 * 与 Phase 2A-2(引擎侧 CPU 反向采样)的分工:
 *  - 引擎只"给料": 未形变的 `bounds` 裁剪(源纹理) + 位移网格(位移纹理);
 *  - 位移采样在显示分辨率上做 `dst(p) = src(p - offset(p))`, 因此预览清晰度不再受
 *    Phase 2A-2 的 192px 上限约束, 每次 dab 也不再需要 CPU 采样;
 *  - 网格双线性插值交给纹理采样器(硬件线性过滤); 网格点与 `run()` 是同一批 ⇒ 几何同源。
 *
 * 三条硬约束:
 *  1. 所有 JNI 取数都在引擎线程(`PaintViewModel.pollLiquifyGpuPreview`)做, UI 线程只 [draw];
 *  2. 开关关闭 / API < 33 / 初始化抛错 → [prepareForGesture] 让引擎继续自己叠加 CPU 预览,
 *     绝不出现"引擎不画、UI 也不画"的空窗;
 *  3. 不改文档: 抬笔仍由 Krita 精确 materialize(撤销仍是"一次手势一条 KisTransaction")。
 *
 * 已知近似(与已验证的 CPU 版语义一致, 因此可逐图对照):
 *  - overlay 是 source-over 叠加, 半透明内容会与底层未形变像素叠加(与 `blendLiquifyPreview` 同);
 *  - 预览始终画在画布最上层, 多图层混合的精确顺序仍由抬笔后的 Krita 决定。
 */
internal object LiquifyGpuPreview {

    private const val PROP_GPU = "debug.reverie.liquifyPreviewGpu"

    /**
     * 构建期注入的实验档位(`-PlqTestProfile=<n>`, 见 `app/build.gradle.kts`):
     * 0 = 不改默认行为; 1 = 默认 AGSL 预览 + latest-state-wins; 2 = 默认 CPU 预览 + latest-state-wins;
     * 3 = 默认 AGSL 预览但不做 latest-state-wins。**只改默认值**, 任一 property 仍可覆盖。
     * 存在的意义: 没有数据线时也能直接装包对照(见 docs/RENDER-OPTIMIZATION.md §4.11)。
     */
    private val testProfile = BuildConfig.LQ_TEST_PROFILE

    /**
     * 位移采样 shader。输入:
     *  - `uSrc`  : 未形变的 bounds 裁剪(1 纹素 = 1 文档像素, CLAMP + 线性);
     *  - `uGrid` : 位移纹理(R = dx, G = dy, 单位文档像素, 线性插值即双线性);
     *  - `uOrigin`/`uEx`/`uEy`: 文档→屏幕仿射(原点 + 两个基向量), 这里做 2x2 求逆变成
     *    屏幕→文档, 于是缩放/旋转/平移自动跟随画布;
     *  - `uCropOrigin`/`uGridOrigin`/`uGridStep`/`uGridSize`: 裁剪与网格在文档坐标里的原点与步长。
     */
    private val SHADER_SRC = """
        uniform shader uSrc;
        uniform shader uGrid;
        uniform float2 uOrigin;
        uniform float2 uEx;
        uniform float2 uEy;
        uniform float2 uCropOrigin;
        uniform float2 uGridOrigin;
        uniform float2 uGridStep;
        uniform float2 uGridSize;

        half4 main(float2 fragCoord) {
            float2 d = fragCoord - uOrigin;
            float det = uEx.x * uEy.y - uEx.y * uEy.x;
            if (abs(det) < 0.000001) {
                return half4(0.0);
            }
            // 屏幕 -> 文档: [uEx uEy] 的逆矩阵(列向量分别是 dx=1 / dy=1 的屏幕位移)
            float2 doc = float2((uEy.y * d.x - uEy.x * d.y) / det,
                                (uEx.x * d.y - uEx.y * d.x) / det);
            float2 g = (doc - uGridOrigin) / uGridStep;
            float2 gMax = uGridSize - 1.0;
            if (g.x < 0.0 || g.y < 0.0 || g.x > gMax.x || g.y > gMax.y) {
                return half4(0.0);
            }
            float2 off = uGrid.eval(g + 0.5).rg;
            return uSrc.eval((doc - uCropOrigin) - off);
        }
    """.trimIndent()

    /** 本次手势是否走 GPU 预览(由 [prepareForGesture] 设定; UI 线程读)。 */
    @Volatile
    var requested = false

    /** 是否已有可绘制内容(UI 线程按帧读取)。 */
    @Volatile
    var active = false

    /** -1 未判定 / 0 不可用 / 1 可用。判定失败后不再重试, 免得每帧都抛异常。 */
    private var supported = -1

    // 更新(引擎线程)与绘制(UI 线程)共用: 两者都是短操作, 用一把锁换掉全部数据竞争
    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var runtime: RuntimeShader? = null
    private var srcBitmap: Bitmap? = null
    private var gridBitmap: Bitmap? = null
    private var srcShader: BitmapShader? = null
    private var gridShader: BitmapShader? = null

    private var cropKey = 0L
    private var cropW = 0
    private var cropH = 0
    private var gridCols = 0
    private var gridRows = 0
    private var gridOriginX = 0f
    private var gridOriginY = 0f
    private var gridStepX = 1f
    private var gridStepY = 1f
    private var cropOriginX = 0f
    private var cropOriginY = 0f

    private val scratch = FloatArray(2)
    private val corners = FloatArray(8)
    private var drawLeft = 0f
    private var drawTop = 0f
    private var drawRight = 0f
    private var drawBottom = 0f
    private var baseX = 0f
    private var baseY = 0f

    /**
     * 手势开始时的判定(任意线程可调用, **不碰 JNI**): 本次手势由 AGSL 还是引擎侧 CPU 画预览。
     *
     * 调用方必须在同一手势的 `runCore` 里把返回值写进引擎(`setLiquifyPreviewHostDrawMode`),
     * 这样即使 property 与 Kotlin 侧判定不一致(例如反射读属性失败), 也不会两边都不画。
     *
     * @return -1 = 跟随 property(GPU 可用且开关打开); 0 = 强制引擎侧 CPU 叠加
     */
    fun decideForGesture(): Int {
        val wantGpu =
            (propBool(PROP_GPU) || testProfile == 1 || testProfile == 3) && ensureSupported()
        requested = wantGpu
        if (!wantGpu) {
            active = false
        }
        // 1 = 强制主机侧(AGSL)绘制; 0 = 强制引擎侧 CPU 叠加; -1 = 交给引擎按 property 判断。
        // 档位 2 是"CPU 预览对照", 用 0 显式打开 —— 否则"引擎要预览"这件事只能靠 property 传达,
        // 无数据线的设备就没法测。
        return when {
            wantGpu -> 1
            testProfile == 2 -> 0
            else -> -1
        }
    }

    /** 平台是否支持(只看 API 等级; shader 编译在 [update] 里做, 失败即回退)。 */
    private fun ensureSupported(): Boolean {
        if (supported >= 0) return supported == 1
        supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 1 else 0
        return supported == 1
    }

    private fun fail() {
        supported = 0
        active = false
        requested = false
        synchronized(lock) {
            runtime = null
            srcShader = null
            gridShader = null
            // 不 recycle(): 上一帧可能仍被渲染线程引用, 交给 GC 更安全
            srcBitmap = null
            gridBitmap = null
            cropKey = 0L
            paint.shader = null
        }
        try {
            // 让引擎立刻回到 CPU 叠加, 宁可预览降级也不能没有预览
            ReverieCoreBridge.setLiquifyPreviewHostDrawMode(0)
        } catch (_: Throwable) {
            // 引擎不可达则忽略
        }
    }

    /** 手势结束/取消, 或引擎报告没有源裁剪时调用。 */
    fun clear() {
        active = false
        synchronized(lock) {
            gridShader = null
            gridBitmap = null
            cropKey = 0L
        }
    }

    /**
     * 引擎线程调用: 用最新一帧的裁剪与网格刷新预览资源。
     *
     * @param crop `[cropW, cropH, docX, docY, docW, docH, seq]`
     * @param src  仅当裁剪发生变化时非空(RGBA8888, 整段手势只上传一次纹理)
     * @param grid `liquifyGrid()` 的原始数组(每个 dab 都会变)
     */
    fun update(crop: IntArray, src: ByteArray?, grid: FloatArray?) {
        if (crop.size < 7 || crop[0] <= 0 || crop[1] <= 0) {
            clear()
            return
        }
        if (!ensureSupported()) {
            fail()
            return
        }
        try {
            synchronized(lock) {
                val key = cropKeyOf(crop)
                if (src != null && key != cropKey) {
                    val bmp = Bitmap.createBitmap(crop[0], crop[1], Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(src))
                    // 换新位图而不是原地覆写: copyPixelsFromBuffer 不保证推进 generation id,
                    // 原地改内容可能让 GPU 继续用旧纹理; 每次新建则可确定会上传。
                    srcBitmap = bmp
                    srcShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    cropOriginX = crop[2].toFloat()
                    cropOriginY = crop[3].toFloat()
                    cropW = crop[0]
                    cropH = crop[1]
                    cropKey = key
                }
                if (grid != null && grid.size >= 8) {
                    applyGridLocked(grid)
                }
                val r = runtime
                val ss = srcShader
                val gs = gridShader
                if (r != null && ss != null && gs != null) {
                    r.setInputShader("uSrc", ss)
                    r.setInputShader("uGrid", gs)
                    r.setFloatUniform("uCropOrigin", cropOriginX, cropOriginY)
                    r.setFloatUniform("uGridOrigin", gridOriginX, gridOriginY)
                    r.setFloatUniform("uGridStep", gridStepX, gridStepY)
                    r.setFloatUniform("uGridSize", gridCols.toFloat(), gridRows.toFloat())
                    paint.shader = r
                    active = true
                }
            }
        } catch (t: Throwable) {
            fail()
        }
    }

    /** 网格 → 位移纹理(R=dx, G=dy, 半精度浮点), 并从真实网格点推原点和步长。 */
    private fun applyGridLocked(grid: FloatArray) {
        val cols = grid[4].toInt()
        val rows = grid[5].toInt()
        val count = grid[7].toInt()
        if (cols < 2 || rows < 2 || count != cols * rows || grid.size < 8 + count * 4) return

        val shorts = ShortArray(count * 4)
        for (i in 0 until count) {
            val base = 8 + i * 4
            shorts[i * 4] = Half.toHalf(grid[base + 2])     // dx
            shorts[i * 4 + 1] = Half.toHalf(grid[base + 3]) // dy
        }
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.RGBA_F16)
        bmp.copyPixelsFromBuffer(ShortBuffer.wrap(shorts))

        // 原点 = 第一个网格点; 步长 = 相邻网格点的实际间距(末列/末行可能被吸附到边界,
        // 所以取前两个点, 与 CPU 版"按 original 坐标插值"的口径一致)
        gridOriginX = grid[8]
        gridOriginY = grid[9]
        val stepX = grid[8 + 4] - gridOriginX
        val stepY = grid[8 + cols * 4 + 1] - gridOriginY
        val fallbackStep = grid[6]
        gridStepX = if (stepX > 0.001f) stepX else fallbackStep
        gridStepY = if (stepY > 0.001f) stepY else fallbackStep
        gridCols = cols
        gridRows = rows

        // 用局部变量传给 setInputShader: 成员是 var(可为空), Kotlin 不会对它做智能转换
        val gs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        gridShader = gs
        gridBitmap = bmp
        val r = runtime
        if (r == null) {
            val created = RuntimeShader(SHADER_SRC)
            created.setInputShader("uGrid", gs)
            runtime = created
        } else {
            r.setInputShader("uGrid", gs)
        }
    }

    /** UI 线程调用: 按当前视图变换把形变后的内容叠到画布之上(仅覆盖网格范围, 其余透明)。 */
    fun draw(canvas: Canvas, vt: CanvasViewTransform) {
        if (!active) return
        synchronized(lock) {
            val r = runtime ?: return
            if (srcShader == null || gridShader == null) return
            if (cropW <= 0 || cropH <= 0) return
            // 文档 -> 屏幕的仿射: 用原点 + 两个基向量表达, 缩放/旋转/平移自然包含在内
            vt.docToScreen(0f, 0f, scratch)
            baseX = scratch[0]
            baseY = scratch[1]
            r.setFloatUniform("uOrigin", baseX, baseY)
            vt.docToScreen(1f, 0f, scratch)
            r.setFloatUniform("uEx", scratch[0] - baseX, scratch[1] - baseY)
            vt.docToScreen(0f, 1f, scratch)
            r.setFloatUniform("uEy", scratch[0] - baseX, scratch[1] - baseY)
            // 只画裁剪区的屏幕包围盒(留 8px 余量): shader 用绝对坐标取值, 缩小绘制范围
            // 只省填充率、不改结果 —— 4K 屏上整屏跑一遍是白白的带宽
            screenBoundsOfCrop(vt)
            canvas.drawRect(
                drawLeft - 8f, drawTop - 8f, drawRight + 8f, drawBottom + 8f, paint,
            )
        }
    }

    /** 裁剪矩形(文档坐标)四角映射到屏幕后的轴对齐包围盒。 */
    private fun screenBoundsOfCrop(vt: CanvasViewTransform) {
        val x0 = cropOriginX
        val y0 = cropOriginY
        val x1 = cropOriginX + cropW
        val y1 = cropOriginY + cropH
        vt.docToScreen(x0, y0, scratch)
        corners[0] = scratch[0]
        corners[1] = scratch[1]
        vt.docToScreen(x1, y0, scratch)
        corners[2] = scratch[0]
        corners[3] = scratch[1]
        vt.docToScreen(x0, y1, scratch)
        corners[4] = scratch[0]
        corners[5] = scratch[1]
        vt.docToScreen(x1, y1, scratch)
        corners[6] = scratch[0]
        corners[7] = scratch[1]
        var minX = corners[0]
        var maxX = corners[0]
        var minY = corners[1]
        var maxY = corners[1]
        var i = 2
        while (i < 8) {
            val x = corners[i]
            val y = corners[i + 1]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
            i += 2
        }
        drawLeft = minX
        drawTop = minY
        drawRight = maxX
        drawBottom = maxY
    }

    /** 裁剪指纹: 只用于判断"是否需要重新上传源纹理", 冲突最坏是多上传一次, 无正确性风险。 */
    fun cropKeyOf(crop: IntArray): Long {
        var k = crop[0].toLong() * 1009L + crop[1]
        k = k * 1009L + crop[2]
        k = k * 1009L + crop[3]
        return k
    }

    /** 读取引擎侧同名诊断 property(只读, 不写); 反射不可用/属性不存在都当作"关"。 */
    private fun propBool(key: String): Boolean = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        val raw = get.invoke(null, key, "") as? String
        (raw?.trim()?.toIntOrNull() ?: 0) != 0
    } catch (_: Throwable) {
        false
    }
}
