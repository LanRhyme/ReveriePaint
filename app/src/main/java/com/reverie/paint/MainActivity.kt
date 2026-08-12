package com.reverie.paint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            vm.refreshProjects()
            ReverieApp(vm)
        }
    }
}

@Composable
fun ReverieApp(vm: PaintViewModel = viewModel()) {
    when (vm.currentPage) {
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
