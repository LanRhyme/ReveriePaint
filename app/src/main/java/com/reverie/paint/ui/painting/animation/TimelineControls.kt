/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationAddKeyframe
import com.reverie.paint.core.animationApplyOnionSkin
import com.reverie.paint.core.animationEndFlipPeek
import com.reverie.paint.core.animationEndTemporaryOnionSkin
import com.reverie.paint.core.animationRemoveKeyframe
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.animationStartFlipPeek
import com.reverie.paint.core.animationStartTemporaryOnionSkin
import com.reverie.paint.core.animationToggleFrameSelection
import com.reverie.paint.core.animationToggleLoop
import com.reverie.paint.core.animationTogglePlay
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 时间轴底部常态控制条。
 * 包含播放/跳转/循环/洋葱皮与翻帧比对/增删复制帧/多选切换/设置入口。
 */
@Composable
internal fun TimelineControls(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    onToggleSettings: () -> Unit = { vm.anim.toolbarExpanded = !vm.anim.toolbarExpanded },
) {
    val playing = vm.anim.isPlaying
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 上一帧 (点击跳上一帧, 长按110ms极速进入翻帧比对)
        val flipActive = vm.anim.isFlipPeeking
        val prevBg by animateColorAsState(
            targetValue = if (flipActive) Morandi.accent.copy(alpha = 0.28f)
            else Morandi.panelHi.copy(alpha = BUTTON_ALPHA),
            animationSpec = spring(stiffness = 500f),
            label = "prevBtnBg",
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(prevBg)
                .pointerInput(vm) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val up = withTimeoutOrNull(110L) {
                            waitForUpOrCancellation()
                        }
                        if (up == null) {
                            vm.animationStartFlipPeek()
                            waitForUpOrCancellation()
                            vm.animationEndFlipPeek()
                        } else {
                            vm.animationSeek((vm.anim.currentTime - 1).coerceAtLeast(0))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(16.dp)) { drawGlyphPrev() }
        }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationTogglePlay() }) {
            if (playing) drawGlyphPause() else drawGlyphPlay()
        }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationSeek(vm.anim.currentTime + 1) }) { drawGlyphNext() }
        Spacer(modifier = Modifier.width(6.dp))
        IconButtonBox(onClick = { vm.animationToggleLoop() }) {
            Icon(
                painter = painterResource(if (vm.anim.loopPlayback) R.drawable.ic_repeat_loop else R.drawable.ic_repeat_none),
                contentDescription = if (vm.anim.loopPlayback) "循环播放" else "单次播放",
                tint = if (vm.anim.loopPlayback) Morandi.accent else Morandi.subText,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))

        // 洋葱皮快捷开关 (点击常开/关, 长按临时透光)
        val onionActive = vm.anim.onionSkin || vm.anim.isTemporaryOnionSkin
        val onionBg by animateColorAsState(
            targetValue = if (onionActive) Morandi.accent.copy(alpha = 0.28f)
            else Morandi.panelHi.copy(alpha = BUTTON_ALPHA),
            animationSpec = spring(stiffness = 500f),
            label = "onionBtnBg",
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(onionBg)
                .pointerInput(vm) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val up = withTimeoutOrNull(160L) {
                            waitForUpOrCancellation()
                        }
                        if (up == null) {
                            vm.animationStartTemporaryOnionSkin()
                            waitForUpOrCancellation()
                            vm.animationEndTemporaryOnionSkin()
                        } else {
                            vm.anim.onionSkin = !vm.anim.onionSkin
                            vm.animationApplyOnionSkin()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(16.dp)) { drawGlyphOnionSkin() }
        }
        Spacer(modifier = Modifier.width(12.dp))
        GlyphTextButton(text = "新帧", onClick = { vm.animationAddKeyframe(duplicate = false) }) { drawGlyphPlus() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphTextButton(text = "复制", onClick = { vm.animationAddKeyframe(duplicate = true) }) { drawGlyphDuplicate() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphTextButton(text = "删帧", onClick = { vm.animationRemoveKeyframe() }) { drawGlyphTrash() }
        Spacer(modifier = Modifier.width(6.dp))

        // 多选模式开关
        GlyphTextButton(
            text = "多选",
            onClick = {
                vm.anim.isMultiSelectMode = true
                vm.animationToggleFrameSelection(vm.anim.currentTime)
            },
            draw = { drawGlyphSelect() },
        )

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "${vm.anim.framerate}fps · ${vm.anim.length}帧 · 第 ${vm.anim.currentTime + 1} 帧",
            color = Morandi.subText,
            fontSize = 10.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        GlyphButton(onClick = onToggleSettings) {
            if (vm.anim.toolbarExpanded) drawGlyphClose() else drawGlyphSettings()
        }
    }
}
