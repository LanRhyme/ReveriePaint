/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationBatchDeleteSelected
import com.reverie.paint.core.animationBatchDuplicateSelected
import com.reverie.paint.core.animationBatchShiftSelected
import com.reverie.paint.core.animationClearSelectedFrames
import com.reverie.paint.core.animationInvertSelectedFrames
import com.reverie.paint.core.animationSelectAllFrames
import com.reverie.paint.core.animationSetSelectedDuration
import com.reverie.paint.core.selectedTrackIndex
import com.reverie.paint.ui.theme.Morandi

/**
 * 时间轴多选模式下的底部批量操作栏。
 *
 * 替代常态控制条展示, 提供:
 * 退出多选 / 选中帧数计数 / 一拍N (1/2/3) / 左右平移 / 批量复制 / 批量删除 / 全选 / 反选 / 清空。
 */
@Composable
internal fun TimelineBatchBar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val selectedCount = vm.anim.selectedFrames.size
    val track = vm.selectedTrackIndex()
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 退出多选
        GlyphTextButton(
            text = "退出",
            onClick = {
                vm.anim.isMultiSelectMode = false
                vm.animationClearSelectedFrames()
            },
            draw = { drawGlyphClose() },
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 选中帧数徽标
        Box(
            modifier = Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Morandi.accent.copy(alpha = 0.20f))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = selectedCount,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn())
                        .togetherWith(slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "batchCountAnim",
            ) { count ->
                Text(
                    text = "已选 $count 帧",
                    color = Morandi.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 一拍 N 快捷操作
        GlyphTextButton(
            text = "1拍1",
            onClick = { vm.animationSetSelectedDuration(track, 1) },
        )
        Spacer(modifier = Modifier.width(4.dp))
        GlyphTextButton(
            text = "1拍2",
            onClick = { vm.animationSetSelectedDuration(track, 2) },
        )
        Spacer(modifier = Modifier.width(4.dp))
        GlyphTextButton(
            text = "1拍3",
            onClick = { vm.animationSetSelectedDuration(track, 3) },
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 左右平移
        GlyphTextButton(
            text = "左移",
            onClick = { vm.animationBatchShiftSelected(track, -1) },
            draw = { drawGlyphShiftLeft() },
        )
        Spacer(modifier = Modifier.width(4.dp))
        GlyphTextButton(
            text = "右移",
            onClick = { vm.animationBatchShiftSelected(track, 1) },
            draw = { drawGlyphShiftRight() },
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 批量复制
        GlyphTextButton(
            text = "复制",
            onClick = { vm.animationBatchDuplicateSelected(track) },
            draw = { drawGlyphDuplicate() },
        )

        Spacer(modifier = Modifier.width(4.dp))

        // 批量删除
        GlyphTextButton(
            text = "删除",
            onClick = { vm.animationBatchDeleteSelected(track) },
            draw = { drawGlyphTrash() },
        )

        Spacer(modifier = Modifier.width(12.dp))

        // 全选 / 反选 / 清空
        GlyphTextButton(
            text = "全选",
            onClick = { vm.animationSelectAllFrames(track) },
        )
        Spacer(modifier = Modifier.width(4.dp))
        GlyphTextButton(
            text = "反选",
            onClick = { vm.animationInvertSelectedFrames(track) },
        )
        Spacer(modifier = Modifier.width(4.dp))
        GlyphTextButton(
            text = "清空",
            onClick = { vm.animationClearSelectedFrames() },
        )
    }
}
