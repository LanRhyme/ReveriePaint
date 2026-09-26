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
 * ### C3: 常驻浮点位移场 (本类现在的主力路径)
 * C2 的呈现 pass 每帧重建 `uGrid`, 位移仍来自 Krita 的 16px 网格 ⇒ 台阶感与"窗口重锚定接缝"
 * 都在。C3 改成: 覆盖层自己持有一张**与源裁剪逐像素对齐的 RGBA16F 位移场**, 每来一个补点
 * 就只在该补点的**影响半径包围盒**里做一次累加 draw (`docs/LIQUIFY-C3-FIELD-PLAN.md` §2):
 * ```
 * 逐 dab: 视口 = 中心 ± 影响半径(场坐标), 加性混合 ⇒ 场 += kernel(mode, d, t, 增益)
 * 呈现:   off = texture(uField, (doc - 场原点) / 场尺寸)     // 只换这一行
 * ```
 * 两个关键实现选择(与文档 §2.3 的 ping/pong 表述不同, 理由见该方法注释):
 *  - **单张场纹理 + `GL_ONE/GL_ONE` 加性混合**, 不做 ping/pong: 位移增量只与 `gl_FragCoord`
 *    推出来的文档坐标有关, 着色器**不采样旧场** ⇒ 不存在"同一张纹理既读又写"的反馈环;
 *    而字面意义的 ping/pong 每个 dab 都要把未绘制部分拷到另一张纹理(全分辨率时 4M px/dab),
 *    恰好把局部化的收益吃光。
 *  - **场按源裁剪对齐**: 纹素 (i,j) ↔ 文档 `cropOrigin + (i,j)*res`; 源世代一变(新手势 / rebase)
 *    即按新裁剪重建并清零, 再从 0 重新累加 —— 因为 rebase 已把此前位移物化进源像素,
 *    引擎网格也重置了, 场继续留着就会重复累加。
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
        // C3: 场的那一格也一起清掉 —— 覆盖层卸载后还留着"场 …"会让人以为它还在生效
        PerfTrace.liquifyGlesField(null)
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
            if (frame.valid) {
                renderer.render(frame, egl.viewportW, egl.viewportH)
            } else {
                // 手势结束/取消: 场与计数一起作废, 免得下一段手势接着上一段的世代继续累加
                renderer.onFrameIdle()
            }
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
        private var uField = -1
        private var uUseField = -1
        private var uFieldOrigin = -1
        private var uFieldSize = -1

        private var srcTex = 0
        private var gridTex = 0
        private var srcTexW = 0
        private var srcTexH = 0
        private var gridTexW = 0
        private var gridTexH = 0

        // ---- C3: 常驻位移场 (dab 累加 pass 的程序 + 场纹理/FBO + 世代) ----

        private var dabProgram = 0
        private var dabAPos = -1
        private var dabURect = -1
        private var dabUFieldOrigin = -1
        private var dabUFieldStep = -1
        private var dabUFieldTexSize = -1
        private var dabUCenter = -1
        private var dabUDelta = -1
        private var dabURadius = -1
        private var dabUGain = -1
        private var dabUMode = -1

        /** 场纹理与它的 FBO (单张 + 加性混合, 见 [accumulateDabs])。 */
        private var fieldTex = 0
        private var fieldFbo = 0
        private var fieldW = 0
        private var fieldH = 0

        /** 场的降采样比(文档像素/场纹素); 超预算时自动翻倍, 见 [ensureField]。 */
        private var fieldRes = 1

        /** 场尺寸已分配且 FBO 完整。 */
        private var fieldReady = false

        /** 本会话是否已放弃常驻场(**粘住**: 不再重试, 标尺给出原因)。 */
        private var fieldUnavailable = false
        private var fieldFailReason = ""

        /** 场当前累积到的源世代(与 [LiquifyGlesPreview.Frame.srcGen] 对齐)。 */
        private var fieldGen = -1L

        /** 本段手势已累加的补点数与读数节流(避免每帧拼字符串)。 */
        private var fieldDabsInGesture = 0
        private var reportedDabs = -1
        private var reportedState = -1

        /** 全视口 NDC 矩形 —— dab 累加 pass 的视口已经就是该 dab 的包围盒。 */
        private val fullRect = floatArrayOf(-1f, -1f, 1f, 1f)

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
            // C3: 呈现 pass 的场分支(未启用时恒 0, 走上面那套网格口径)
            uField = GLES20.glGetUniformLocation(p, "uField")
            uUseField = GLES20.glGetUniformLocation(p, "uUseField")
            uFieldOrigin = GLES20.glGetUniformLocation(p, "uFieldOrigin")
            uFieldSize = GLES20.glGetUniformLocation(p, "uFieldSize")
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
            GLES20.glUniform1i(uField, TEX_UNIT_FIELD)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            // 源像素是预乘的 ⇒ 预乘 source-over(与 AGSL 路径同义, 见类注释第 3 条)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // C3 的场是可选加速路径: 建不起来只回退网格, 绝不带走整条 GLES 预览
            prepareField()
            return true
        }

        fun release() {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (dabProgram != 0) {
                GLES20.glDeleteProgram(dabProgram)
                dabProgram = 0
            }
            if (srcTex != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(srcTex), 0)
                srcTex = 0
            }
            if (gridTex != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(gridTex), 0)
                gridTex = 0
            }
            if (fieldTex != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(fieldTex), 0)
                fieldTex = 0
            }
            if (fieldFbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fieldFbo), 0)
                fieldFbo = 0
            }
            fieldReady = false
            quad = null
            srcBytes = null
            gridHalf = null
        }

        /**
         * 本帧无内容(手势结束/取消)。只作废场世代与读数, 不碰 GL —— 屏幕已经由外层的
         * 全透明清屏擦干净了。
         */
        fun onFrameIdle() {
            fieldGen = -1L
            fieldDabsInGesture = 0
            reportedDabs = -1
            reportedState = STATE_UNKNOWN
            PerfTrace.liquifyGlesField(null)
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

            // C3: 先把本帧的补点累加进常驻场, 呈现 pass 再统一采样(只换那一行位移来源)
            val fieldActive = prepareFieldForFrame(f)
            if (fieldActive) {
                // 取补点必须在这里(而不是在 awaitFrame 里): 只有真的要累加时才取走,
                // 无效帧/提前 return 的帧不会把补点吞掉(见 LiquifyGlesPreview.takeDabs)
                LiquifyGlesPreview.takeDabs(f)
                accumulateDabs(f)
                // 累加 pass 把视口挪到了各 dab 的包围盒 ⇒ 呈现 pass 必须回到整视口
                GLES20.glViewport(0, 0, w, h)
            }
            reportField(f, fieldActive)

            // 每段手势的首帧把喂给 shader 的**全套 uniform** 同时送 logcat 与标尺 HUD ——
            // 没有数据线时, 截图里的这一行就是核对"坐标系 / 仿射 / 网格/场口径"的唯一依据。
            // 一次性诊断, 不在每帧热路径上。
            if (LiquifyGlesPreview.renderedFrames == 0L) {
                val snap =
                    "gles首帧 vp=${w}x$h 裁剪=${f.cropW}x${f.cropH}@(${f.cropOriginX},${f.cropOriginY})" +
                        " 网格=${f.gridCols}x${f.gridRows} 步长=(${f.gridStepX},${f.gridStepY})" +
                        " 场=" + (if (fieldActive) "${fieldW}x${fieldH}/$fieldRes" else "关") +
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
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TEX_UNIT_FIELD)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fieldTex)
            GLES20.glUniform2f(uViewSize, w.toFloat(), h.toFloat())
            GLES20.glUniform2f(uOrigin, f.affineOx, f.affineOy)
            GLES20.glUniform2f(uEx, f.exX, f.exY)
            GLES20.glUniform2f(uEy, f.eyX, f.eyY)
            GLES20.glUniform2f(uCropOrigin, f.cropOriginX, f.cropOriginY)
            GLES20.glUniform2f(uCropSize, f.cropW.toFloat(), f.cropH.toFloat())
            GLES20.glUniform2f(uGridOrigin, f.gridOriginX, f.gridOriginY)
            GLES20.glUniform2f(uGridStep, f.gridStepX, f.gridStepY)
            GLES20.glUniform2f(uGridSize, f.gridCols.toFloat(), f.gridRows.toFloat())
            GLES20.glUniform1f(uUseField, if (fieldActive) 1f else 0f)
            GLES20.glUniform2f(uFieldOrigin, f.cropOriginX, f.cropOriginY)
            // 场的文档尺寸 = 场纹素 × 降采样比(场与裁剪逐像素对齐, 所以原点就是裁剪原点)
            val res = fieldRes.toFloat()
            GLES20.glUniform2f(uFieldSize, (fieldW * res).coerceAtLeast(1f), (fieldH * res).coerceAtLeast(1f))
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

        // ---------------- C3: 常驻位移场 ----------------

        /**
         * 建场的 GL 资源与 dab 累加 pass 的程序。
         *
         * **失败不致命**: 场是可选的加速路径 —— 这里任何一环建不起来, 只置 [fieldUnavailable]
         * 并让本会话继续走 C2 的网格路径(标尺显示"场 已回退网格(原因)")。绝不能让"场的失败"
         * 带走整条 GLES 预览, 那等于从"画得差一点"直接掉成"没有预览"。
         */
        private fun prepareField() {
            val vs = ShaderCache.compile(GLES20.GL_VERTEX_SHADER, ShaderCache.VS, "dab vs")
            val fs = ShaderCache.compile(GLES20.GL_FRAGMENT_SHADER, ShaderCache.DAB_FS, "dab fs")
            if (vs == 0 || fs == 0) {
                giveUpField("dab 着色器编译失败")
                return
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
                Log.w(TAG, "dab link 失败: ${GLES20.glGetProgramInfoLog(p)}")
                GLES20.glDeleteProgram(p)
                giveUpField("dab program link 失败")
                return
            }
            dabProgram = p
            dabAPos = GLES20.glGetAttribLocation(p, "aPos")
            dabURect = GLES20.glGetUniformLocation(p, "uRect")
            dabUFieldOrigin = GLES20.glGetUniformLocation(p, "uFieldOrigin")
            dabUFieldStep = GLES20.glGetUniformLocation(p, "uFieldStep")
            dabUFieldTexSize = GLES20.glGetUniformLocation(p, "uFieldTexSize")
            dabUCenter = GLES20.glGetUniformLocation(p, "uDabCenter")
            dabUDelta = GLES20.glGetUniformLocation(p, "uDabDelta")
            dabURadius = GLES20.glGetUniformLocation(p, "uDabRadius")
            dabUGain = GLES20.glGetUniformLocation(p, "uDabGain")
            dabUMode = GLES20.glGetUniformLocation(p, "uDabMode")
            if (dabAPos < 0) {
                giveUpField("dab aPos 缺失")
                return
            }

            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            if (t[0] == 0) {
                giveUpField("场纹理创建失败")
                return
            }
            fieldTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fieldTex)
            // 线性过滤: 位移的双线性交给硬件(与网格路径同一套采样语义); CLAMP 与已验收的夹紧一致
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val fb = IntArray(1)
            GLES30.glGenFramebuffers(1, fb, 0)
            if (fb[0] == 0) {
                giveUpField("场 FBO 创建失败")
                return
            }
            fieldFbo = fb[0]
            Log.i(TAG, "liquifyGles 场资源就绪(尺寸在首个源裁剪到达时确定)")
        }

        /** 放弃常驻场(本会话不再重试): 只影响场的可用性, 呈现走网格路径。 */
        private fun giveUpField(reason: String) {
            fieldUnavailable = true
            fieldFailReason = reason
            Log.w(TAG, "liquifyGles 场回退: $reason")
        }

        /**
         * 本帧的场可用性判定 + 世代同步。新源像素(rebase / 新手势)⇒ 场按新裁剪重建尺寸并清零。
         *
         * @return true = 本帧的呈现 pass 采样场
         */
        private fun prepareFieldForFrame(f: LiquifyGlesPreview.Frame): Boolean {
            if (!f.fieldArmed) {
                fieldGen = -1L
                return false
            }
            if (fieldUnavailable) return false
            // 世代一变就重建/清零场。**判据是世代而不是"本帧有没有源像素"** —— 新手势的第一帧
            // 未必带源像素(引擎可能已发布过、被上一帧消费), 而场必须每段手势都从 0 开始。
            if (f.cropW > 0 && f.cropH > 0 && (fieldGen != f.srcGen || !fieldReady)) {
                if (!ensureField(f)) return false
                fieldGen = f.srcGen
                fieldDabsInGesture = 0
            }
            return fieldReady && fieldGen == f.srcGen
        }

        /**
         * 按当前裁剪确定场尺寸并清零(每个源世代一次)。超预算时先提高降采样比, 仍超则回退网格
         * (见 docs/LIQUIFY-C3-FIELD-PLAN.md §2.1)。
         */
        private fun ensureField(f: LiquifyGlesPreview.Frame): Boolean {
            if (fieldTex == 0 || fieldFbo == 0) {
                giveUpField("场资源未就绪")
                return false
            }
            if (f.cropW <= 0 || f.cropH <= 0) return false
            var res = LiquifyGlesPreview.fieldRes
            var w = (f.cropW + res - 1) / res
            var h = (f.cropH + res - 1) / res
            // 位移的尾端本来是双线性, 降一半几乎看不出来 ⇒ 先降分辨率再考虑回退
            while (res < 8 && w.toLong() * h.toLong() > LiquifyGlesPreview.FIELD_MAX_PX) {
                res *= 2
                w = (f.cropW + res - 1) / res
                h = (f.cropH + res - 1) / res
            }
            if (w.toLong() * h.toLong() > LiquifyGlesPreview.FIELD_MAX_PX) {
                giveUpField("场超预算(${f.cropW}x${f.cropH})")
                return false
            }
            if (fieldW != w || fieldH != h || !fieldReady) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fieldTex)
                GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0,
                    GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
                )
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fieldFbo)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fieldTex, 0,
                )
                // 完整性必须查: 浮点纹理若不可渲染, 后面每次累加都会静默丢结果(表现为"没有形变")
                val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    giveUpField("场 FBO 不完整(0x${Integer.toHexString(status)})")
                    return false
                }
                GLES20.glViewport(0, 0, w, h)
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                fieldW = w
                fieldH = h
                fieldRes = res
                fieldReady = true
                Log.i(TAG, "liquifyGles 场 ${w}x$h 比$res 裁剪${f.cropW}x${f.cropH}")
            } else {
                clearField()
            }
            return true
        }

        /** 把场清零(= 未形变)。每个源世代一次, 成本 O(场), 不在每 dab 的热路径上。 */
        private fun clearField() {
            if (fieldFbo == 0 || fieldW <= 0 || fieldH <= 0) return
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fieldFbo)
            GLES20.glViewport(0, 0, fieldW, fieldH)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        /**
         * 把本帧的补点逐个累加进场: 每个 dab 一次**局部** draw (视口 = 它的影响半径包围盒),
         * 不重建整场、不做网格量化 —— 场本身就已经是全分辨率的浮点数据。
         *
         * 混合用 `GL_ONE / GL_ONE` 纯加性 ⇒ 着色器只输出**增量**, 于是不需要读旧场, 也就不存在
         * "同一张纹理既当采样源又当渲染目标"的反馈环(见类注释 C3 段的说明)。
         */
        private fun accumulateDabs(f: LiquifyGlesPreview.Frame) {
            val count = f.dabCount
            if (dabProgram == 0 || fieldFbo == 0 || count <= 0) return
            val dabs = f.dabs
            val res = fieldRes.toFloat()
            val originX = f.cropOriginX
            val originY = f.cropOriginY
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fieldFbo)
            // 防御性解绑: 上一帧的呈现 pass 把场纹理留在了 TEX_UNIT_FIELD 上。dab 程序没有任何
            // sampler(它不采样旧场), 规范上并不构成反馈环, 但保守驱动会因此报错 —— 解绑零成本。
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TEX_UNIT_FIELD)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glUseProgram(dabProgram)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            GLES20.glUniform2f(dabUFieldOrigin, originX, originY)
            GLES20.glUniform2f(dabUFieldStep, res, res)
            GLES20.glUniform2f(dabUFieldTexSize, fieldW.toFloat(), fieldH.toFloat())
            GLES20.glUniform4f(dabURect, fullRect[0], fullRect[1], fullRect[2], fullRect[3])
            val q = quad
            var drawn = 0
            if (q != null) {
                GLES20.glEnableVertexAttribArray(dabAPos)
                q.position(0)
                GLES20.glVertexAttribPointer(dabAPos, 2, GLES20.GL_FLOAT, false, 0, q)
                var i = 0
                while (i < count) {
                    val b = i * LiquifyGlesPreview.DAB_STRIDE
                    i++
                    val cx = dabs[b]
                    val cy = dabs[b + 1]
                    val radius = dabs[b + 6]
                    val x0 = ((cx - radius - originX) / res).toInt().coerceIn(0, fieldW)
                    val y0 = ((cy - radius - originY) / res).toInt().coerceIn(0, fieldH)
                    val x1 = (((cx + radius - originX) / res).toInt() + 1).coerceIn(0, fieldW)
                    val y1 = (((cy + radius - originY) / res).toInt() + 1).coerceIn(0, fieldH)
                    if (x1 <= x0 || y1 <= y0) continue
                    GLES20.glViewport(x0, y0, x1 - x0, y1 - y0)
                    GLES20.glUniform2f(dabUCenter, cx, cy)
                    GLES20.glUniform2f(dabUDelta, dabs[b + 2] - cx, dabs[b + 3] - cy)
                    GLES20.glUniform1f(dabURadius, radius)
                    GLES20.glUniform1f(dabUGain, dabs[b + 5])
                    GLES20.glUniform1i(dabUMode, dabs[b + 4].toInt())
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    drawn++
                }
                GLES20.glDisableVertexAttribArray(dabAPos)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            // 呈现 pass 恢复预乘 source-over(类注释第 3 条)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            fieldDabsInGesture += drawn
        }

        /**
         * 刷标尺里的"场"一格: `场 3200x2400/1 (16MB) dab=128` / `场 已回退网格(原因)` / `场 关`。
         *
         * 节流: 只在状态变化或补点数跨过 [FIELD_REPORT_DAB_STEP] 时拼字符串 —— 每帧拼字符串会
         * 让这条渲染线程无谓地产生垃圾。
         */
        private fun reportField(f: LiquifyGlesPreview.Frame, active: Boolean) {
            val state = when {
                !f.fieldArmed -> STATE_OFF
                fieldUnavailable -> STATE_FALLBACK
                else -> STATE_FIELD
            }
            if (state == reportedState &&
                (state != STATE_FIELD || fieldDabsInGesture - reportedDabs < FIELD_REPORT_DAB_STEP)
            ) {
                return
            }
            reportedState = state
            reportedDabs = fieldDabsInGesture
            val text = when (state) {
                STATE_OFF -> "场 关"
                STATE_FALLBACK -> "场 已回退网格($fieldFailReason)"
                else -> {
                    // 场的显存占用: RGBA16F 单张 + 无 ping/pong ⇒ 每纹素 8B
                    val mb = fieldW.toLong() * fieldH.toLong() * 8L / 1048576L
                    val dropped = LiquifyGlesPreview.dabsDropped
                    buildString {
                        append("场 ").append(fieldW).append('x').append(fieldH)
                        append('/').append(fieldRes).append(" (").append(mb).append("MB)")
                        append(" dab=").append(fieldDabsInGesture)
                        if (dropped > 0) append(" 丢").append(dropped)
                        if (!active) append(" 待命")
                    }
                }
            }
            PerfTrace.liquifyGlesField(text)
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
         * 呈现片元: 与 AGSL 版逐行对应(见类注释)。位移来源有两支, **只差取 `off` 那一段**:
         *  - `uUseField = 1`(C3): `off` 取自常驻浮点场 —— 全分辨率、无网格量化, 于是台阶感与
         *    窗口接缝同时消失; 场与裁剪逐像素对齐, 越界(裁剪之外)照旧输出全透明;
         *  - `uUseField = 0`(C2 网格): `uGrid` 的 `(g + 0.5) / gridSize` 是"网格坐标 → 纹素
         *    中心"的采样位置, 与 `BitmapShader.eval(g + 0.5)` 同义。
         * 其余(仿射求逆、裁剪采样、预乘输出)两支完全一致 —— A/B 时差异只会来自"场怎么来的"。
         */
        val FS = """
            precision highp float;
            uniform sampler2D uSrc;
            uniform sampler2D uGrid;
            uniform sampler2D uField;
            uniform float uUseField;
            uniform vec2 uViewSize;
            uniform vec2 uOrigin;
            uniform vec2 uEx;
            uniform vec2 uEy;
            uniform vec2 uCropOrigin;
            uniform vec2 uCropSize;
            uniform vec2 uGridOrigin;
            uniform vec2 uGridStep;
            uniform vec2 uGridSize;
            uniform vec2 uFieldOrigin;
            uniform vec2 uFieldSize;

            void main() {
                // gl_FragCoord 的 y 向上, 画布坐标 y 向下 ⇒ 翻回画布坐标
                vec2 frag = vec2(gl_FragCoord.x, uViewSize.y - gl_FragCoord.y);
                vec2 d = frag - uOrigin;
                float det = uEx.x * uEy.y - uEx.y * uEy.x;
                if (abs(det) < 1e-6) { gl_FragColor = vec4(0.0); return; }
                vec2 doc = vec2((uEy.y * d.x - uEy.x * d.y) / det,
                                (uEx.x * d.y - uEx.y * d.x) / det);
                vec2 off;
                if (uUseField > 0.5) {
                    // 场与裁剪对齐: 归一化坐标就是"场纹素中心"(v=0 两侧都是文档 top)
                    vec2 fp = (doc - uFieldOrigin) / uFieldSize;
                    if (fp.x < 0.0 || fp.y < 0.0 || fp.x > 1.0 || fp.y > 1.0) {
                        gl_FragColor = vec4(0.0);
                        return;
                    }
                    off = texture2D(uField, fp).rg;
                } else {
                    vec2 g = (doc - uGridOrigin) / uGridStep;
                    vec2 gMax = uGridSize - 1.0;
                    if (g.x < 0.0 || g.y < 0.0 || g.x > gMax.x || g.y > gMax.y) {
                        gl_FragColor = vec4(0.0);
                        return;
                    }
                    off = texture2D(uGrid, (g + 0.5) / uGridSize).rg;
                }
                gl_FragColor = texture2D(uSrc, (doc - uCropOrigin - off) / uCropSize);
            }
        """.trimIndent()

        /**
         * C3 · dab 累加片元: 往常驻场里**加**一个补点的位移增量。
         *
         * 前置条件(由提交侧保证, 见 [LiquifyGlesPreview.pushDab]):
         *  - 视口已经就设成该 dab 的影响半径包围盒 ⇒ 全视口 quad + `discard` 就是"局部累加";
         *  - 混合是 `GL_ONE / GL_ONE`, 所以这里只输出**增量**, 旧值由混合自己加上去 ——
         *    于是着色器无需读旧场, 单张纹理既当渲染目标又不构成反馈环。
         *
         * 核函数与网格路径同族(`docs/LIQUIFY-C3-FIELD-PLAN.md` §4), 衰减统一用
         * `t = 1 - smoothstep(0, 1, r / radius)`; 幅度系数与曲线在 Kotlin 侧已折进 `uDabGain`
         * (见 `LiquifyPath.fieldDabGain`), 因此"同一笔该有多大形变"与引擎口径一致。
         */
        val DAB_FS = """
            precision highp float;
            uniform vec2 uFieldOrigin;
            uniform vec2 uFieldStep;
            uniform vec2 uFieldTexSize;
            uniform vec2 uDabCenter;
            uniform vec2 uDabDelta;
            uniform float uDabRadius;
            uniform float uDabGain;
            uniform int uDabMode;

            void main() {
                // 场纹素 -> 文档坐标(与写入侧同一约定: 场 v=0 与源纹理 v=0 都是文档 top)
                vec2 doc = uFieldOrigin + gl_FragCoord.xy * uFieldStep;
                vec2 d = doc - uDabCenter;
                float r = length(d);
                float rad = max(uDabRadius, 0.5);
                if (r >= rad) discard;
                float t = 1.0 - smoothstep(0.0, 1.0, r / rad);
                if (t <= 0.0) discard;
                vec2 dir = normalize(d + vec2(1e-5, 1e-5));
                vec2 delta;
                if (uDabMode == 1) {
                    // 膨胀: 远离笔心(引擎 scalePoints 的正向), 位移随距离线性增长
                    delta = dir * (r * uDabGain * t);
                } else if (uDabMode == 2) {
                    // 收缩: 靠近笔心
                    delta = -dir * (r * uDabGain * t);
                } else if (uDabMode == 3 || uDabMode == 4) {
                    // 旋转: 文档坐标 y 向下, 正角在屏幕上看是顺时针(与工具栏图标一致)
                    float ang = (uDabMode == 3 ? 1.0 : -1.0) * uDabGain * t;
                    float c = cos(ang);
                    float s = sin(ang);
                    delta = vec2(d.x * c - d.y * s, d.x * s + d.y * c) - d;
                } else {
                    // 推拉: 位移方向与幅度 = 本 dab 的拖动向量
                    delta = uDabDelta * (uDabGain * t);
                }
                gl_FragColor = vec4(delta, 0.0, 0.0);
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

        /** 纹理单元: 0 = 源裁剪, 1 = 位移网格(C2 回退), 2 = 常驻位移场(C3)。 */
        private const val TEX_UNIT_SRC = 0
        private const val TEX_UNIT_GRID = 1
        private const val TEX_UNIT_FIELD = 2

        /** 标尺"场"一格的状态码(见 `Renderer.reportField`)。 */
        private const val STATE_UNKNOWN = -1
        private const val STATE_OFF = 0
        private const val STATE_FIELD = 1
        private const val STATE_FALLBACK = 2

        /** 场读数里补点数的刷新间隔: 每 N 个补点才重拼一次标尺文本(不在每帧热路径上拼字符串)。 */
        private const val FIELD_REPORT_DAB_STEP = 32

        /** 光栅化包围盒的余量(px): 位移会把边界外的像素拉进来一点, 与 AGSL 版一致。 */
        private const val QUAD_MARGIN_PX = 8f

        /** 无新状态时的最长等待(ms): 只为让诊断日志有节奏, 与渲染无关。 */
        private const val WAIT_MS = 250L
    }
}
