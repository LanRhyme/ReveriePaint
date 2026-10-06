/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi

/**
 * 「帧转图层」进度浮层。
 *
 * ## 为什么是浮层而不是塞进某一条工具栏
 *
 * 帧转图层有**两个入口**: 长按帧格的菜单、多选模式的批量栏。进度一开始
 * (2026-10-05 第一版) 只做在批量栏里, 结果从菜单启动时进度条根本不出现 ——
 * 用户看到的就只是"卡住了", 而这个功能的前身(图层转帧)真的卡死过,
 * 那种沉默是最不该重现的体验。所以做成浮层: 不管从哪进来, 进度都在同一个位置。
 *
 * ## 为什么要进度
 *
 * 拆一帧要"取画面 + 建层 + 写像素", 2800×1840 下一帧约 1 秒, 十几帧就是十几秒。
 * 十几秒不给反馈, 用户一定会以为死机。
 */
@Composable
internal fun FramesToLayersProgressOverlay(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val total = vm.anim.framesToLayersTotal
    AnimatedVisibility(
        visible = total > 0,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val doneNow = vm.anim.framesToLayersDone
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panel.copy(alpha = 0.94f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.f2l_progress, doneNow, total),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${if (total > 0) (doneNow * 100 / total) else 0}%",
                        color = Morandi.accent,
                        fontSize = 11.sp,
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) doneNow.toFloat() / total else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp)),
                    color = Morandi.accent,
                    trackColor = Morandi.panelHi,
                )
            }
        }
    }
}
