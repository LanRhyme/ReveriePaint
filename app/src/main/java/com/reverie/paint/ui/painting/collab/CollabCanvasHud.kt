/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.collab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.approveCollabPeer
import com.reverie.paint.core.dismissRecoverableCollabSession
import com.reverie.paint.core.reconnectCollabNow
import com.reverie.paint.core.recoverLastCollabSession
import com.reverie.paint.core.rejectCollabPeer
import com.reverie.paint.core.setCollabForceRelayEnabled
import com.reverie.paint.model.CollabConnectionPhase
import com.reverie.paint.model.CollabPublicRoom
import com.reverie.paint.model.CollabRole
import com.reverie.paint.ui.components.Dimens
import com.reverie.paint.ui.components.PanelCloseButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.glassBorder

private val HUD_STATUS_GREEN = Color(0xFF5F9E6E)
private val HUD_STATUS_AMBER = Color(0xFFC99E52)

/**
 * 画布顶部非阻塞茶绘状态浮条：
 * 1) 房主待审批横幅（不再强弹全屏窗打断运笔）
 * 2) 成员进房/重连快照同步进度条
 * 3) 断线重连提示与一键重连
 * 4) 轻量聊天冒泡条（点击可打开房间面板回复）
 */
@Composable
internal fun CollabCanvasHud(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val collab = vm.collab
    if (!collab.isActive) return
    val colors = Theme.current
    val pillShape = RoundedCornerShape(Dimens.radius)

    Column(
        modifier = modifier
            .widthIn(min = 240.dp, max = 420.dp)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val pendingReq = if (collab.role == CollabRole.HOST) collab.pendingRequests.firstOrNull() else null
        AnimatedVisibility(
            visible = pendingReq != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (pendingReq != null) {
                val extraCount = (collab.pendingRequests.size - 1).coerceAtLeast(0)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(pillShape)
                        .background(colors.panel.copy(alpha = 0.94f))
                        .glassBorder(pillShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(HUD_STATUS_AMBER),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.collab_pending_banner_title, pendingReq.name),
                            color = colors.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (extraCount > 0) {
                            Text(
                                text = stringResource(R.string.collab_pending_banner_more, extraCount),
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    ReTextButton(
                        text = stringResource(R.string.collab_approve),
                        onClick = { vm.approveCollabPeer(pendingReq.peerId) },
                        containerColor = HUD_STATUS_GREEN,
                        contentColor = Color.White,
                        fontSize = 11.sp,
                    )
                    ReTextButton(
                        text = stringResource(R.string.collab_reject),
                        onClick = { vm.rejectCollabPeer(pendingReq.peerId) },
                        textColor = colors.error,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (collab.snapshotTotalChunks > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(pillShape)
                    .background(colors.panel.copy(alpha = 0.92f))
                    .glassBorder(pillShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.collab_snapshot_syncing,
                        collab.snapshotReceivedChunks,
                        collab.snapshotTotalChunks,
                    ),
                    color = colors.text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                LinearProgressIndicator(
                    progress = {
                        (collab.snapshotReceivedChunks.toFloat() / collab.snapshotTotalChunks.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    },
                    color = colors.accent,
                    trackColor = colors.panelHi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        } else if (
            collab.role == CollabRole.PEER &&
            !collab.hasReceivedInitialSnapshot &&
            collab.phase == CollabConnectionPhase.CONNECTED
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(pillShape)
                    .background(colors.panel.copy(alpha = 0.94f))
                    .glassBorder(pillShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(HUD_STATUS_AMBER),
                )
                Text(
                    text = stringResource(R.string.collab_waiting_initial_snapshot),
                    color = colors.text,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!collab.forceRelayEnabled && !collab.networkDiag.forceRelayMode) {
                    ReTextButton(
                        text = stringResource(R.string.collab_switch_relay_btn),
                        onClick = { vm.setCollabForceRelayEnabled(true) },
                        fontSize = 11.sp,
                    )
                }
                ReTextButton(
                    text = stringResource(R.string.collab_reconnect_now),
                    onClick = { vm.reconnectCollabNow() },
                    fontSize = 11.sp,
                )
            }
        }

        if (collab.phase == CollabConnectionPhase.RECONNECTING || collab.phase == CollabConnectionPhase.ERROR) {
            val isErr = collab.phase == CollabConnectionPhase.ERROR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(pillShape)
                    .background(colors.panel.copy(alpha = 0.94f))
                    .glassBorder(pillShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isErr) colors.error else HUD_STATUS_AMBER),
                )
                Text(
                    text = collab.statusMessage ?: stringResource(R.string.collab_peer_offline_tag),
                    color = if (isErr) colors.error else colors.text,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (collab.roomCode.isNotEmpty()) {
                    ReTextButton(
                        text = stringResource(R.string.collab_reconnect_now),
                        onClick = { vm.reconnectCollabNow() },
                        fontSize = 11.sp,
                    )
                }
            }
        }

        val bannerChat = collab.latestChatBanner
        AnimatedVisibility(
            visible = bannerChat != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (bannerChat != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.panel.copy(alpha = 0.92f))
                        .glassBorder(RoundedCornerShape(16.dp))
                        .clickable { collab.showRoomSheet = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(bannerChat.badgeColorArgb)),
                    )
                    Text(
                        text = "${bannerChat.senderName}: ${bannerChat.text}",
                        color = colors.text,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 茶绘高危操作通用二次确认弹窗（解散房间、踢人、转交房主、不保存退出） */
@Composable
internal fun CollabConfirmDialog(
    title: String,
    description: String,
    confirmText: String,
    isDestructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val dialogShape = RoundedCornerShape(Dimens.radius * 1.4f)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .fillMaxWidth(0.84f)
                .clip(dialogShape)
                .background(colors.panel)
                .glassBorder(dialogShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                color = colors.subText,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    textColor = colors.subText,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                ReTextButton(
                    text = confirmText,
                    onClick = onConfirm,
                    containerColor = if (isDestructive) colors.error else colors.accent,
                    contentColor = if (isDestructive) Color.White else colors.onAccent,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 公开房间点击加密房卡片时的居中独立密码输入弹窗 */
@Composable
internal fun CollabLockedRoomPasswordDialog(
    room: CollabPublicRoom,
    busy: Boolean,
    onJoin: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val dialogShape = RoundedCornerShape(Dimens.radius * 1.4f)
    var pw by remember(room.roomCode) { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 290.dp, max = 380.dp)
                .fillMaxWidth(0.86f)
                .clip(dialogShape)
                .background(colors.panel)
                .glassBorder(dialogShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = room.title,
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PanelCloseButton(onClose = onDismiss)
            }

            CollabTextField(
                value = pw,
                onValueChange = { pw = it.take(32) },
                placeholder = stringResource(R.string.collab_password_required_hint),
                visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { pwVisible = !pwVisible },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(if (pwVisible) R.drawable.ic_eye else R.drawable.ic_eye_off),
                            contentDescription = stringResource(R.string.collab_toggle_password_vis),
                            tint = colors.subText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )

            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    textColor = colors.subText,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                ReTextButton(
                    text = stringResource(R.string.collab_join_now),
                    onClick = { onJoin(pw) },
                    primary = true,
                    enabled = !busy && pw.isNotBlank(),
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 断线/重启后检测到仍活跃的上一场茶绘会话恢复提示条 (供主页与茶绘大厅共用) */
@Composable
internal fun CollabSessionRecoveryBanner(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val session = vm.collab.recoverableSession
    if (!vm.collab.showRecoveryBanner || session == null || vm.collab.isActive) return
    val colors = Theme.current
    val shape = RoundedCornerShape(14.dp)
    val displayTitle = session.roomTitle.ifBlank { session.roomCode }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accent.copy(alpha = 0.12f))
            .glassBorder(shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cloud),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.collab_recovery_banner_title, displayTitle),
                color = colors.text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.collab_recovery_banner_code, session.roomCode),
                color = colors.subText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ReTextButton(
            text = stringResource(R.string.collab_recovery_dismiss_btn),
            onClick = { vm.dismissRecoverableCollabSession() },
            textColor = colors.subText,
            fontSize = 11.5.sp,
        )
        ReTextButton(
            text = stringResource(R.string.collab_recovery_resume_btn),
            onClick = { vm.recoverLastCollabSession() },
            primary = true,
            fontSize = 11.5.sp,
        )
    }
}
