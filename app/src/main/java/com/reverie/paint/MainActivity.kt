package com.reverie.paint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reverie.paint.core.Page
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.create.CreatePage
import com.reverie.paint.ui.home.HomePage
import com.reverie.paint.ui.painting.PaintingPage

class MainActivity : ComponentActivity() {
    companion object {
        @Volatile
        var activityInstance: MainActivity? = null

        /** Apply (or remove) immersive mode on the UI thread. Immersive =
         *  edge-to-edge content (decor fits windows = false) plus hidden
         *  system bars (status bar + navigation bar); a swipe shows them
         *  temporarily. Optionally extends into display cutout/notch area.
         */
        fun applyImmersive(enable: Boolean, extendToCutout: Boolean = true) {
            val act = activityInstance ?: return
            act.runOnUiThread {
                val w = act.window
                val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
                if (enable) {
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val lp = w.attributes
                        lp.layoutInDisplayCutoutMode = if (extendToCutout) {
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        } else {
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                        }
                        w.attributes = lp
                    }
                } else {
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
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
        com.reverie.paint.core.ReverieCoreBridge.syncActivity(this)
        // Load the native engine AFTER the activity is registered so Qt's
        // C++ initJNI caches a live activity in g_jActivity. Doing this in
        // a class-init block would cache null and KF6I18n would crash.
        com.reverie.paint.core.ReverieCoreBridge.ensureLoaded()
        setContent {
            val vm: PaintViewModel = viewModel()
            vm.appContext = applicationContext
            vm.updateColorPickerMode(applicationContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).getString("colorPickerMode", "SQUARE") ?: "SQUARE")
            // Restore all persisted settings (accent color, opacities, immersive, cutout)
            vm.syncSettingsFromPrefs()
            applyImmersive(vm.immersiveMode, vm.extendToCutout)
            vm.refreshProjects()
            vm.loadBrushPresets()
            ReverieApp(vm)
        }
    }
}

@Composable
fun ReverieApp(vm: PaintViewModel = viewModel()) {
    androidx.compose.animation.AnimatedContent(
        targetState = vm.currentPage,
        transitionSpec = {
            if (targetState == Page.PAINTING || initialState == Page.PAINTING) {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                        androidx.compose.animation.scaleIn(initialScale = 0.96f, animationSpec = androidx.compose.animation.core.tween(240)))
                    .togetherWith(
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
                                androidx.compose.animation.scaleOut(targetScale = 1.02f, animationSpec = androidx.compose.animation.core.tween(180))
                    )
            } else {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                        androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(200)) { if (targetState == Page.CREATE) it / 3 else -it / 3 })
                    .togetherWith(
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                                androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(160)) { if (targetState == Page.CREATE) -it / 3 else it / 3 }
                    )
            }
        },
        label = "AppPageTransition"
    ) { page ->
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
        }
    }
}
