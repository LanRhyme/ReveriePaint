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
        setContent {
            ReverieApp()
        }
    }
}

@Composable
fun ReverieApp(vm: PaintViewModel = viewModel()) {
    when (vm.currentPage) {
        Page.HOME -> {
            HomePage(
                vm = vm,
                onOpenProject = { p -> vm.openProject(p) },
            )
        }

        Page.CREATE -> {
            CreatePage(vm)
        }

        Page.PAINTING -> {
            PaintingPage(vm)
        }
    }
}
