package com.reverie.paint.ui.painting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.reverie.paint.core.PaintViewModel

/**
 * Ensures that physical hardware keyboard shortcuts (e.g. Delete, Backspace, tool keys)
 * are bypassed while a text input dialog or editing UI is active.
 */
@Composable
fun TextInputGuard(vm: PaintViewModel) {
    DisposableEffect(vm) {
        vm.pushTextInputActive()
        onDispose {
            vm.popTextInputActive()
        }
    }
}
