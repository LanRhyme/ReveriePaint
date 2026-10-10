/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.*

/**
 * Renders modal confirmation and information dialogs for PaintingPage:
 * Exit Save, Discard Confirm, External Image Import, Toolbar Squeezed,
 * Low Storage Warning, and Brush Import Progress.
 */
@Composable
internal fun PaintingGlobalDialogs(
    vm: PaintViewModel,
    showExitSaveDialog: Boolean,
    showDiscardConfirmDialog: Boolean,
    onCloseExitSaveDialog: () -> Unit,
    onDiscardFromExitSave: () -> Unit,
    onCloseDiscardConfirmDialog: () -> Unit,
) {
    if (showExitSaveDialog) {
        val exitContext = LocalContext.current
        val savedToastMsg = stringResource(R.string.toast_project_saved)
        ExitSaveDialog(
            vm = vm,
            onDiscard = onDiscardFromExitSave,
            onSaveAndExit = {
                onCloseExitSaveDialog()
                vm.saveProject(vm.docName) {
                    Toast.makeText(exitContext, savedToastMsg, Toast.LENGTH_SHORT).show()
                    vm.goHome()
                }
            },
            onDismiss = onCloseExitSaveDialog,
        )
    }

    if (showDiscardConfirmDialog) {
        DiscardConfirmDialog(
            onDiscard = {
                onCloseDiscardConfirmDialog()
                vm.discardAndExit()
            },
            onDismiss = onCloseDiscardConfirmDialog,
        )
    }

    val droppedImageUri = vm.pendingExternalImageUri
    if (droppedImageUri != null) {
        ExternalImageImportDialog(
            uri = droppedImageUri,
            vm = vm,
            onDismiss = { vm.pendingExternalImageUri = null },
        )
    }

    if (vm.showToolbarSqueezedDialog) {
        ToolbarSqueezedDialog(
            vm = vm,
            onDismiss = { vm.showToolbarSqueezedDialog = false },
        )
    }

    if (vm.showLowStorageDialog) {
        LowStorageDialog(
            message = vm.lowStorageMessage,
            onDismiss = { vm.showLowStorageDialog = false },
        )
    }

    vm.brushImportProgress?.let { progress ->
        BrushImportProgressDialog(progress = progress)
    }
}
