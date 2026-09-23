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
import androidx.activity.compose.setContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

                if (enable) {
                    androidx.core.view.WindowCompat
                        .setDecorFitsSystemWindows(w, false)
                    controller.hide(
                        androidx.core.view.WindowInsetsCompat.Type
                            .systemBars(),
                    )
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val lp = w.attributes
                        lp.layoutInDisplayCutoutMode = cutoutMode
                        w.attributes = lp
                    }
                } else {
                    androidx.core.view.WindowCompat
                        .setDecorFitsSystemWindows(w, true)
                    controller.show(
                        androidx.core.view.WindowInsetsCompat.Type
                            .systemBars(),
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val lp = w.attributes
                        lp.layoutInDisplayCutoutMode = cutoutMode
                        w.attributes = lp
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
                val barColor = colors.bg.toArgb()
                w.statusBarColor = barColor
                w.navigationBarColor = barColor
                w.setBackgroundDrawable(ColorDrawable(barColor))
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
                    lp.preferredDisplayModeId = maxFpsMode.modeId
                    lp.preferredRefreshRate = maxFpsMode.refreshRate
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        try {
                            lp.setFrameRateBoostOnTouchEnabled(true)
                        } catch (_: Throwable) {}
                    }
                    window.attributes = lp
                    try {
                        window.decorView.requestLayout()
                    } catch (_: Throwable) {}
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
            applyImmersive(vm.immersiveMode, vm.extendToCutout)
            ReverieApp(vm)
        }
        setupDragAndDrop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
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
                viewUri?.let { uris.add(it) }
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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyHighRefreshRate(this)
            com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.applyHighRefreshRateAndUnbuffered()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyHighRefreshRate(this)
        com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.applyHighRefreshRateAndUnbuffered()
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        val vm = currentViewModel
        if (vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(this)
            if (driver.onGenericMotionEvent(ev)) {
                return true
            }
        }

        val touchView = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        if (touchView != null) {
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
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val vm = currentViewModel
        if (vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(this)
            if (driver.onStylusKeyEvent(event)) {
                return true
            }
            if (vm.currentPage == com.reverie.paint.core.Page.PAINTING) {
                if (vm.handleNativeKeyEvent(event)) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // 当软件切入后台时，自动触发后台保存
        currentViewModel?.onAppBackgrounded()
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
                // Expanding smoothly into canvas from gallery
                (
                    androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core
                            .tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) +
                        androidx.compose.animation.scaleIn(
                            initialScale = 0.88f,
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
                    ) +
                        androidx.compose.animation.scaleOut(
                            targetScale = 0.88f,
                            animationSpec =
                                androidx.compose.animation.core.tween(
                                    220,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
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
}
