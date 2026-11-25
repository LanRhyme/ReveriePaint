/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.collab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.approveCollabPeer
import com.reverie.paint.core.closeCollabRoom
import com.reverie.paint.core.createCollabRoom
import com.reverie.paint.core.ensureCollabPrefsLoaded
import com.reverie.paint.core.handoffCollabHost
import com.reverie.paint.core.joinCollabRoom
import com.reverie.paint.core.kickCollabPeer
import com.reverie.paint.core.leaveCollabRoom
import com.reverie.paint.core.rejectCollabPeer
import com.reverie.paint.core.showActionToast
import com.reverie.paint.model.CollabConnectionPhase
import com.reverie.paint.model.CollabRole
import com.reverie.paint.model.CollabTopologyPolicy
import com.reverie.paint.ui.theme.Morandi

private val STATUS_GREEN = Color(0xFF5F9E6E)
private val STATUS_AMBER = Color(0xFFC99E52)

/**
 * 顶栏/主页共用的茶绘状态入口组件。
 */
@Composable
internal fun CollabStatusButton(
    vm: PaintViewModel,
    compact: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val collab = vm.collab
    val isActive = collab.isActive
    val tint = when {
        !isActive -> Morandi.icon
        collab.phase == CollabConnectionPhase.CONNECTED -> STATUS_GREEN
        collab.phase == CollabConnectionPhase.ERROR -> Morandi.error
        else -> STATUS_AMBER
    }
    val handleClick = {
        vm.ensureCollabPrefsLoaded()
        if (onClick != null) {
            onClick()
        } else if (isActive) {
            collab.showRoomSheet = true
        } else {
            collab.showLobbyDialog = true
        }
    }

    if (compact) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isActive) tint.copy(alpha = 0.16f) else Color.Transparent)
                .clickable(onClick = handleClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cloud),
                contentDescription = stringResource(R.string.collab_open_lobby),
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            if (isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(tint),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isActive) tint.copy(alpha = 0.16f) else Morandi.panelHi,
                )
                .border(
                    width = 1.dp,
                    color = if (isActive) tint.copy(alpha = 0.45f) else Morandi.border,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = handleClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
            Icon(
                painter = painterResource(R.drawable.ic_cloud),
                contentDescription = stringResource(R.string.collab_open_lobby),
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            val label = when {
                !isActive -> stringResource(R.string.collab_open_lobby)
                collab.roomCode.isNotEmpty() -> "${collab.roomCode} · ${collab.peers.size.coerceAtLeast(1)}"
                else -> stringResource(R.string.collab_status_connecting)
            }
            Text(
                text = label,
                color = Morandi.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * 创建 / 加入茶绘房间大厅弹窗。
 */
@Composable
internal fun CollabLobbyDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        vm.ensureCollabPrefsLoaded()
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Create, 1 = Join
    var nickname by remember(vm.collab.selfName) { mutableStateOf(vm.collab.selfName) }
    var roomCodeInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var requireApproval by remember { mutableStateOf(false) }
    var maxPeers by remember { mutableIntStateOf(10) }
    var signalingUrl by remember(vm.collab.signalingUrl) { mutableStateOf(vm.collab.signalingUrl) }
    var showAdvanced by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Morandi.accent,
        unfocusedBorderColor = Morandi.border,
        focusedTextColor = Morandi.text,
        unfocusedTextColor = Morandi.text,
        focusedLabelColor = Morandi.accent,
        unfocusedLabelColor = Morandi.subText,
        cursorColor = Morandi.accent,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 310.dp, max = 420.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(18.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.collab_title),
                        color = Morandi.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = stringResource(R.string.common_close),
                        tint = Morandi.icon,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 模式切换胶囊: 创建房间 / 加入房间
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panelHi)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    0 to stringResource(R.string.collab_create_room),
                    1 to stringResource(R.string.collab_join_room),
                ).forEach { (tabIdx, title) ->
                    val active = selectedTab == tabIdx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Morandi.accent else Color.Transparent)
                            .clickable { selectedTab = tabIdx }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            color = if (active) Morandi.onAccent else Morandi.subText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.take(24) },
                label = { Text(stringResource(R.string.collab_nickname)) },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (selectedTab == 1) {
                OutlinedTextField(
                    value = roomCodeInput,
                    onValueChange = { raw ->
                        roomCodeInput = CollabTopologyPolicy.normalizeRoomCode(raw).take(6)
                    },
                    label = { Text(stringResource(R.string.collab_room_code)) },
                    placeholder = { Text(stringResource(R.string.collab_room_code_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.collab_lan_discover_tip),
                    color = Morandi.subText,
                    fontSize = 11.sp,
                )
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(32) },
                label = { Text(stringResource(R.string.collab_password_optional)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (selectedTab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.collab_require_approval),
                        color = Morandi.text,
                        fontSize = 13.sp,
                    )
                    Switch(
                        checked = requireApproval,
                        onCheckedChange = { requireApproval = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Morandi.onAccent,
                            checkedTrackColor = Morandi.accent,
                        ),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.collab_max_peers),
                        color = Morandi.subText,
                        fontSize = 12.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(4, 8, 10, 16).forEach { count ->
                            val selected = maxPeers == count
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Morandi.accent else Morandi.panelHi)
                                    .border(
                                        1.dp,
                                        if (selected) Morandi.accent else Morandi.border,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { maxPeers = count }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = count.toString(),
                                    color = if (selected) Morandi.onAccent else Morandi.text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.collab_signaling_url),
                    color = Morandi.subText,
                    fontSize = 11.sp,
                )
                Icon(
                    painter = painterResource(
                        if (showAdvanced) R.drawable.ic_chevron_down else R.drawable.ic_chevron,
                    ),
                    contentDescription = null,
                    tint = Morandi.subText,
                    modifier = Modifier.size(12.dp),
                )
            }

            if (showAdvanced) {
                OutlinedTextField(
                    value = signalingUrl,
                    onValueChange = { signalingUrl = it.trim() },
                    label = { Text(stringResource(R.string.collab_signaling_url)) },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val status = vm.collab.statusMessage
            if (!status.isNullOrBlank()) {
                Text(
                    text = status,
                    color = if (vm.collab.phase == CollabConnectionPhase.ERROR) Morandi.error else Morandi.accent,
                    fontSize = 12.sp,
                )
            }

            val busy = vm.collab.phase == CollabConnectionPhase.CREATING ||
                vm.collab.phase == CollabConnectionPhase.JOINING ||
                vm.collab.phase == CollabConnectionPhase.WAITING_APPROVAL

            Button(
                onClick = {
                    if (selectedTab == 0) {
                        vm.createCollabRoom(
                            nickname = nickname,
                            password = password,
                            requireApproval = requireApproval,
                            maxPeers = maxPeers,
                            signalingUrl = signalingUrl,
                        )
                    } else {
                        vm.joinCollabRoom(
                            rawRoomCode = roomCodeInput,
                            nickname = nickname,
                            password = password,
                            signalingUrl = signalingUrl,
                        )
                    }
                },
                enabled = !busy && (selectedTab == 0 || CollabTopologyPolicy.isValidRoomCode(roomCodeInput)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morandi.accent,
                    contentColor = Morandi.onAccent,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) {
                Text(
                    text = if (selectedTab == 0) {
                        stringResource(R.string.collab_start_hosting)
                    } else {
                        stringResource(R.string.collab_join_now)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * 茶绘房间运行面板: 房间码分享、快照同步进度、房主审批与成员管理。
 */
@Composable
internal fun CollabRoomSheet(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val collab = vm.collab
    val context = LocalContext.current
    val copiedPattern = stringResource(R.string.collab_code_copied)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 310.dp, max = 440.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 580.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud),
                        contentDescription = null,
                        tint = STATUS_GREEN,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.collab_title),
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val sub = when (collab.phase) {
                            CollabConnectionPhase.CONNECTED -> stringResource(
                                R.string.collab_status_connected,
                                collab.peers.size.coerceAtLeast(1),
                            )
                            CollabConnectionPhase.WAITING_APPROVAL -> stringResource(R.string.collab_status_waiting_approval)
                            CollabConnectionPhase.ERROR -> collab.statusMessage ?: stringResource(R.string.collab_status_idle)
                            else -> stringResource(R.string.collab_status_connecting)
                        }
                        Text(text = sub, color = Morandi.subText, fontSize = 11.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = stringResource(R.string.common_close),
                        tint = Morandi.icon,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 房间码卡片
            if (collab.roomCode.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi)
                        .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.collab_room_code),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = collab.roomCode,
                            color = Morandi.text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("collab_room_code", collab.roomCode),
                            )
                            vm.showActionToast(
                                message = String.format(copiedPattern, collab.roomCode),
                                iconRes = R.drawable.ic_copy,
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            tint = Morandi.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.collab_copy_code),
                            color = Morandi.accent,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // 初入房 .revp 快照进度条
            if (collab.snapshotTotalChunks > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.collab_snapshot_syncing,
                            collab.snapshotReceivedChunks,
                            collab.snapshotTotalChunks,
                        ),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (collab.snapshotReceivedChunks.toFloat() / collab.snapshotTotalChunks.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        },
                        color = Morandi.accent,
                        trackColor = Morandi.panelHi,
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 房主待审批入房申请
                if (collab.role == CollabRole.HOST && collab.pendingRequests.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.collab_pending_requests, collab.pendingRequests.size),
                        color = Morandi.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    collab.pendingRequests.forEach { req ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.panelHi)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = req.name,
                                color = Morandi.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(STATUS_GREEN)
                                        .clickable { vm.approveCollabPeer(req.peerId) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.collab_approve),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.error.copy(alpha = 0.16f))
                                        .clickable { vm.rejectCollabPeer(req.peerId) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.collab_reject),
                                        color = Morandi.error,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                // 在线画师列表
                Text(
                    text = stringResource(R.string.collab_peers_title),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )

                collab.peers.forEach { peer ->
                    val isSelf = peer.peerId == collab.selfPeerId
                    val isHost = peer.role == CollabRole.HOST
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(peer.badgeColorArgb)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = peer.name,
                                    color = Morandi.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isHost) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Morandi.accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.collab_host_badge),
                                            color = Morandi.accent,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                            if (!isSelf) {
                                Text(
                                    text = stringResource(
                                        if (peer.connectedP2P) R.string.collab_p2p_ready else R.string.collab_p2p_pending,
                                    ),
                                    color = if (peer.connectedP2P) STATUS_GREEN else Morandi.subText,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        if (collab.role == CollabRole.HOST && !isSelf) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.accent.copy(alpha = 0.14f))
                                        .clickable { vm.handoffCollabHost(peer.peerId) }
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.collab_host_badge),
                                        color = Morandi.accent,
                                        fontSize = 10.sp,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.error.copy(alpha = 0.14f))
                                        .clickable { vm.kickCollabPeer(peer.peerId) }
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.collab_kick),
                                        color = Morandi.error,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        vm.leaveCollabRoom()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Text(
                        text = stringResource(R.string.collab_leave_room),
                        color = Morandi.text,
                        fontSize = 12.sp,
                    )
                }
                if (collab.role == CollabRole.HOST) {
                    Button(
                        onClick = {
                            vm.closeCollabRoom()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Morandi.error,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.collab_close_room),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
