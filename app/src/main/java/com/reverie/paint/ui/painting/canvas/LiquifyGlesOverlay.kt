/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Half
import android.util.Log
import android.view.TextureView
import com.reverie.paint.core.LiquifyGlesPreview
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.model.LiquifyGridMeta
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Phase 5 · C2: 液化交互层的**自持 GLES 覆盖层** —— 用与 AGSL 版**同一套数学**复刻出画面,
 * 和现有路径做 A/B 对照 (见 docs/LIQUIFY-PHASE5-GLES-PLAN.md §4 · C2)。
 *
 * ### 为什么是 TextureView 而不是 SurfaceView(C1 实测结论)
 * 最初用 `SurfaceView + setZOrderMediaOverlay(true)`, 真机**进画布即黑屏、连性能标尺都看不见** ——
 * 因为 SurfaceView 是**独立 surface 层**: 它要么在 window 之下, 要么在最上; 无法"夹在
 * HWUI 位图之上、Compose 覆盖层之下"。同窗口内做夹层只能用**普通 View**。
 * `TextureView` 是普通 View(参与同一 view 层级), z-order 天然按组合顺序 ⇒ 可以正确夹层,
 * 同时仍能把 GLES 渲染结果挂到它的 `SurfaceTexture` 上。
 *
 * ### C2 做的事
 * 逐条复刻 `LiquifyGpuPreview.SHADER_SRC`(AGSL) 的数学, 只是换成 GLES:
 * ```
 * doc  = inverse(uEx, uEy) * (frag - uOrigin)      // 屏幕 -> 文档(2x2 求逆)
 * g    = (doc - uGridOrigin) / uGridStep           // 文档 -> 网格坐标
 * off  = texture(uGrid, (g + 0.5) / gridSize)      // 双线性 = 硬件线性过滤
 * 颜色  = texture(uSrc, (doc - uCropOrigin - off) / uCropSize)
 * ```
 * 网格越界(`g` 落在 [0, size-1] 之外)输出全透明, 与 AGSL 版一致 —— 覆盖层只画形变区,
 * 其余一律让画布透过来。
 *
 * ### 坐标系与混合(逐条对齐 AGSL 路径的口径, A/B 时重点看这三处)
 * 1. 视口 = 本 View 的像素尺寸, 与 `CanvasTouchView` 同一坐标系(两者都是 `fillMaxSize` 的
 *    兄弟 View); GL 的 `gl_FragCoord.y` 向上、画布坐标向下 ⇒ `frag.y = viewH - gl_FragCoord.y`;
 * 2. 源裁剪/位移网格都是**自上而下**上传(v=0 就是文档 top), 所以采样坐标直接用
 *    `(doc - cropOrigin) / cropSize`, 不做翻转; 纹素中心 = `(i + 0.5)` 与 BitmapShader 一致;
 * 3. 源像素是 Krita 的**预乘** BGRA(引擎侧已换成 RGBA), 所以用预乘 source-over
 *    (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`) —— 与 AGSL 的返回预乘色 + hwui 的 SRC_OVER 同义。
 *
 * ### 门槛与回退
 * 需要 **OpenGL ES 3.0** 上下文(16F 位移纹理可被硬件线性过滤, 与 AGSL 的 `RGBA_F16` 网格同精度)。
 * EGL/着色器/交换任何一环失败都会调 [`LiquifyGlesPreview.markFailed`] 并把自己设为 `GONE` ——
 * 引擎线程下一帧会把"预览由谁画"交回引擎 CPU 预览, **永远不会**让画板没预览或被盖黑。
 *
 * 开关(**默认关**): `setprop debug.reverie.liquifyGles 1`, 或构建期档位 `-PlqTestProfile=4`
 * (档位 4 = 只开这条路径、AGSL 预览保持关闭 ⇒ 干净的单变量对照)。
 */
internal class LiquifyGlesOverlay(context: Context) :
    TextureView(context), TextureView.SurfaceTextureListener {

    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    private val egl = EglCore()

    /** 渲染线程专用的一帧快照(复用 ⇒ 零分配)。 */
    private val frame = LiquifyGlesPreview.Frame()

    init {
        // 不透明=false: 需要 alpha 参与合成(否则"透明"会被合成为黑)
        isOpaque = false
        // 不接触摸: 手势仍由 CanvasTouchView 统一处理
        isClickable = false
        isFocusable = false
        // C2 起每个像素的 alpha 由 shader 决定(预乘输出), View 层不再打折
        alpha = 1f
        surfaceTextureListener = this
    }

    // ---------------- TextureView.SurfaceTextureListener ----------------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        egl.viewportW = w
        egl.viewportH = h
        running = true
        // 声明"我活着": 手势开始的判定只在这时候才会把绘制权交给 GLES(见 LiquifyGlesPreview.setAlive)
        LiquifyGlesPreview.setAlive(true)
        // HUD 读数: 没有数据线时靠这一格确认"GLES 到底就绪了没"
        PerfTrace.liquifyGlesState("就绪")
        renderThread = Thread({ renderLoop(st) }, "ReverieLiquifyGles").apply { start() }
        Log.i(TAG, "surfaceTextureAvailable ${w}x$h")
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        egl.viewportW = w
        egl.viewportH = h
        // 视口变了必须重画: 仿射求逆与 NDC 矩形都依赖视口尺寸
        LiquifyGlesPreview.requestRender()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopRendering()
        egl.release()
        Log.i(TAG, "surfaceTextureDestroyed")
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        stopRendering()
        super.onDetachedFromWindow()
    }

    private fun stopRendering() {
        running = false
        PerfTrace.liquifyGlesState("已卸载")
        // 先交回绘制权再退出: setAlive(false) 内部会 clear(), 于是若手势还在进行, 预览当场由
        // AGSL 或引擎接手(引擎侧仍在"主机侧绘制"判定里, 不会留下没人画的真空期)
        LiquifyGlesPreview.setAlive(false)
        // 渲染线程平时阻塞在 awaitFrame 上: 叫醒它, 否则要等超时才退出
        LiquifyGlesPreview.requestRender()
        renderThread?.join(1000)
        renderThread = null
    }

    /** 任何失败都自我隐藏, 并把"预览由谁画"交回引擎 CPU 路径(绝不允许两边都不画)。 */
    private fun bailOut(reason: String) {
        Log.w(TAG, "liquifyGles 关闭: $reason")
        running = false
        LiquifyGlesPreview.markFailed()
        // HUD 读数: 失败原因直接上屏(没有数据线时这一格就是唯一的报错出口)
        PerfTrace.liquifyGlesState("失败 $reason")
        post { visibility = GONE }
    }

    // ---------------- 渲染循环 ----------------

    private fun renderLoop(st: SurfaceTexture) {
        if (!egl.init(st)) {
            bailOut("EGL(ES3) 初始化失败")
            return
        }
        val renderer = Renderer()
        if (!renderer.prepare()) {
            renderer.release()
            egl.release()
            bailOut(renderer.failReason)
            return
        }
        // 线程刚起来: 先清一帧全透明(上一次会话可能把内容留在了 SurfaceTexture 上)
        LiquifyGlesPreview.requestRender()
        var frames = 0
        var lastLog = SystemClock.elapsedRealtime()
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLog >= 1000L) {
                Log.i(
                    TAG,
                    "liquifyGles ${frames}fx/s 出帧${LiquifyGlesPreview.renderedFrames}" +
                        " 源上传${LiquifyGlesPreview.sourceUploadCount}" +
                        " 网格上传${LiquifyGlesPreview.gridUploads}" +
                        " 暂存${LiquifyGlesPreview.previewUpdates}" +
                        " 纹理${LiquifyGlesPreview.textureWidth}x${LiquifyGlesPreview.textureHeight}" +
                        " vp=${egl.viewportW}x${egl.viewportH}",
                )
                frames = 0
                lastLog = now
            }
            // 没有新状态就一直阻塞(不空烧 GPU), 最长 WAIT_MS 毫秒好让上面的日志有节奏
            if (!LiquifyGlesPreview.awaitFrame(frame, WAIT_MS)) continue
            if (!running) break
            if (!egl.makeCurrent()) {
                bailOut("eglMakeCurrent 失败")
                break
            }
            GLES20.glViewport(0, 0, egl.viewportW, egl.viewportH)
            // 全透明清屏: 未绘制处必须让画布透过来
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (frame.valid) renderer.render(frame, egl.viewportW, egl.viewportH)
            if (!egl.swapBuffers()) {
                bailOut("swapBuffers 失败")
                break
            }
            frames++
            LiquifyGlesPreview.noteFrameRendered()
        }
        if (egl.makeCurrent()) renderer.release()
        egl.release()
    }

    // ---------------- GL 资源与绘制 ----------------

    /** 一次会话内的 GL 对象与纹理上传缓冲(全部在渲染线程上创建/释放)。 */
    private inner class Renderer {

        var failReason = "GLES 初始化失败"
            private set

        private var program = 0
        private var aPos = -1
        private var uRect = -1
        private var uSrc = -1
        private var uGrid = -1
        private var uViewSize = -1
        private var uOrigin = -1
        private var uEx = -1
        private var uEy = -1
        private var uCropOrigin = -1
        private var uCropSize = -1
        private var uGridOrigin = -1
        private var uGridStep = -1
        private var uGridSize = -1

        private var srcTex = 0
        private var gridTex = 0
        private var srcTexW = 0
        private var srcTexH = 0
        private var gridTexW = 0
        private var gridTexH = 0

        private var quad: FloatBuffer? = null

        /** 源像素上传缓冲(只在 rebase 时用一次; 复用 direct buffer 避免每次分配 2MB)。 */
        private var srcBytes: ByteBuffer? = null

        /** 位移网格的 half float 上传缓冲(每个 dab 用一次; 按需扩容)。 */
        private var gridHalf: ShortBuffer? = null

        /** NDC 矩形 `[x0, y0, x1, y1]`(即裁剪区映射到屏幕后的包围盒)。 */
        private val rect = FloatArray(4)
        private val corners = FloatArray(8)

        fun prepare(): Boolean {
            val vs = ShaderCache.compile(GLES20.GL_VERTEX_SHADER, ShaderCache.VS, "vs")
            val fs = ShaderCache.compile(GLES20.GL_FRAGMENT_SHADER, ShaderCache.FS, "fs")
            if (vs == 0 || fs == 0) {
                failReason = "着色器编译失败"
                return false
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val link = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
            if (link[0] == 0) {
                Log.w(TAG, "link 失败: ${GLES20.glGetProgramInfoLog(p)}")
                GLES20.glDeleteProgram(p)
                failReason = "program link 失败"
                return false
            }
            program = p
            aPos = GLES20.glGetAttribLocation(p, "aPos")
            uRect = GLES20.glGetUniformLocation(p, "uRect")
            uSrc = GLES20.glGetUniformLocation(p, "uSrc")
            uGrid = GLES20.glGetUniformLocation(p, "uGrid")
            uViewSize = GLES20.glGetUniformLocation(p, "uViewSize")
            uOrigin = GLES20.glGetUniformLocation(p, "uOrigin")
            uEx = GLES20.glGetUniformLocation(p, "uEx")
            uEy = GLES20.glGetUniformLocation(p, "uEy")
            uCropOrigin = GLES20.glGetUniformLocation(p, "uCropOrigin")
            uCropSize = GLES20.glGetUniformLocation(p, "uCropSize")
            uGridOrigin = GLES20.glGetUniformLocation(p, "uGridOrigin")
            uGridStep = GLES20.glGetUniformLocation(p, "uGridStep")
            uGridSize = GLES20.glGetUniformLocation(p, "uGridSize")
            if (aPos < 0) {
                failReason = "aPos 缺失"
                return false
            }
            quad = ShaderCache.unitQuad()
            srcTex = createTexture()
            gridTex = createTexture()
            if (srcTex == 0 || gridTex == 0) {
                failReason = "纹理创建失败"
                return false
            }
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uSrc, TEX_UNIT_SRC)
            GLES20.glUniform1i(uGrid, TEX_UNIT_GRID)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            // 源像素是预乘的 ⇒ 预乘 source-over(与 AGSL 路径同义, 见类注释第 3 条)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            return true
        }

        fun release() {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (srcTex != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(srcTex), 0)
                srcTex = 0
            }
            if (gridTex != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(gridTex), 0)
                gridTex = 0
            }
            quad = null
            srcBytes = null
            gridHalf = null
        }

        /** 出一帧。纹理只传"变了的那一份", uniform 每帧都刷(仿射每帧都可能变)。 */
        fun render(f: LiquifyGlesPreview.Frame, w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            if (f.src != null) uploadSrc(f)
            if (f.grid != null) uploadGrid(f)
            // 纹理尺寸与当前裁剪/网格不一致(例如源像素长度不符被丢弃) ⇒ 这一帧不画, 免得错位
            if (srcTexW != f.cropW || srcTexH != f.cropH) return
            if (gridTexW != f.gridCols || gridTexH != f.gridRows) return
            if (!computeQuad(f, w, h)) return

            // 每段手势的首帧把喂给 shader 的**全套 uniform** 同时送 logcat 与标尺 HUD ——
            // 没有数据线时, 截图里的这一行就是核对"坐标系 / 仿射 / 网格口径"的唯一依据。
            // 一次性诊断, 不在每帧热路径上。
            if (LiquifyGlesPreview.renderedFrames == 0L) {
                val snap =
                    "gles首帧 vp=${w}x$h 裁剪=${f.cropW}x${f.cropH}@(${f.cropOriginX},${f.cropOriginY})" +
                        " 网格=${f.gridCols}x${f.gridRows} 步长=(${f.gridStepX},${f.gridStepY})" +
                        " O=(${f.affineOx},${f.affineOy}) Ex=(${f.exX},${f.exY}) Ey=(${f.eyX},${f.eyY})" +
                        " NDC=[${rect[0]},${rect[1]},${rect[2]},${rect[3]}]"
                Log.i(TAG, "liquifyGles 首帧 $snap")
                PerfTrace.liquifyGlesSnapshot(snap)
            }

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TEX_UNIT_SRC)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TEX_UNIT_GRID)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gridTex)
            GLES20.glUniform2f(uViewSize, w.toFloat(), h.toFloat())
            GLES20.glUniform2f(uOrigin, f.affineOx, f.affineOy)
            GLES20.glUniform2f(uEx, f.exX, f.exY)
            GLES20.glUniform2f(uEy, f.eyX, f.eyY)
            GLES20.glUniform2f(uCropOrigin, f.cropOriginX, f.cropOriginY)
            GLES20.glUniform2f(uCropSize, f.cropW.toFloat(), f.cropH.toFloat())
            GLES20.glUniform2f(uGridOrigin, f.gridOriginX, f.gridOriginY)
            GLES20.glUniform2f(uGridStep, f.gridStepX, f.gridStepY)
            GLES20.glUniform2f(uGridSize, f.gridCols.toFloat(), f.gridRows.toFloat())
            GLES20.glUniform4f(uRect, rect[0], rect[1], rect[2], rect[3])

            val q = quad ?: return
            GLES20.glEnableVertexAttribArray(aPos)
            q.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, q)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
        }

        /** 源裁剪 → RGBA8 纹理(整段手势只上传一次, 见 [LiquifyGlesPreview]). */
        private fun uploadSrc(f: LiquifyGlesPreview.Frame) {
            val bytes = f.src ?: return
            val need = f.cropW * f.cropH * 4
            if (f.cropW <= 0 || f.cropH <= 0 || bytes.size < need) return
            var buf = srcBytes
            if (buf == null || buf.capacity() < need) {
                buf = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
                srcBytes = buf
            }
            buf.clear()
            buf.put(bytes, 0, need)
            buf.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, f.cropW, f.cropH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
            )
            srcTexW = f.cropW
            srcTexH = f.cropH
            LiquifyGlesPreview.noteSourceUpload(f.cropW, f.cropH)
        }

        /**
         * 位移网格 → `RGBA16F` 纹理(R = dx, G = dy, 单位文档像素)。
         *
         * 与 AGSL 版**同一个纹理格式**: 半精度 + 硬件线性过滤, 于是"网格双线性插值"这件事
         * 在两条路径上是同一个数值结果 —— 这是 C2 能逐图对照的前提。
         */
        private fun uploadGrid(f: LiquifyGlesPreview.Frame) {
            val grid = f.grid ?: return
            val cols = f.gridCols
            val rows = f.gridRows
            val count = cols * rows
            if (cols < 2 || rows < 2) return
            if (grid.size < LiquifyGridMeta.HEADER + count * LiquifyGridMeta.STRIDE) return

            val need = count * 4
            var half = gridHalf
            if (half == null || half.capacity() < need) {
                half = ByteBuffer.allocateDirect(need * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                gridHalf = half
            }
            half.clear()
            for (i in 0 until count) {
                val b = LiquifyGridMeta.HEADER + i * LiquifyGridMeta.STRIDE
                half.put(Half.toHalf(grid[b + 2]))     // dx
                half.put(Half.toHalf(grid[b + 3]))     // dy
                half.put(0)
                half.put(0)
            }
            half.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gridTex)
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, cols, rows, 0,
                GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, half,
            )
            gridTexW = cols
            gridTexH = rows
            LiquifyGlesPreview.noteGridUpload()
        }

        /**
         * 裁剪矩形(文档坐标)四角 → 屏幕包围盒 → NDC 矩形, 写进 [rect]。
         *
         * 屏幕坐标用与 shader 同一套仿射: `screen = O + docX * Ex + docY * Ey`。
         * 这里只决定**光栅化范围**(shader 自己按绝对坐标取值), 所以 ±8px 余量与 AGSL 版一致,
         * 少花的是纯填充率。
         */
        private fun computeQuad(f: LiquifyGlesPreview.Frame, w: Int, h: Int): Boolean {
            val ox = f.affineOx
            val oy = f.affineOy
            val x0 = f.cropOriginX
            val y0 = f.cropOriginY
            val x1 = f.cropOriginX + f.cropW
            val y1 = f.cropOriginY + f.cropH
            corners[0] = ox + x0 * f.exX + y0 * f.eyX
            corners[1] = oy + x0 * f.exY + y0 * f.eyY
            corners[2] = ox + x1 * f.exX + y0 * f.eyX
            corners[3] = oy + x1 * f.exY + y0 * f.eyY
            corners[4] = ox + x0 * f.exX + y1 * f.eyX
            corners[5] = oy + x0 * f.exY + y1 * f.eyY
            corners[6] = ox + x1 * f.exX + y1 * f.eyX
            corners[7] = oy + x1 * f.exY + y1 * f.eyY
            var minX = corners[0]
            var maxX = corners[0]
            var minY = corners[1]
            var maxY = corners[1]
            var i = 2
            while (i < 8) {
                val cx = corners[i]
                val cy = corners[i + 1]
                if (cx < minX) minX = cx else if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy else if (cy > maxY) maxY = cy
                i += 2
            }
            minX = (minX - QUAD_MARGIN_PX).coerceAtLeast(0f)
            minY = (minY - QUAD_MARGIN_PX).coerceAtLeast(0f)
            maxX = (maxX + QUAD_MARGIN_PX).coerceAtMost(w.toFloat())
            maxY = (maxY + QUAD_MARGIN_PX).coerceAtMost(h.toFloat())
            if (maxX - minX < 1f || maxY - minY < 1f) return false
            rect[0] = minX * 2f / w - 1f
            rect[2] = maxX * 2f / w - 1f
            // GL 的 y 向上、画布坐标向下 ⇒ 翻转(见类注释第 1 条)
            rect[1] = 1f - maxY * 2f / h
            rect[3] = 1f - minY * 2f / h
            return true
        }

        private fun createTexture(): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            if (t[0] == 0) return 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            // MIN_FILTER 默认是 NEAREST_MIPMAP_LINEAR ⇒ 不显式设 LINEAR 纹理会判为不完整(全黑)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // 与 BitmapShader(CLAMP) 同义: 位移把采样点推出裁剪外时夹边, 不重复不镜像
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }
    }

    // ---------------- EGL ----------------

    private class EglCore {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var viewportW = 1
        var viewportH = 1

        fun init(window: Any): Boolean {
            release()
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false

            // 必须带 ALPHA_8: 否则"透明"会被合成为黑(黑屏的一个可能来源)
            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
                return false
            }
            // 要 ES 3.0: 位移纹理是 RGBA16F, 线性过滤在 ES3 里是核心能力(ES2 得看扩展)。
            // 拿不到 ES3 就直接失败 —— 让上层回退到引擎 CPU 预览, 好过写第二套纹理路径。
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(
                display, configs[0], window, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return false
            return makeCurrent()
        }

        fun makeCurrent(): Boolean =
            display != EGL14.EGL_NO_DISPLAY &&
                EGL14.eglMakeCurrent(display, surface, surface, context)

        fun swapBuffers(): Boolean =
            display != EGL14.EGL_NO_DISPLAY && EGL14.eglSwapBuffers(display, surface)

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }
    }

    private object ShaderCache {

        /** 顶点: 单位方 → NDC 矩形(裁剪区的屏幕包围盒), 光栅化范围由此限定。 */
        val VS = """
            attribute vec2 aPos;
            uniform vec4 uRect;
            void main() {
                vec2 p = uRect.xy + (uRect.zw - uRect.xy) * aPos;
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """

        /**
         * 片元: 与 AGSL 版逐行对应(见类注释)。`uGrid` 的 `(g + 0.5) / gridSize` 是
         * "网格坐标 → 纹素中心"的采样位置, 与 `BitmapShader.eval(g + 0.5)` 同义。
         */
        val FS = """
            precision highp float;
            uniform sampler2D uSrc;
            uniform sampler2D uGrid;
            uniform vec2 uViewSize;
            uniform vec2 uOrigin;
            uniform vec2 uEx;
            uniform vec2 uEy;
            uniform vec2 uCropOrigin;
            uniform vec2 uCropSize;
            uniform vec2 uGridOrigin;
            uniform vec2 uGridStep;
            uniform vec2 uGridSize;

            void main() {
                // gl_FragCoord 的 y 向上, 画布坐标 y 向下 ⇒ 翻回画布坐标
                vec2 frag = vec2(gl_FragCoord.x, uViewSize.y - gl_FragCoord.y);
                vec2 d = frag - uOrigin;
                float det = uEx.x * uEy.y - uEx.y * uEy.x;
                if (abs(det) < 1e-6) { gl_FragColor = vec4(0.0); return; }
                vec2 doc = vec2((uEy.y * d.x - uEy.x * d.y) / det,
                                (uEx.x * d.y - uEx.y * d.x) / det);
                vec2 g = (doc - uGridOrigin) / uGridStep;
                vec2 gMax = uGridSize - 1.0;
                if (g.x < 0.0 || g.y < 0.0 || g.x > gMax.x || g.y > gMax.y) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                vec2 off = texture2D(uGrid, (g + 0.5) / uGridSize).rg;
                gl_FragColor = texture2D(uSrc, (doc - uCropOrigin - off) / uCropSize);
            }
        """.trimIndent()

        private var qbuf: FloatBuffer? = null

        /** 单位方的三角形带(顶点属性是 0/1, 真正的矩形由 `uRect` 给出)。 */
        fun unitQuad(): FloatBuffer {
            var b = qbuf
            if (b == null) {
                b = ByteBuffer.allocateDirect(4 * 2 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                b.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
                b.position(0)
                qbuf = b
            }
            return b
        }

        fun compile(type: Int, src: String, tag: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.w(TAG, "$tag 编译失败: ${GLES20.glGetShaderInfoLog(s)}")
                GLES20.glDeleteShader(s)
                return 0
            }
            return s
        }
    }

    companion object {
        private const val TAG = "ReveriePerf"

        /** 开关判定在 core(`LiquifyGlesPreview`)—— 取数侧也要读同一份, 免得两侧不一致。 */
        val enabled: Boolean get() = LiquifyGlesPreview.enabled

        /**
         * 覆盖层是否该挂进视图树。除了 property/构建档位, 还要认**设置页里的"预览方式 = GLES"**
         * (没有数据线时唯一的入口, 见 `LiquifyGpuPreview.HOST_OVERRIDE_GLES`)。
         * 调用方传入 `vm.liquifyHostDraw`, 于是页内切换也会重组并重建/摘除覆盖层。
         */
        fun enabledFor(hostOverride: Int): Boolean = LiquifyGlesPreview.isOn(hostOverride)

        /** 纹理单元: 0 = 源裁剪, 1 = 位移网格。 */
        private const val TEX_UNIT_SRC = 0
        private const val TEX_UNIT_GRID = 1

        /** 光栅化包围盒的余量(px): 位移会把边界外的像素拉进来一点, 与 AGSL 版一致。 */
        private const val QUAD_MARGIN_PX = 8f

        /** 无新状态时的最长等待(ms): 只为让诊断日志有节奏, 与渲染无关。 */
        private const val WAIT_MS = 250L
    }
}
