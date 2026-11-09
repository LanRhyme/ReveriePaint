/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint

import android.content.Intent
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reverie.paint.core.*
import com.reverie.paint.core.Page
import com.reverie.paint.ui.components.LiquidIndication
import com.reverie.paint.ui.create.CreatePage
import com.reverie.paint.ui.dialog.UpdateDialog
import com.reverie.paint.ui.home.HomePage
import com.reverie.paint.ui.painting.PaintingPage
import com.reverie.paint.ui.replay.ReplayPage

class MainActivity : ComponentActivity() {
    companion object {
        @Volatile
        var activityInstance: MainActivity? = null

        @Volatile
        var currentViewModel: PaintViewModel? = null

        const val EXTRA_ROUTE = "com.reverie.paint.EXTRA_ROUTE"
        const val EXTRA_PROJECT_PATH = "com.reverie.paint.EXTRA_PROJECT_PATH"
        const val ROUTE_CREATE = "create"
        const val ROUTE_STATS = "stats"
        const val ROUTE_OPEN_PROJECT = "open_project"

        /** 小组件/外部入口认可的工程后缀 */
        private val PROJECT_EXTENSIONS = setOf("revp", "kra")

        /** Apply (or remove) immersive mode on the UI thread. Immersive =
         *  edge-to-edge content (decor fits windows = false) plus hidden
         *  system bars (status bar + navigation bar); a swipe shows them
         *  temporarily. Optionally extends into display cutout/notch area.
         */
        fun applyImmersive(
            enable: Boolean,
            extendToCutout: Boolean = true,
        ) {
            val act = activityInstance ?: return
            act.runOnUiThread {
                val w = act.window
                val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
                val cutoutMode = if (extendToCutout) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }

                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val lp = w.attributes
                    lp.layoutInDisplayCutoutMode = cutoutMode
                    w.attributes = lp
                }
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    w.isStatusBarContrastEnforced = false
                    w.isNavigationBarContrastEnforced = false
                }

