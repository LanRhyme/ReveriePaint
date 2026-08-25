/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reverie.paint.core.*
import com.reverie.paint.core.Page
import com.reverie.paint.ui.components.LiquidIndication
import com.reverie.paint.ui.create.CreatePage
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
                        lp.layoutInDisplayCutoutMode =
                            if (extendToCutout) {
                                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            } else {
                                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                            }
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
                        lp.layoutInDisplayCutoutMode =
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                        w.attributes = lp
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityInstance = this
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
            vm.appContext = applicationContext
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
            applyImmersive(vm.immersiveMode, vm.extendToCutout)
            vm.refreshProjects()
            vm.loadBrushPresets()
            ReverieApp(vm)
        }
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
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
}
