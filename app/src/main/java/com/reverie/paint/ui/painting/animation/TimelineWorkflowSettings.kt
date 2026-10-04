/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationSetManualKeyframes
import com.reverie.paint.core.animationSetPreviousFrameReference
import com.reverie.paint.core.previousFrameReference
import com.reverie.paint.ui.components.ReSectionTitle
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun TimelineWorkflowSettings(vm: PaintViewModel) {
    ReSectionTitle(
        text = stringResource(R.string.anim_drawing_workflow),
        modifier = Modifier.padding(start = 12.dp),
    )
    CompactSettingRow(label = stringResource(R.string.anim_manual_keyframes)) {
        ReSwitch(
            checked = vm.anim.manualKeyframes,
            onChecked = { vm.animationSetManualKeyframes(it) },
        )
    }
    Text(
        text = stringResource(
            if (vm.anim.manualKeyframes) R.string.anim_manual_keyframes_hint
            else R.string.anim_auto_keyframes_hint,
        ),
        color = Morandi.subText,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
    if (vm.anim.manualKeyframes) {
        CompactSettingRow(label = stringResource(R.string.anim_previous_frame_reference)) {
            ReSwitch(
                checked = vm.anim.previousFrameReference,
                onChecked = { vm.animationSetPreviousFrameReference(it) },
            )
        }
        Text(
            text = stringResource(R.string.anim_previous_frame_reference_hint),
            color = Morandi.subText,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