                if (enable) {
                    controller.hide(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                    )
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                    )
                    currentViewModel?.let { vm ->
                        val isDark = vm.isCurrentlyDark()
                        val colors = if (isDark) com.reverie.paint.ui.theme.MorandiDarkColors else com.reverie.paint.ui.theme.MorandiLightColors
                        applySystemBarsTheme(colors, isDark)
                    }
                }
            }
        }

        fun applySystemBarsTheme(
            colors: com.reverie.paint.ui.theme.AppColors,
            isDark: Boolean,
        ) {
            val act = activityInstance ?: return
            act.runOnUiThread {
                val w = act.window
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    w.isStatusBarContrastEnforced = false
                    w.isNavigationBarContrastEnforced = false
                }
                w.setBackgroundDrawable(ColorDrawable(colors.bg.toArgb()))
                val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }

        /**
         * Lock window display mode to the panel's maximum supported refresh rate (144Hz / 120Hz).
         * Prevents system VRR and floating-window video playback from aggressively throttling drawing down to 60Hz.
         */
        fun applyHighRefreshRate(activity: android.app.Activity) {
            val window = activity.window ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try { activity.display } catch (_: Throwable) { null }
                } else {
                    @Suppress("DEPRECATION")
                    activity.windowManager?.defaultDisplay
                }
                val modes = display?.supportedModes ?: emptyArray()
                val maxFpsMode = modes.maxByOrNull { it.refreshRate }
                if (maxFpsMode != null) {
                    val lp = window.attributes
                    val needsUpdate = lp.preferredDisplayModeId != maxFpsMode.modeId ||
                        lp.preferredRefreshRate != maxFpsMode.refreshRate
                    if (needsUpdate) {
                        lp.preferredDisplayModeId = maxFpsMode.modeId
                        lp.preferredRefreshRate = maxFpsMode.refreshRate
                        if (android.os.Build.VERSION.SDK_INT >= 34) {
                            try {
                                lp.setFrameRateBoostOnTouchEnabled(true)
                            } catch (_: Throwable) {}
                        }
                        window.attributes = lp
                    }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityInstance = this
        LanguageManager.init(this)
        applyHighRefreshRate(this)

        val vm = ViewModelProvider(this)[PaintViewModel::class.java]
        currentViewModel = vm
        val initialIsDark = vm.isCurrentlyDark()
        enableEdgeToEdge(
            statusBarStyle = if (initialIsDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                             else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (initialIsDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                                 else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        // Give Qt's Android layer a live Activity reference (see
        // ReverieCoreBridge.initQtAndroid) so KF6I18n's context() calls
        // don't crash with a NULL jclass.
        com.reverie.paint.core.ReverieCoreBridge
            .syncActivity(this)
        // Load the native engine AFTER the activity is registered so Qt's
        // C++ initJNI caches a live activity in g_jActivity. Doing this in
        // a class-init block would cache null and KF6I18n would crash.
        com.reverie.paint.core.ReverieCoreBridge
            .ensureLoaded()
        // 原生崩溃快照里要带上"当前页面/工具/画布", 这里补一次刷新:
        // 冷启动后几秒内崩溃时, 光靠 Breadcrumbs 触发可能还没发生过任何用户操作
        com.reverie.paint.core.NativeCrashContext.requestRefresh()
        vm.appContext = applicationContext
        vm.recordLaunch()
        vm.initPerformanceHintSession()
        vm.syncSettingsFromPrefs()
        // 提前在 onCreate 启动笔刷加载，与 Compose UI 挂载和首帧渲染并发执行，彻底消除冷启动进入画布时笔刷面板的延迟与白屏
        vm.loadBrushPresets()
        val initialColors = if (initialIsDark) com.reverie.paint.ui.theme.MorandiDarkColors else com.reverie.paint.ui.theme.MorandiLightColors
        applySystemBarsTheme(initialColors, initialIsDark)
        val initialImmerse = (vm.currentPage == Page.PAINTING)
        applyImmersive(initialImmerse, vm.extendToCutout)
        setContent {
            val vm: PaintViewModel = viewModel()
            currentViewModel = vm
            val theme = com.reverie.paint.ui.theme.Theme.current
            val isDark = vm.isCurrentlyDark()
            androidx.compose.runtime.LaunchedEffect(theme, isDark) {
                applySystemBarsTheme(theme, isDark)
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                vm.appContext = applicationContext
                // 内存压力回调: 系统在 OOM 之前主动丢可重建的缓存 (回放帧缓存/
                // 图层缩略图), 而不是让进程被杀
                vm.registerMemoryPressureCallbacks()
                // 性能打点默认关闭, 只有显式 setprop debug.reverie.perf 1 才打开
                com.reverie.paint.core.PerfTrace.refreshFromSystemProp()
                vm.getOrCreateStylusDriver(applicationContext)
                vm.updateColorPickerMode(
                    applicationContext
                        .getSharedPreferences(
                            "paint_prefs",
                            android.content.Context.MODE_PRIVATE,
                        ).getString("colorPickerMode", "SQUARE")
                        ?: "SQUARE",
                )
                // Restore all persisted settings (accent color, opacities, immersive, cutout)
                vm.syncSettingsFromPrefs()
                vm.refreshProjects()
                vm.loadBrushPresets()
                handleIncomingIntent(intent)
            }
            val appPage = vm.currentPage
            val shouldImmerse = (appPage == Page.PAINTING)
            androidx.compose.runtime.LaunchedEffect(shouldImmerse, vm.extendToCutout) {
                applyImmersive(shouldImmerse, vm.extendToCutout)
            }
            // 页面切换时刷新手写笔音效门控: 只有绘画页 + 前台才保持 AudioTrack
            // 预热, 其余页面挂起, 避免常驻静音输出被系统判为"播放媒体"而耗电
            androidx.compose.runtime.LaunchedEffect(appPage) {
                com.reverie.paint.core.Breadcrumbs.record("Navigation", "Current page: $appPage")
                vm.refreshStylusAudioGate()
            }
            ReverieApp(vm)
        }
        setupDragAndDrop()
    }

    /**
     * 桌面小组件/快捷方式直达某份工程。
     *
     * 小组件缓存下来的路径随时可能失效: 工程被删/改名, 快照轮转掉, 或者旧版本把工程目录里
     * 的 .png 也当成"最近画作"。以前这里把拿到的文件直接丢给 [PaintViewModel.loadProject],
     * 读不成工程就落进 loadProject 的失败分支, 弹一条"导入失败: 未能识别的文件格式"——
     * 用户只是点了下桌面小组件, 却收到一句莫名其妙的导入失败 (issue: 从小组件打开软件弹导入失败)。
     *
     * 现在: 解析全在 IO 线程 (以前在主线程读整包 zip, 大工程会卡一下), 候选路径必须通过
     * "后缀 + zip 里有 meta.json" 双校验才会真的打开; 校验不过就退到最近一份合法工程,
     * 再不行就安静地留在画廊 —— 点小组件的语义是"打开软件", 不该因为某份工程坏了报导入错误。
     */
    private fun openProjectFromShortcut(rawPath: String?) {
        val vm = currentViewModel ?: return
        val helper = com.reverie.paint.ui.widget.RecentProjectWidgetHelper
        CoroutineScope(Dispatchers.IO).launch {
            val proj = shortcutProject(helper, rawPath) ?: shortcutProject(helper, null)
            withContext(Dispatchers.Main) {
                if (proj != null) vm.loadProject(proj)
            }
        }
    }

    /** [rawPath] 为 null 表示"最近一份工程"; 非工程文件/坏档一律返回 null (调用方静默降级) */
    private fun shortcutProject(
        helper: com.reverie.paint.ui.widget.RecentProjectWidgetHelper,
        rawPath: String?,
    ): com.reverie.paint.model.Project? {
        val file = if (rawPath.isNullOrBlank()) {
            helper.getLatestProjectDisplayData(this)?.filePath?.let { java.io.File(it) }
        } else {
            java.io.File(rawPath)
        } ?: return null
        if (!isLoadableProject(file)) return null
        return helper.getProjectFromFile(this, file)
    }

    /** 后缀必须是工程格式, 且 zip 里真的有 meta.json —— 只读中央目录, 不解大对象 */
    private fun isLoadableProject(file: java.io.File): Boolean {
        if (!file.isFile) return false
        if (file.extension.lowercase() !in PROJECT_EXTENSIONS) return false
        return try {
            java.util.zip.ZipFile(file).use { it.getEntry("meta.json") != null }
        } catch (_: Throwable) {
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val route = intent.getStringExtra(EXTRA_ROUTE)
        if (route == ROUTE_CREATE) {
            currentViewModel?.let { vm ->
                vm.currentPage = Page.CREATE
            }
        } else if (route == ROUTE_STATS) {
            currentViewModel?.let { vm ->
                vm.currentPage = Page.HOME
                vm.homeSelectedTab = 1
                vm.settingsInitialSubPage = "STATISTICS"
            }
        } else if (route == ROUTE_OPEN_PROJECT) {
            openProjectFromShortcut(intent.getStringExtra(EXTRA_PROJECT_PATH))
        }

        val action = intent.action ?: return
        // 带路由的 intent 只负责跳页面 (小组件导航), 它身上的 data 是 Glance 的导航标记,
        // 不能进导入流程
        if (route != null) return
        val uris = mutableListOf<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val targetUri = streamUri ?: intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (targetUri != null) {
                    uris.add(targetUri)
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                    if (!text.isNullOrBlank() && (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true))) {
                        try {
                            uris.add(Uri.parse(text))
                        } catch (_: Exception) {}
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val streamUris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!streamUris.isNullOrEmpty()) {
                    uris.addAll(streamUris)
                } else {
                    val clipData = intent.clipData
                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i).uri?.let { uris.add(it) }
                        }
                    }
                }
            }

            Intent.ACTION_VIEW -> {
                val viewUri = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                // 只接受真正读得出来的来源。桌面小组件走 Glance 的 actionStartActivity, 系统侧
                // 看到的是 dat=glance-action:/CALLBACK?appWidgetId=...&viewSize=... —— 这串 URI
                // 根本没有 provider, 以前它被当成"用户要导入的文件", 于是每点一次小组件就弹一条
                // "导入失败: 未能识别的文件格式" (issue: 从桌面小组件打开软件弹导入失败)。
                if (viewUri != null && com.reverie.paint.model.IncomingUriPolicy.isImportable(viewUri.scheme)) {
                    uris.add(viewUri)
                }
            }
        }

        if (uris.isNotEmpty()) {
            currentViewModel?.handleIncomingUris(uris, this)
        }
    }

    private fun setupDragAndDrop() {
        window.decorView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    event.clipDescription != null
                }

                DragEvent.ACTION_DRAG_ENTERED,
                DragEvent.ACTION_DRAG_LOCATION -> {
                    currentViewModel?.let { vm ->
                        if (!vm.isDraggingExternal) vm.isDraggingExternal = true
                    }
                    true
                }

                DragEvent.ACTION_DRAG_EXITED -> {
                    currentViewModel?.isDraggingExternal = false
                    true
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    currentViewModel?.isDraggingExternal = false
                    true
                }

                DragEvent.ACTION_DROP -> {
                    currentViewModel?.isDraggingExternal = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        requestDragAndDropPermissions(event)
                    }
                    val clipData = event.clipData
                    val uris = mutableListOf<Uri>()
                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            val item = clipData.getItemAt(i)
                            val itemUri = item.uri
                            if (itemUri != null) {
                                uris.add(itemUri)
                            } else {
                                val text = item.text?.toString()?.trim()
                                if (!text.isNullOrBlank() && (text.startsWith("http://", ignoreCase = true) ||
                                        text.startsWith("https://", ignoreCase = true) ||
                                        text.startsWith("file://", ignoreCase = true) ||
                                        text.startsWith("content://", ignoreCase = true))) {
                                    try {
                                        uris.add(Uri.parse(text))
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                    if (uris.isNotEmpty()) {
                        currentViewModel?.handleIncomingUris(uris, this)
                        true
                    } else {
                        false
                    }
                }

                else -> true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyHighRefreshRate(this)
        com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.applyHighRefreshRateAndUnbuffered()
        val vm = currentViewModel
        if (vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(this)
            driver.onActivityResume(this)
            val shouldImmerse = (vm.currentPage == Page.PAINTING)
            applyImmersive(shouldImmerse, vm.extendToCutout)
        }
    }

    override fun onPause() {
        super.onPause()
        val vm = currentViewModel
        if (vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(this)
            driver.onActivityPause(this)
        }
    }

    override fun onStart() {
        super.onStart()
        val vm = currentViewModel
        // 门控用 STARTED 而非 RESUMED: 分屏/悬浮窗失焦时仍算前台, 绘画音效
        // 必须保持可用; 只有真正切到后台才挂起音效管线
        vm?.refreshStylusAudioGate(true)
        vm?.onAppForegrounded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyHighRefreshRate(this)
            com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.applyHighRefreshRateAndUnbuffered()
            currentViewModel?.let { vm ->
                val shouldImmerse = (vm.currentPage == Page.PAINTING)
                applyImmersive(shouldImmerse, vm.extendToCutout)
            }
        }
        val vm = currentViewModel
        if (vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(this)
            driver.onWindowFocusChanged(this, hasFocus)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyHighRefreshRate(this)
        com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.applyHighRefreshRateAndUnbuffered()
        currentViewModel?.let { vm ->
            val shouldImmerse = (vm.currentPage == Page.PAINTING)
            applyImmersive(shouldImmerse, vm.extendToCutout)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        currentViewModel?.let { vm ->
            val shouldImmerse = (vm.currentPage == Page.PAINTING)
            applyImmersive(shouldImmerse, vm.extendToCutout)
            val dm = resources.displayMetrics
            val density = dm.density
            val screenW = if (newConfig.screenWidthDp > 0) newConfig.screenWidthDp * density else dm.widthPixels.toFloat()
            val screenH = if (newConfig.screenHeightDp > 0) newConfig.screenHeightDp * density else dm.heightPixels.toFloat()
            val isLand = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
                (newConfig.orientation == android.content.res.Configuration.ORIENTATION_UNDEFINED && screenW > screenH)
            vm.handleOrientationChange(
                newIsLandscape = isLand,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                density = density,
            )
        }
    }

    private var lastGenericMotionButtonState: Int = 0

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        val vm = currentViewModel
        if (vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(this)
            if (driver.onGenericMotionEvent(ev)) {
                return true
            }
            val btn = ev.buttonState
            val action = ev.actionMasked
            val isButtonAction = action == android.view.MotionEvent.ACTION_BUTTON_PRESS ||
                    action == android.view.MotionEvent.ACTION_BUTTON_RELEASE
            if (btn != 0 || lastGenericMotionButtonState != 0 || isButtonAction) {
                lastGenericMotionButtonState = btn
                if (driver.onStylusMotionEvent(ev)) {
                    return true
                }
            }
            lastGenericMotionButtonState = btn
        }

        val touchView = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        if (touchView != null && touchView.isAttachedToWindow) {
            try {
                val action = ev.actionMasked
                if (action == android.view.MotionEvent.ACTION_HOVER_MOVE ||
                    action == android.view.MotionEvent.ACTION_HOVER_ENTER ||
                    action == android.view.MotionEvent.ACTION_HOVER_EXIT) {

                    val loc = IntArray(2)
                    touchView.getLocationOnScreen(loc)
                    val localX = ev.rawX - loc[0]
                    val localY = ev.rawY - loc[1]
                    touchView.onDirectHover(localX, localY, action)

                    // 核心防护：当手指正在双指缩放/旋转或触控作画时，在 Activity 顶层直接消费掉悬停事件，
                    // 阻止 ViewGroup 默认下发 ACTION_CANCEL 杀掉多指触控手势流！
                    if (touchView.isInteracting || touchView.isTransformActive) {
                        return true
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "Error handling hover event", t)
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val vm = currentViewModel
        if (vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(this)
            // 录制快捷键状态下放行全部硬件按键 (含数位板/手写笔侧键)，使按键录制对话框能正常捕获
            if (!vm.isShortcutRecordingActive && driver.onStylusKeyEvent(event)) {
                return true
            }
            // 三星 S Pen 隔空手势: 系统注入的单键 (AT/POUND/F7..F12) KeyEvent
            if (vm.currentPage == com.reverie.paint.core.Page.PAINTING &&
                !vm.isTextInputActive && !vm.isShortcutRecordingActive
            ) {
                val strokeActive = com.reverie.paint.ui.painting.canvas.CanvasTouchView
                    .activeTouchView?.let { it.isAttachedToWindow && it.isInteracting } == true
                if (vm.handleSpenAirKeyEvent(event, strokeActive)) {
                    return true
                }
            }
            if (vm.currentPage == com.reverie.paint.core.Page.PAINTING) {
                // 处于文本编辑/重命名对话框或快捷键录制状态时跳过全局快捷键拦截，确保输入与录制正常工作
                if (!vm.isTextInputActive && !vm.isShortcutRecordingActive && vm.handleNativeKeyEvent(event)) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // 切后台立即挂起纸张音效管线: 常驻的 USAGE_MEDIA 静音输出会被系统
        // 判为"应用在后台静音播放媒体", 触发耗电异常告警
        currentViewModel?.refreshStylusAudioGate(false)
        // 当软件切入后台时，自动触发后台保存
        currentViewModel?.onAppBackgrounded()
        // 切后台时进程还没被 cgroup freezer 冻住, 这是崩溃上报"延迟定时器可能永不触发"的兜底
        com.reverie.paint.core.telemetry.TelemetryReporter.requestScanNow(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activityInstance == this) {
            activityInstance = null
            currentViewModel = null
        }
    }
}

@Composable
fun ReverieApp(vm: PaintViewModel = viewModel()) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (UpdateManager.isAutoCheckEnabled(context)) {
            UpdateManager.checkForUpdates(context, isManual = false)
        }
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                vm.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    // Global indication override: every clickable / M3 component that did not
    // explicitly opt out renders a touch-point light glow (LiquidIndication)
    // instead of the Material ripple. See docs spec 2026-08-25.
    androidx.compose.animation.AnimatedContent(
        targetState = vm.currentPage,
        transitionSpec = {
            if (targetState == Page.PAINTING) {
                // 进入画布只做一次短淡入, 不再 scaleIn(0.88f): 对含 setZOrderOnTop(true) 的
                // FrontBufferPreviewOverlay (SurfaceView + CanvasFrontBufferedRenderer) 的父容器做
                // 弹簧缩放时, 首笔前缓冲 HardwareBuffer 按缩放中的尺寸创建, 真机上观察到首笔笔迹
                // 折行裂成几块 (抬笔 commit() 重建缓冲后恢复)。
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ).togetherWith(
                    androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core
                            .tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) +
                        androidx.compose.animation.scaleOut(
                            targetScale = 1.08f,
                            animationSpec =
                                androidx.compose.animation.core.tween(
                                    220,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
                        ),
                )
            } else if (initialState == Page.PAINTING) {
                // Contracting smoothly back into gallery from canvas
                (
                    androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core
                            .tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) +
                        androidx.compose.animation.scaleIn(
                            initialScale = 1.08f,
                            animationSpec =
                                androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                ),
                        )
                ).togetherWith(
                    androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core
                            .tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ),
                )
            } else {
                (
                    androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core
                            .tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) +
                        androidx.compose.animation.slideInHorizontally(
                            animationSpec =
                                androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                ),
                        ) { if (targetState == Page.CREATE) it / 3 else -it / 3 }
                ).togetherWith(
                    androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core
                            .tween(160),
                    ) +
                        androidx.compose.animation.slideOutHorizontally(
                            animationSpec =
                                androidx.compose.animation.core
                                    .tween(160),
                        ) { if (targetState == Page.CREATE) -it / 3 else it / 3 },
                )
            }
        },
        label = "AppPageTransition",
    ) { page ->
        CompositionLocalProvider(LocalIndication provides LiquidIndication) {
            when (page) {
                Page.HOME -> {
                    HomePage(vm)
                }

                Page.CREATE -> {
                    CreatePage(vm)
                }

                Page.PAINTING -> {
                    PaintingPage(vm)
                }

                Page.REPLAY -> {
                    ReplayPage(vm)
                }
            }
        }
    }

    val availableUpdate = UpdateManager.availableUpdate
    if (UpdateManager.showUpdateDialog && availableUpdate != null) {
        UpdateDialog(
            release = availableUpdate,
            onDismiss = { UpdateManager.dismissDialog() },
        )
    }

    val brushImportUris = vm.pendingExternalBrushUris
    if (!brushImportUris.isNullOrEmpty()) {
        com.reverie.paint.ui.dialog.ExternalBrushImportDialog(
            uris = brushImportUris,
            vm = vm,
            onDismiss = { vm.pendingExternalBrushUris = null },
        )
    }

    // 崩溃诊断弹窗：检测上次未提示的崩溃日志
    var pendingCrashLog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.reverie.paint.core.CrashHandler.CrashLogInfo?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val unseen = com.reverie.paint.core.CrashHandler.getUnseenCrashLog(context)
            if (unseen != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    pendingCrashLog = unseen
                }
            }
        }
    }
    pendingCrashLog?.let { logInfo ->
        com.reverie.paint.ui.dialog.CrashReportDialog(
            logInfo = logInfo,
            onDismiss = { pendingCrashLog = null },
        )
    }

    // 异常退出恢复弹窗：检测是否存在崩溃标记与可恢复工程
    var pendingRecoveryFile by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<java.io.File?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val recoveryFile = com.reverie.paint.core.CrashHandler.checkCrashRecovery(context)
            if (recoveryFile != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    pendingRecoveryFile = recoveryFile
                }
            } else {
                com.reverie.paint.core.CrashHandler.clearCrashMarker(context)
            }
        }
    }
    pendingRecoveryFile?.let { file ->
        com.reverie.paint.ui.dialog.CrashRecoveryDialog(
            recoveryFile = file,
            onRestore = {
                val proj = vm.parseProjectFromFile(file).copy(
                    isAutoSaved = true,
                )
                vm.loadProject(proj)
                com.reverie.paint.core.CrashHandler.clearCrashMarker(context)
                pendingRecoveryFile = null
            },
            onDismiss = {
                com.reverie.paint.core.CrashHandler.clearCrashMarker(context)
                pendingRecoveryFile = null
            },
        )
    }
}
