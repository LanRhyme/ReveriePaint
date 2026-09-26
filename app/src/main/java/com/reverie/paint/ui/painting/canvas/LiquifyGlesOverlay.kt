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
import android.util.Log
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Phase 5 · C1: 液化交互层的**自持 GLES 覆盖层**。
 *
 * ### 为什么是 TextureView 而不是 SurfaceView(C1 实测结论)
 * 最初用 `SurfaceView + setZOrderMediaOverlay(true)`, 真机**进画布即黑屏、连性能标尺都看不见** ——
 * 因为 SurfaceView 是**独立 surface 层**:它要么在 window 之下, 要么在最上;无法"夹在
 * HWUI 位图之上、Compose 覆盖层之下"。同窗口内做夹层只能用**普通 View**。
 * `TextureView` 是普通 View(参与同一 view 层级), z-order 天然按组合顺序 ⇒ 可以正确夹层,
 * 同时仍能把 GLES 渲染结果挂到它的 `SurfaceTexture` 上。
 *
 * ### 本阶段(C1)只做一件事
 * 画一张半透明方块, 用来验证: ① 画布能透过它; ② 它不吞手势; ③ 它不遮覆盖层/面板。
 * **不做**任何液化逻辑(见 docs/LIQUIFY-PHASE5-GLES-PLAN.md 的 C2~C5)。
 *
 * 开关(**默认关**): `setprop debug.reverie.liquifyGles 1`, 或构建期档位 `-PlqTestProfile=4`。
 * 任何一环失败(EGL/着色器)都会把自己设为 `GONE`, 保证**永远不会**让画板不可用。
 */
internal class LiquifyGlesOverlay(context: Context) :
    TextureView(context), TextureView.SurfaceTextureListener {

    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    private val egl = EglCore()

    init {
        // 不透明=false: 需要 alpha 参与合成(否则"透明"会被合成为黑)
        isOpaque = false
        // 不接触摸: 手势仍由 CanvasTouchView 统一处理
        isClickable = false
        isFocusable = false
        // C1 阶段半透明, 便于目视确认"画布能透过来"
        alpha = 0.6f
        surfaceTextureListener = this
    }

    // ---------------- TextureView.SurfaceTextureListener ----------------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        egl.viewportW = w
        egl.viewportH = h
        running = true
        renderThread = Thread({ renderLoop(st) }, "ReverieLiquifyGles").apply { start() }
        Log.i(TAG, "surfaceTextureAvailable ${w}x$h")
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        egl.viewportW = w
        egl.viewportH = h
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
        renderThread?.join(1000)
        renderThread = null
    }

    /** 任何失败都自我隐藏 —— 绝不允许"覆盖层把画板变黑"。 */
    private fun bailOut(reason: String) {
        Log.w(TAG, "liquifyGles 关闭: $reason")
        running = false
        post { visibility = GONE }
    }

    // ---------------- 渲染循环 ----------------

    private fun renderLoop(st: SurfaceTexture) {
        if (!egl.init(st)) {
            bailOut("EGL 初始化失败")
            return
        }
        val vertex = ShaderCache.compile(GLES20.GL_VERTEX_SHADER, ShaderCache.VS, "vs")
        val fragment = ShaderCache.compile(GLES20.GL_FRAGMENT_SHADER, ShaderCache.FS, "fs")
        if (vertex == 0 || fragment == 0) {
            egl.release()
            bailOut("着色器编译失败")
            return
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val link = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            Log.w(TAG, "link 失败: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            egl.release()
            bailOut("program link 失败")
            return
        }
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val uColor = GLES20.glGetUniformLocation(program, "uColor")
        val buf = ShaderCache.quadBuffer()

        var frames = 0
        var lastLog = android.os.SystemClock.elapsedRealtime()
        while (running) {
            if (!egl.makeCurrent()) {
                bailOut("eglMakeCurrent 失败")
                break
            }
            GLES20.glViewport(0, 0, egl.viewportW, egl.viewportH)
            // 全透明清屏: 未绘制处必须让画布透过来(这也是 C1 的判据之一)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            buf.position(0)
            // C1b 已真机确认"层级 / 透明 / 不吞手势"三件事成立, 因此**默认不再画那块青色标定方块**
            // —— 它只是为了目视验证而存在, 却会遮挡画布内容(真机反馈)。本版覆盖层只清成全透明,
            // 对画面零影响; 真正的液化输出在 C2 接入(那时这组 aPos/uColor/buf 会被复用)。
            // 需要再次目视确认层级时, 把 SHOW_CALIBRATION_QUAD 改成 true 即可。
            if (SHOW_CALIBRATION_QUAD) {
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buf)
                GLES20.glUniform4f(uColor, 0.2f, 0.8f, 0.9f, 0.5f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPos)
            }

            if (!egl.swapBuffers()) {
                bailOut("swapBuffers 失败")
                break
            }
            frames++
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastLog >= 1000L) {
                Log.i(TAG, "liquifyGles ${frames}fx/s vp=${egl.viewportW}x${egl.viewportH}")
                frames = 0
                lastLog = now
            }
            // C1 只是静态标定块: 限速到 ~60fps, 避免空烧 GPU
            val spent = android.os.SystemClock.elapsedRealtime() - now
            if (spent < 16L) Thread.sleep(16L - spent)
        }
        GLES20.glDeleteProgram(program)
        egl.release()
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
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
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
        const val VS = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """
        const val FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """

        private var qbuf: FloatBuffer? = null

        fun quadBuffer(): FloatBuffer {
            var b = qbuf
            if (b == null) {
                val half = 0.3f
                b = ByteBuffer.allocateDirect(4 * 2 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                b.put(floatArrayOf(-half, -half, half, -half, -half, half, half, half))
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

        /** 目视标定用的青色方块: C1b 已验完, 默认关(需要时改成 true)。 */
        private const val SHOW_CALIBRATION_QUAD = false

        /**
         * 开关(**默认关**)。诊断 property 只读一次, 避免每帧反射。
         *
         * 无数据线时用构建期档位: `./gradlew :app:assembleDebug -PlqTestProfile=4`
         * (档位 4 只开这个 GLES 覆盖层, 同时让 AGSL 预览保持关闭 ⇒ 干净的隔离对照)。
         */
        val enabled: Boolean by lazy {
            try {
                if (com.reverie.paint.BuildConfig.LQ_TEST_PROFILE == 4) return@lazy true
                val cls = Class.forName("android.os.SystemProperties")
                val get = cls.getMethod("get", String::class.java, String::class.java)
                val raw = get.invoke(null, "debug.reverie.liquifyGles", "") as? String
                (raw?.trim()?.toIntOrNull() ?: 0) != 0
            } catch (_: Throwable) {
                false
            }
        }
    }
}
