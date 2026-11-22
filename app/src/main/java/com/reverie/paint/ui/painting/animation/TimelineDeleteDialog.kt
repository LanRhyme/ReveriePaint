/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.animation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationConfirmDeleteTrack
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun TimelineDeleteDialog(vm: PaintViewModel) {
    val request = vm.anim.deleteTrackRequest ?: return
    AlertDialog(
        onDismissRequest = { vm.anim.deleteTrackRequest = null },
        title = { Text(stringResource(R.string.anim_delete_track_title)) },
        text = { Text(stringResource(R.string.anim_delete_track_message, request.name)) },
        confirmButton = {
            TextButton(onClick = { vm.animationConfirmDeleteTrack(request) }) {
                Text(stringResource(R.string.anim_delete_track_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.anim.deleteTrackRequest = null }) {
                Text(stringResource(R.string.cancel))
            }
        },
        containerColor = Morandi.panel,
        titleContentColor = Morandi.text,
        textContentColor = Morandi.subText,
    )
}
