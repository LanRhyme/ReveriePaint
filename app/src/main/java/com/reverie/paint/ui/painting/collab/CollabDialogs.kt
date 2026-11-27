/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.collab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.Page
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.approveCollabPeer
import com.reverie.paint.core.availableExistingProjectsForCollab
import com.reverie.paint.core.createCollabRoom
import com.reverie.paint.core.discardCollabAndExit
import com.reverie.paint.core.ensureCollabPrefsLoaded
import com.reverie.paint.core.focusViewportOnCollabPeer
import com.reverie.paint.core.handoffCollabHost
import com.reverie.paint.core.isCollabFullSaveRestricted
import com.reverie.paint.core.joinCollabRoom
import com.reverie.paint.core.kickCollabPeer
import com.reverie.paint.core.openCreateCanvasForCollab
import com.reverie.paint.core.openExistingProjectForCollab
import com.reverie.paint.core.orphanedCollabLayers
import com.reverie.paint.core.reclaimOrphanedLayers
import com.reverie.paint.core.reconnectCollabNow
import com.reverie.paint.core.refreshProjects
import com.reverie.paint.core.refreshPublicCollabRooms
import com.reverie.paint.core.rejectCollabPeer
import com.reverie.paint.core.requestEndCollabAndPromptSave
import com.reverie.paint.core.saveCollabArtwork
import com.reverie.paint.core.saveCollabBackupInPlace
import com.reverie.paint.core.sendCollabChatMessage
import com.reverie.paint.core.setCollabCursorDisplayMode
import com.reverie.paint.core.setCollabForceRelayEnabled
import com.reverie.paint.core.setCollabKcpProxyEnabled
import com.reverie.paint.core.showActionToast
import com.reverie.paint.core.updateCollabRoomSettings
import com.reverie.paint.model.CollabConnectionPhase
import com.reverie.paint.model.CollabCursorDisplayMode
import com.reverie.paint.model.CollabPeer
import com.reverie.paint.model.CollabPublicRoom
import com.reverie.paint.model.CollabRole
import com.reverie.paint.model.CollabRouteMode
import com.reverie.paint.model.CollabSavePolicy
import com.reverie.paint.model.CollabTopologyPolicy
import com.reverie.paint.model.Project
import com.reverie.paint.ui.home.rememberThumbnail
import com.reverie.paint.ui.components.Dimens
import com.reverie.paint.ui.components.LiquidSegmentedBar
import com.reverie.paint.ui.components.PanelCloseButton
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.ReChip
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSectionTitle
import com.reverie.paint.ui.components.ReSettingRow
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.ReTextInput
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.glassBorder

private val STATUS_GREEN = Color(0xFF5F9E6E)
private val STATUS_AMBER = Color(0xFFC99E52)

@Composable
internal fun CollabBetaBadge(onAccent: Boolean = false) {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (onAccent) colors.onAccent.copy(alpha = 0.20f) else colors.accent.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.collab_beta_badge),
            color = if (onAccent) colors.onAccent else colors.accent,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 顶栏/主页茶绘状态按钮。常态单机画布顶栏不再渲染此按钮 (由 TopBar 调用处判断 vm.collab.isActive)。
 */
@Composable
internal fun CollabStatusButton(
    vm: PaintViewModel,
    compact: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = Theme.current
    val collab = vm.collab
    val isActive = collab.isActive
    val hasPendingApprovals = isActive && collab.role == CollabRole.HOST && collab.pendingRequests.isNotEmpty()
    val hasRecoverable = !isActive && collab.showRecoveryBanner && collab.recoverableSession != null
    val tint = when {
        !isActive && hasRecoverable -> STATUS_AMBER
        !isActive -> colors.icon
        hasPendingApprovals -> STATUS_AMBER
        collab.phase == CollabConnectionPhase.CONNECTED -> STATUS_GREEN
        collab.phase == CollabConnectionPhase.ERROR -> colors.error
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
        Box(contentAlignment = Alignment.Center) {
            ReIconButton(
                icon = R.drawable.ic_cloud,
                desc = stringResource(R.string.collab_open_lobby),
                onTap = handleClick,
                tint = tint,
                size = 32.dp,
                iconSize = 18.dp,
            )
            if (isActive || hasRecoverable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(tint),
                )
            }
        }
    } else {
        val pillShape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .clip(pillShape)
                .background(if (isActive) tint.copy(alpha = 0.16f) else colors.panelHi)
                .border(
                    width = 1.dp,
                    color = if (isActive) tint.copy(alpha = 0.45f) else colors.border,
                    shape = pillShape,
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
                !isActive -> stringResource(R.string.collab_title)
                hasPendingApprovals -> "${collab.peers.size.coerceAtLeast(1)} · +${collab.pendingRequests.size}"
                collab.phase == CollabConnectionPhase.RECONNECTING -> stringResource(R.string.collab_peer_offline_tag)
                collab.phase == CollabConnectionPhase.CONNECTED -> "${collab.peers.size.coerceAtLeast(1)}"
                collab.phase == CollabConnectionPhase.ERROR -> stringResource(R.string.collab_status_error_short)
                else -> stringResource(R.string.collab_status_connecting)
            }
            Text(
                text = label,
                color = colors.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** 统一弹窗顶部带图标圆徽章、标题与副标题的头部栏 */
@Composable
private fun CollabDialogHeader(
    title: String,
    subtitle: String? = null,
    iconTint: Color = Theme.current.accent,
    showBetaBadge: Boolean = false,
    onClose: () -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_cloud),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showBetaBadge) {
                        CollabBetaBadge()
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.subText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        PanelCloseButton(onClose = onClose)
    }
}

/** 莫兰迪分组设置卡片容器 */
@Composable
private fun CollabSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Theme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelHi.copy(alpha = 0.72f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * 创建(跳新建画布)/加入茶绘大厅弹窗。
 */
private enum class CollabCanvasSource {
    CURRENT,
    EXISTING,
    NEW,
}

@Composable
internal fun CollabLobbyDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val res = LocalResources.current
    LaunchedEffect(Unit) {
        vm.ensureCollabPrefsLoaded()
        if (vm.projects.isEmpty()) {
            vm.refreshProjects()
        }
    }
    DisposableEffect(Unit) {
        onDispose { vm.collab.preselectedCreateProject = null }
    }

    val inPainting = vm.currentPage == Page.PAINTING && vm.docWidth > 0 && vm.docHeight > 0
    val preselected = vm.collab.preselectedCreateProject
    val existingProjects = remember(vm.projects, preselected) {
        val base = vm.availableExistingProjectsForCollab()
        if (preselected != null && base.none { it.filePath == preselected.filePath }) {
            listOf(preselected) + base
        } else {
            base
        }
    }

    var selectedTab by remember(preselected) { mutableIntStateOf(0) } // 0 = Create, 1 = Join, 2 = Online
    var canvasSource by remember(inPainting, preselected) {
        mutableStateOf(
            when {
                preselected != null -> CollabCanvasSource.EXISTING
                inPainting -> CollabCanvasSource.CURRENT
                else -> CollabCanvasSource.NEW
            },
        )
    }
    var selectedProjectPath by remember(preselected) { mutableStateOf(preselected?.filePath.orEmpty()) }
    val selectedExistingProject = remember(existingProjects, selectedProjectPath) {
        existingProjects.firstOrNull { it.filePath == selectedProjectPath } ?: existingProjects.firstOrNull()
    }
    var nickname by remember(vm.collab.selfName) { mutableStateOf(vm.collab.selfName) }
    var editingNicknameInline by remember { mutableStateOf(false) }
    var roomCodeInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var requireApproval by remember { mutableStateOf(false) }
    var maxPeers by remember { mutableIntStateOf(10) }
    var isPublic by remember { mutableStateOf(false) }
    var roomTitle by remember { mutableStateOf("") }
    var savePolicy by remember { mutableStateOf(CollabSavePolicy.ALL) }
    var pendingLockedRoom by remember { mutableStateOf<CollabPublicRoom?>(null) }
    var signalingUrl by remember(vm.collab.signalingUrl) { mutableStateOf(vm.collab.signalingUrl) }
    var turnUrl by remember(vm.collab.customTurnUrl) { mutableStateOf(vm.collab.customTurnUrl) }
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        password = ""
        passwordVisible = false
        pendingLockedRoom = null
        editingNicknameInline = false
        vm.collab.duplicateSuggestedName = null
        if (selectedTab == 2) {
            vm.refreshPublicCollabRooms(signalingUrl)
        }
    }

    val dialogShape = RoundedCornerShape(20.dp)
    val tabs = listOf(
        stringResource(R.string.collab_create_room),
        stringResource(R.string.collab_join_room),
        stringResource(R.string.collab_tab_online_rooms),
    )
    val busy = vm.collab.phase == CollabConnectionPhase.CREATING ||
        vm.collab.phase == CollabConnectionPhase.JOINING ||
        vm.collab.phase == CollabConnectionPhase.WAITING_APPROVAL

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 450.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 660.dp)
                .clip(dialogShape)
                .background(colors.panel)
                .glassBorder(dialogShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollabDialogHeader(
                title = stringResource(R.string.collab_title),
                showBetaBadge = true,
                onClose = onDismiss,
            )

            CollabSessionRecoveryBanner(vm = vm)

            LiquidSegmentedBar(
                count = tabs.size,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.fillMaxWidth().height(38.dp),
            ) { idx, isActive ->
                Text(
                    text = tabs[idx],
                    color = if (isActive) colors.onAccent else colors.subText,
                    fontSize = 12.5.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selectedTab != 2 || editingNicknameInline) {
                    CollabSectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            ReSectionTitle(stringResource(R.string.collab_nickname))
                            if (selectedTab == 2) {
                                ReTextButton(
                                    text = stringResource(R.string.common_confirm),
                                    onClick = { editingNicknameInline = false },
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        ReTextInput(
                            value = nickname,
                            onValueChange = { nickname = it.take(24) },
                            placeholder = stringResource(R.string.collab_nickname_default),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.panelHi.copy(alpha = 0.72f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.collab_nickname),
                                color = colors.subText,
                                fontSize = 11.5.sp,
                            )
                            Text(
                                text = nickname.ifBlank { stringResource(R.string.collab_nickname_default) },
                                color = colors.text,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ReTextButton(
                            text = stringResource(R.string.collab_nickname_change_btn),
                            onClick = { editingNicknameInline = true },
                            fontSize = 11.sp,
                        )
                    }
                }

                when (selectedTab) {
                    0 -> {
                        CollabCanvasSourceSection(
                            vm = vm,
                            inPainting = inPainting,
                            canvasSource = canvasSource,
                            onSourceChange = { canvasSource = it },
                            existingProjects = existingProjects,
                            selectedProject = selectedExistingProject,
                            onSelectProject = { selectedProjectPath = it.filePath },
                        )
                        CollabCreateOptionsContent(
                            vm = vm,
                            isPublic = isPublic,
                            onPublicChange = { isPublic = it },
                            roomTitle = roomTitle,
                            onTitleChange = { roomTitle = it.take(CollabTopologyPolicy.MAX_ROOM_TITLE_LENGTH) },
                            password = password,
                            onPasswordChange = { password = it.take(32) },
                            passwordVisible = passwordVisible,
                            onTogglePasswordVis = { passwordVisible = !passwordVisible },
                            requireApproval = requireApproval,
                            onRequireApprovalChange = { requireApproval = it },
                            maxPeers = maxPeers,
                            onMaxPeersChange = { maxPeers = it },
                            savePolicy = savePolicy,
                            onSavePolicyChange = { savePolicy = it },
                        )
                    }
                    1 -> {
                        CollabSectionCard {
                            ReSectionTitle(stringResource(R.string.collab_room_code))
                            ReTextInput(
                                value = roomCodeInput,
                                onValueChange = { raw ->
                                    roomCodeInput = CollabTopologyPolicy.normalizeRoomCode(raw).take(6)
                                },
                                placeholder = stringResource(R.string.collab_room_code_hint),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as? android.content.ClipboardManager
                                            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                            val extracted = CollabTopologyPolicy.extractRoomCodeFromText(clipText)
                                            if (extracted != null) {
                                                roomCodeInput = extracted
                                                vm.showActionToast(
                                                    res.getString(R.string.collab_paste_code_ok, extracted),
                                                    R.drawable.ic_paste,
                                                )
                                            } else {
                                                vm.showActionToast(R.string.collab_paste_code_empty, R.drawable.ic_paste)
                                            }
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_paste),
                                            contentDescription = stringResource(R.string.collab_paste_code),
                                            tint = colors.accent,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                            )
                            Text(
                                text = stringResource(R.string.collab_lan_discover_tip),
                                color = colors.subText,
                                fontSize = 11.sp,
                            )
                            ReTextInput(
                                value = password,
                                onValueChange = { password = it.take(32) },
                                placeholder = stringResource(R.string.collab_password_optional),
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            painter = painterResource(if (passwordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off),
                                            contentDescription = stringResource(R.string.collab_toggle_password_vis),
                                            tint = colors.subText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    else -> {
                        CollabOnlineRoomsSection(
                            rooms = vm.collab.publicRooms,
                            isLoading = vm.collab.isLoadingPublicRooms,
                            errorText = vm.collab.publicRoomsError,
                            selectedRoomCode = pendingLockedRoom?.roomCode.orEmpty(),
                            onRefresh = { vm.refreshPublicCollabRooms(signalingUrl) },
                            onSelectRoom = { room ->
                                if (!busy) {
                                    if (room.peerCount >= room.maxPeers) {
                                        vm.showActionToast(R.string.collab_err_room_full, R.drawable.ic_cloud)
                                    } else if (!room.hasPassword) {
                                        pendingLockedRoom = null
                                        vm.joinCollabRoom(
                                            rawRoomCode = room.roomCode,
                                            nickname = nickname,
                                            password = "",
                                            signalingUrl = signalingUrl,
                                            turnUrl = turnUrl,
                                        )
                                    } else {
                                        pendingLockedRoom = room
                                    }
                                }
                            },
                        )
                    }
                }

                if (selectedTab != 2) {
                    CollabAdvancedNetworkCard(
                        vm = vm,
                        showAdvanced = showAdvanced,
                        onToggleShow = { showAdvanced = !showAdvanced },
                        signalingUrl = signalingUrl,
                        onSignalingChange = { signalingUrl = it.trim() },
                        turnUrl = turnUrl,
                        onTurnChange = { turnUrl = it.trim() },
                    )
                }

                val status = vm.collab.statusMessage
                if (!status.isNullOrBlank()) {
                    Text(
                        text = status,
                        color = if (vm.collab.phase == CollabConnectionPhase.ERROR) colors.error else colors.accent,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                val suggestedName = vm.collab.duplicateSuggestedName
                if (!suggestedName.isNullOrBlank() && vm.collab.lastDuplicateRoomCode.isNotBlank() && !busy) {
                    ReTextButton(
                        text = stringResource(R.string.collab_use_suggested_name, suggestedName),
                        onClick = {
                            nickname = suggestedName
                            vm.joinCollabRoom(
                                rawRoomCode = vm.collab.lastDuplicateRoomCode,
                                nickname = suggestedName,
                                password = vm.collab.lastDuplicatePassword,
                                signalingUrl = signalingUrl,
                                turnUrl = turnUrl,
                            )
                        },
                        containerColor = colors.accent.copy(alpha = 0.16f),
                        contentColor = colors.accent,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    val sourceReady = canvasSource != CollabCanvasSource.EXISTING || selectedExistingProject != null
                    val canCreate = !busy && sourceReady && CollabTopologyPolicy.isValidPublicRoomTitle(isPublic, roomTitle)
                    val ctaText = when (canvasSource) {
                        CollabCanvasSource.CURRENT -> stringResource(R.string.collab_create_with_current_canvas)
                        CollabCanvasSource.EXISTING -> stringResource(R.string.collab_create_with_existing_canvas)
                        CollabCanvasSource.NEW -> stringResource(R.string.collab_go_create_canvas)
                    }
                    ReButton(
                        text = ctaText,
                        onClick = {
                            when (canvasSource) {
                                CollabCanvasSource.CURRENT -> {
                                    vm.createCollabRoom(
                                        nickname = nickname,
                                        password = password,
                                        requireApproval = requireApproval,
                                        maxPeers = maxPeers,
                                        signalingUrl = signalingUrl,
                                        canvasW = vm.docWidth,
                                        canvasH = vm.docHeight,
                                        isPublic = isPublic,
                                        roomTitle = roomTitle,
                                        savePolicy = savePolicy,
                                        turnUrl = turnUrl,
                                    )
                                }
                                CollabCanvasSource.EXISTING -> {
                                    selectedExistingProject?.let { proj ->
                                        vm.openExistingProjectForCollab(
                                            project = proj,
                                            nickname = nickname,
                                            password = password,
                                            requireApproval = requireApproval,
                                            maxPeers = maxPeers,
                                            signalingUrl = signalingUrl,
                                            turnUrl = turnUrl,
                                            isPublic = isPublic,
                                            roomTitle = roomTitle,
                                            savePolicy = savePolicy,
                                        )
                                    }
                                }
                                CollabCanvasSource.NEW -> {
                                    vm.openCreateCanvasForCollab(
                                        nickname = nickname,
                                        password = password,
                                        requireApproval = requireApproval,
                                        maxPeers = maxPeers,
                                        signalingUrl = signalingUrl,
                                        turnUrl = turnUrl,
                                        isPublic = isPublic,
                                        roomTitle = roomTitle,
                                        savePolicy = savePolicy,
                                    )
                                }
                            }
                        },
                        primary = true,
                        enabled = canCreate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                1 -> {
                    val canJoin = !busy && CollabTopologyPolicy.isValidRoomCode(roomCodeInput)
                    ReButton(
                        text = stringResource(R.string.collab_join_now),
                        onClick = {
                            vm.joinCollabRoom(
                                rawRoomCode = roomCodeInput,
                                nickname = nickname,
                                password = password,
                                signalingUrl = signalingUrl,
                                turnUrl = turnUrl,
                            )
                        },
                        primary = true,
                        enabled = canJoin,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Unit
            }
        }
    }

    pendingLockedRoom?.let { locked ->
        CollabLockedRoomPasswordDialog(
            room = locked,
            busy = vm.collab.phase == CollabConnectionPhase.JOINING ||
                vm.collab.phase == CollabConnectionPhase.WAITING_APPROVAL,
            onJoin = { enteredPassword ->
                pendingLockedRoom = null
                vm.joinCollabRoom(
                    rawRoomCode = locked.roomCode,
                    nickname = nickname,
                    password = enteredPassword,
                    signalingUrl = signalingUrl,
                    turnUrl = turnUrl,
                )
            },
            onDismiss = { pendingLockedRoom = null },
        )
    }
}

@Composable
private fun CollabCanvasSourceSection(
    vm: PaintViewModel,
    inPainting: Boolean,
    canvasSource: CollabCanvasSource,
    onSourceChange: (CollabCanvasSource) -> Unit,
    existingProjects: List<Project>,
    selectedProject: Project?,
    onSelectProject: (Project) -> Unit,
) {
    val colors = Theme.current
    val options = remember(inPainting) {
        if (inPainting) {
            listOf(CollabCanvasSource.CURRENT, CollabCanvasSource.EXISTING, CollabCanvasSource.NEW)
        } else {
            listOf(CollabCanvasSource.EXISTING, CollabCanvasSource.NEW)
        }
    }
    val selectedIdx = options.indexOf(canvasSource).coerceAtLeast(0)

    CollabSectionCard {
        ReSectionTitle(stringResource(R.string.collab_canvas_source_label))
        LiquidSegmentedBar(
            count = options.size,
            selectedIndex = selectedIdx,
            onSelect = { idx -> options.getOrNull(idx)?.let(onSourceChange) },
            modifier = Modifier.fillMaxWidth().height(34.dp),
        ) { idx, isActive ->
            val labelRes = when (options[idx]) {
                CollabCanvasSource.CURRENT -> R.string.collab_canvas_source_current
                CollabCanvasSource.EXISTING -> R.string.collab_canvas_source_existing
                CollabCanvasSource.NEW -> R.string.collab_canvas_source_new
            }
            Text(
                text = stringResource(labelRes),
                color = if (isActive) colors.onAccent else colors.subText,
                fontSize = 11.5.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        when (canvasSource) {
            CollabCanvasSource.CURRENT -> {
                val title = vm.docName.ifBlank { stringResource(R.string.collab_canvas_untitled) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dimens.radiusSm))
                        .background(colors.panel)
                        .border(1.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(Dimens.radiusSm))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_layers),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(
                            R.string.collab_current_canvas_info,
                            title,
                            vm.docWidth,
                            vm.docHeight,
                            vm.layers.size,
                        ),
                        color = colors.text,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CollabCanvasSource.EXISTING -> {
                if (existingProjects.isEmpty()) {
                    Text(
                        text = stringResource(R.string.collab_existing_canvas_empty),
                        color = colors.subText,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.collab_existing_canvas_pick_title),
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 188.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(existingProjects, key = { it.filePath }) { proj ->
                            CollabExistingProjectRow(
                                project = proj,
                                selected = selectedProject?.filePath == proj.filePath,
                                onClick = { onSelectProject(proj) },
                            )
                        }
                    }
                }
            }
            CollabCanvasSource.NEW -> {
                Text(
                    text = stringResource(R.string.collab_create_need_canvas),
                    color = colors.subText,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun CollabExistingProjectRow(
    project: Project,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    val thumb = rememberThumbnail(project.previewPath, project.lastModified)
    val rowShape = RoundedCornerShape(Dimens.radiusSm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (selected) colors.accent.copy(alpha = 0.13f) else colors.panel)
            .border(
                width = if (selected) 1.2.dp else 0.7.dp,
                color = if (selected) colors.accent else colors.subText.copy(alpha = 0.2f),
                shape = rowShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.panelHi)
                .border(0.6.dp, colors.subText.copy(alpha = 0.2f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = project.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = null,
                    tint = colors.subText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = project.name,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val dimsText = if (project.width > 0 && project.height > 0) {
                stringResource(
                    R.string.collab_existing_project_meta,
                    project.width,
                    project.height,
                    project.layerCount,
                )
            } else {
                "${project.layerCount}"
            }
            Text(
                text = dimsText,
                color = colors.subText,
                fontSize = 10.5.sp,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CollabCreateOptionsContent(
    vm: PaintViewModel,
    isPublic: Boolean,
    onPublicChange: (Boolean) -> Unit,
    roomTitle: String,
    onTitleChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVis: () -> Unit,
    requireApproval: Boolean,
    onRequireApprovalChange: (Boolean) -> Unit,
    maxPeers: Int,
    onMaxPeersChange: (Int) -> Unit,
    savePolicy: CollabSavePolicy,
    onSavePolicyChange: (CollabSavePolicy) -> Unit,
) {
    val colors = Theme.current

    CollabSectionCard {
        ReSettingRow(
            label = stringResource(R.string.collab_public_room_toggle),
            modifier = Modifier
                .clip(RoundedCornerShape(Dimens.radiusSm))
                .background(colors.panel),
        ) {
            ReSwitch(checked = isPublic, onChecked = onPublicChange)
        }
        ReSectionTitle(
            if (isPublic) {
                stringResource(R.string.collab_room_title_required_label)
            } else {
                stringResource(R.string.collab_room_title_optional_label)
            },
        )
        ReTextInput(
            value = roomTitle,
            onValueChange = onTitleChange,
            placeholder = stringResource(R.string.collab_room_title_hint),
        )
        if (isPublic && roomTitle.isBlank()) {
            Text(
                text = stringResource(R.string.collab_err_room_title_required),
                color = colors.error,
                fontSize = 11.sp,
            )
        }
    }

    CollabSectionCard {
        ReTextInput(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = stringResource(R.string.collab_password_optional),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVis) {
                    Icon(
                        painter = painterResource(if (passwordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off),
                        contentDescription = stringResource(R.string.collab_toggle_password_vis),
                        tint = colors.subText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        ReSettingRow(
            label = stringResource(R.string.collab_require_approval),
            modifier = Modifier
                .clip(RoundedCornerShape(Dimens.radiusSm))
                .background(colors.panel),
        ) {
            ReSwitch(checked = requireApproval, onChecked = onRequireApprovalChange)
        }
        ReSectionTitle(stringResource(R.string.collab_max_peers))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(4, 8, 10, 16).forEach { count ->
                ReChip(
                    text = count.toString(),
                    selected = maxPeers == count,
                    onTap = { onMaxPeersChange(count) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    CollabSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReSectionTitle(stringResource(R.string.collab_save_policy_label))
            CollabRoomTagPill(
                text = stringResource(R.string.collab_save_policy_immutable_hint),
                tint = colors.subText,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReChip(
                text = stringResource(R.string.collab_save_policy_all),
                selected = savePolicy == CollabSavePolicy.ALL,
                onTap = { onSavePolicyChange(CollabSavePolicy.ALL) },
                modifier = Modifier.weight(1f),
            )
            ReChip(
                text = stringResource(R.string.collab_save_policy_own_only),
                selected = savePolicy == CollabSavePolicy.OWN_ONLY,
                onTap = { onSavePolicyChange(CollabSavePolicy.OWN_ONLY) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = if (savePolicy == CollabSavePolicy.OWN_ONLY) {
                stringResource(R.string.collab_save_policy_own_desc)
            } else {
                stringResource(R.string.collab_save_policy_all_desc)
            },
            color = colors.subText,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun CollabAdvancedNetworkCard(
    vm: PaintViewModel,
    showAdvanced: Boolean,
    onToggleShow: () -> Unit,
    signalingUrl: String,
    onSignalingChange: (String) -> Unit,
    turnUrl: String,
    onTurnChange: (String) -> Unit,
) {
    val colors = Theme.current
    CollabSectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleShow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.collab_custom_turn_section),
                color = colors.subText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                painter = painterResource(if (showAdvanced) R.drawable.ic_chevron_down else R.drawable.ic_chevron),
                contentDescription = null,
                tint = colors.subText,
                modifier = Modifier.size(12.dp),
            )
        }
        if (showAdvanced) {
            ReTextInput(
                value = signalingUrl,
                onValueChange = onSignalingChange,
                placeholder = stringResource(R.string.collab_signaling_url),
            )
            ReTextInput(
                value = turnUrl,
                onValueChange = onTurnChange,
                placeholder = stringResource(R.string.collab_turn_url_hint),
            )
            ReSettingRow(
                label = stringResource(R.string.collab_kcp_proxy),
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(colors.panel),
            ) {
                ReSwitch(
                    checked = vm.collab.kcpProxyEnabled,
                    onChecked = { vm.setCollabKcpProxyEnabled(it) },
                )
            }
            Text(
                text = stringResource(R.string.collab_kcp_proxy_hint),
                color = colors.subText,
                fontSize = 11.sp,
            )
            ReSettingRow(
                label = stringResource(R.string.collab_force_relay_label),
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(colors.panel),
            ) {
                ReSwitch(
                    checked = vm.collab.forceRelayEnabled,
                    onChecked = { vm.setCollabForceRelayEnabled(it) },
                )
            }
            Text(
                text = stringResource(R.string.collab_force_relay_hint),
                color = colors.subText,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CollabOnlineRoomsSection(
    rooms: List<CollabPublicRoom>,
    isLoading: Boolean,
    errorText: String?,
    selectedRoomCode: String,
    onRefresh: () -> Unit,
    onSelectRoom: (CollabPublicRoom) -> Unit,
) {
    val colors = Theme.current
    val sectionShape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReSectionTitle(
                if (rooms.isNotEmpty()) {
                    stringResource(R.string.collab_online_rooms_count, rooms.size)
                } else {
                    stringResource(R.string.collab_online_rooms_title)
                },
            )
            ReTextButton(
                text = stringResource(R.string.collab_online_rooms_refresh),
                icon = R.drawable.ic_refresh,
                onClick = onRefresh,
                enabled = !isLoading,
                fontSize = 11.5.sp,
            )
        }
        when {
            isLoading && rooms.isEmpty() -> {
                Text(
                    text = stringResource(R.string.collab_online_rooms_loading),
                    color = colors.subText,
                    fontSize = 11.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(sectionShape)
                        .background(colors.panelHi)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            !errorText.isNullOrBlank() && rooms.isEmpty() -> {
                Text(
                    text = errorText,
                    color = colors.error,
                    fontSize = 11.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(sectionShape)
                        .background(colors.panelHi)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            rooms.isEmpty() -> {
                Text(
                    text = stringResource(R.string.collab_online_rooms_empty),
                    color = colors.subText,
                    fontSize = 11.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(sectionShape)
                        .background(colors.panelHi)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rooms.forEach { room ->
                        CollabOnlineRoomCard(
                            room = room,
                            selected = selectedRoomCode == room.roomCode,
                            onClick = { onSelectRoom(room) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollabOnlineRoomCard(
    room: CollabPublicRoom,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    val cardShape = RoundedCornerShape(12.dp)
    val isFull = room.peerCount >= room.maxPeers
    val countColor = if (isFull) colors.error else STATUS_GREEN
    val borderColor = if (selected) colors.accent else colors.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(if (selected) colors.accent.copy(alpha = 0.08f) else colors.panelHi)
            .border(width = if (selected) 1.5.dp else 1.dp, color = borderColor, shape = cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (room.hasPassword) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = stringResource(R.string.collab_public_room_locked_tag),
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = room.title,
                    color = colors.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val metaText = if (room.canvasWidth > 0 && room.canvasHeight > 0) {
                    "${stringResource(R.string.collab_public_room_host, room.hostName)} · ${room.canvasWidth}×${room.canvasHeight}"
                } else {
                    stringResource(R.string.collab_public_room_host, room.hostName)
                }
                Text(
                    text = metaText,
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (room.requireApproval) {
                    CollabRoomTagPill(
                        text = stringResource(R.string.collab_public_room_approval_tag),
                        tint = STATUS_AMBER,
                    )
                }
                if (room.savePolicy == CollabSavePolicy.OWN_ONLY) {
                    CollabRoomTagPill(
                        text = stringResource(R.string.collab_own_only_badge),
                        tint = colors.accent,
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(countColor.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "${room.peerCount}/${room.maxPeers}",
                    color = countColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            if (isFull) {
                Text(
                    text = stringResource(R.string.collab_public_room_full_tag),
                    color = colors.error,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun CollabRoomTagPill(
    text: String,
    tint: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 茶绘房间管理面板: 分「房间与成员 / 聊天 / 设置」三页，支持房主在线编辑房间公开/名称/密码/审批/人数。
 */
@Composable
internal fun CollabRoomSheet(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val collab = vm.collab
    val context = LocalContext.current
    val copiedPattern = stringResource(R.string.collab_code_copied)
    val saveRestricted = vm.isCollabFullSaveRestricted()
    val restrictedMsg = stringResource(R.string.collab_own_only_in_room_blocked)
    val dialogShape = RoundedCornerShape(20.dp)
    val cardShape = RoundedCornerShape(14.dp)

    val hasPendingP2p = collab.peers.any { it.peerId != collab.selfPeerId && !it.connectedP2P }
    val showReconnectAction = collab.phase == CollabConnectionPhase.RECONNECTING ||
        collab.phase == CollabConnectionPhase.ERROR ||
        collab.kcpNeedsReconnectHint ||
        hasPendingP2p

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Room & Peers, 1 = Chat, 2 = Settings
    var showCloseRoomConfirm by remember { mutableStateOf(false) }
    var pendingKickPeer by remember { mutableStateOf<CollabPeer?>(null) }
    var pendingHandoffPeer by remember { mutableStateOf<CollabPeer?>(null) }
    var showNetDetails by remember { mutableStateOf(false) }
    var chatDraft by remember { mutableStateOf("") }

    // 房主编辑房间设置表单态
    var editIsPublic by remember(collab.isPublicRoom, collab.roomCode) { mutableStateOf(collab.isPublicRoom) }
    var editTitle by remember(collab.roomTitle, collab.roomCode) { mutableStateOf(collab.roomTitle) }
    var editRequireApproval by remember(collab.requireApproval, collab.roomCode) { mutableStateOf(collab.requireApproval) }
    var editMaxPeers by remember(collab.maxPeers, collab.roomCode) { mutableIntStateOf(collab.maxPeers) }
    var editPwMode by remember(collab.roomHasPassword, collab.roomCode) { mutableIntStateOf(0) } // 0=keep, 1=clear, 2=set new
    var editNewPassword by remember { mutableStateOf("") }
    var editNewPwVis by remember { mutableStateOf(false) }

    val sheetTabs = listOf(
        stringResource(R.string.collab_tab_room_peers),
        stringResource(R.string.collab_tab_chat),
        stringResource(R.string.collab_tab_settings),
    )

    val statusTint = when (collab.phase) {
        CollabConnectionPhase.CONNECTED -> STATUS_GREEN
        CollabConnectionPhase.ERROR -> colors.error
        else -> STATUS_AMBER
    }
    val subStatusText = when (collab.phase) {
        CollabConnectionPhase.CONNECTED -> stringResource(
            R.string.collab_status_connected,
            collab.peers.size.coerceAtLeast(1),
        )
        CollabConnectionPhase.WAITING_APPROVAL -> stringResource(R.string.collab_status_waiting_approval)
        CollabConnectionPhase.RECONNECTING -> collab.statusMessage ?: stringResource(R.string.collab_peer_offline_tag)
        CollabConnectionPhase.ERROR -> collab.statusMessage ?: stringResource(R.string.collab_status_idle)
        else -> stringResource(R.string.collab_status_connecting)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 460.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 650.dp)
                .clip(dialogShape)
                .background(colors.panel)
                .glassBorder(dialogShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollabDialogHeader(
                title = collab.roomTitle.ifBlank { stringResource(R.string.collab_title) },
                subtitle = subStatusText,
                iconTint = statusTint,
                showBetaBadge = true,
                onClose = onDismiss,
            )

            LiquidSegmentedBar(
                count = sheetTabs.size,
                selectedIndex = activeTab,
                onSelect = { activeTab = it },
                modifier = Modifier.fillMaxWidth().height(36.dp),
            ) { idx, isActive ->
                val label = if (idx == 1 && collab.chatMessages.isNotEmpty()) {
                    "${sheetTabs[idx]} (${collab.chatMessages.size})"
                } else {
                    sheetTabs[idx]
                }
                Text(
                    text = label,
                    color = if (isActive) colors.onAccent else colors.subText,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (collab.snapshotTotalChunks > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.collab_snapshot_syncing,
                            collab.snapshotReceivedChunks,
                            collab.snapshotTotalChunks,
                        ),
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (collab.snapshotReceivedChunks.toFloat() / collab.snapshotTotalChunks.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        },
                        color = colors.accent,
                        trackColor = colors.panelHi,
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
                when (activeTab) {
                    0 -> {
                        if (collab.roomCode.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(cardShape)
                                    .background(colors.panelHi)
                                    .glassBorder(cardShape)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            CollabRoomTagPill(
                                                text = if (collab.isPublicRoom) {
                                                    stringResource(R.string.collab_public_room_badge)
                                                } else {
                                                    stringResource(R.string.collab_private_room_badge)
                                                },
                                                tint = if (collab.isPublicRoom) STATUS_GREEN else colors.subText,
                                            )
                                            if (collab.roomHasPassword) {
                                                CollabRoomTagPill(
                                                    text = stringResource(R.string.collab_public_room_locked_tag),
                                                    tint = colors.accent,
                                                )
                                            }
                                            if (collab.savePolicy == CollabSavePolicy.OWN_ONLY) {
                                                CollabRoomTagPill(
                                                    text = stringResource(R.string.collab_own_only_badge),
                                                    tint = STATUS_AMBER,
                                                )
                                            }
                                        }
                                        Text(
                                            text = collab.roomCode,
                                            color = colors.text,
                                            fontSize = 23.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 3.sp,
                                        )
                                    }
                                    if (showReconnectAction) {
                                        ReTextButton(
                                            text = stringResource(R.string.collab_reconnect_now),
                                            onClick = { vm.reconnectCollabNow() },
                                            containerColor = colors.accent.copy(alpha = 0.14f),
                                            contentColor = colors.accent,
                                            fontSize = 11.5.sp,
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ReTextButton(
                                        text = stringResource(R.string.collab_copy_code),
                                        icon = R.drawable.ic_copy,
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
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val normalizedCode = CollabTopologyPolicy.normalizeRoomCode(collab.roomCode)
                                    val pwdSuffix = if (collab.roomHasPassword) {
                                        stringResource(R.string.collab_invite_password_suffix)
                                    } else ""
                                    val cleanTitle = collab.roomTitle.trim()
                                    val inviteText = if (cleanTitle.isNotEmpty()) {
                                        stringResource(R.string.collab_invite_with_title, cleanTitle, normalizedCode, pwdSuffix)
                                    } else {
                                        stringResource(R.string.collab_invite_no_title, normalizedCode, pwdSuffix)
                                    }
                                    ReTextButton(
                                        text = stringResource(R.string.collab_copy_invite),
                                        icon = R.drawable.ic_copy,
                                        onClick = {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as? android.content.ClipboardManager
                                            cm?.setPrimaryClip(
                                                android.content.ClipData.newPlainText("collab_invite", inviteText),
                                            )
                                            vm.showActionToast(R.string.collab_invite_copied, R.drawable.ic_copy)
                                        },
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ReTextButton(
                                        text = stringResource(R.string.collab_save_backup_btn),
                                        icon = if (saveRestricted) R.drawable.ic_lock else R.drawable.ic_save,
                                        onClick = {
                                            if (saveRestricted) {
                                                vm.showActionToast(restrictedMsg, R.drawable.ic_lock)
                                            } else {
                                                vm.saveCollabBackupInPlace()
                                            }
                                        },
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .weight(1f)
                                            .alpha(if (saveRestricted) 0.48f else 1f),
                                    )
                                }
                                if (saveRestricted) {
                                    Text(
                                        text = restrictedMsg,
                                        color = colors.subText,
                                        fontSize = 10.5.sp,
                                    )
                                }
                            }
                        }

                        if (collab.role == CollabRole.HOST && collab.pendingRequests.isNotEmpty()) {
                            ReSectionTitle(stringResource(R.string.collab_pending_requests, collab.pendingRequests.size))
                            collab.pendingRequests.forEach { req ->
                                val itemShape = RoundedCornerShape(10.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(itemShape)
                                        .background(colors.panelHi)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = req.name,
                                        color = colors.text,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ReTextButton(
                                            text = stringResource(R.string.collab_approve),
                                            onClick = { vm.approveCollabPeer(req.peerId) },
                                            containerColor = STATUS_GREEN,
                                            contentColor = Color.White,
                                            fontSize = 11.sp,
                                        )
                                        ReTextButton(
                                            text = stringResource(R.string.collab_reject),
                                            onClick = { vm.rejectCollabPeer(req.peerId) },
                                            textColor = colors.error,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }

                        ReSectionTitle(stringResource(R.string.collab_peers_title))
                        collab.peers.forEach { peer ->
                            val isSelf = peer.peerId == collab.selfPeerId
                            val isHost = peer.role == CollabRole.HOST
                            val hasCursor = !isSelf && collab.remoteCursors.any { it.peerId == peer.peerId }
                            val itemShape = RoundedCornerShape(10.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(itemShape)
                                    .background(colors.panelHi)
                                    .then(
                                        if (hasCursor) {
                                            Modifier.clickable { vm.focusViewportOnCollabPeer(peer.peerId) }
                                        } else {
                                            Modifier
                                        },
                                    )
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
                                            color = colors.text,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isHost) {
                                            CollabRoomTagPill(
                                                text = stringResource(R.string.collab_host_badge),
                                                tint = colors.accent,
                                            )
                                        }
                                    }
                                    if (!isSelf) {
                                        val routeDetail = buildString {
                                            if (peer.tunnelProto.isNotEmpty()) append(peer.tunnelProto.uppercase())
                                            if (peer.latencyMs >= 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append("${peer.latencyMs}ms")
                                            }
                                        }
                                        val subLabel = when {
                                            !peer.signalingOnline && !peer.connectedP2P -> stringResource(R.string.collab_peer_offline_tag)
                                            peer.connectedP2P && peer.routeMode == CollabRouteMode.P2P_DIRECT && routeDetail.isNotEmpty() ->
                                                stringResource(R.string.collab_route_p2p, routeDetail)
                                            peer.connectedP2P && peer.routeMode == CollabRouteMode.RELAYED && routeDetail.isNotEmpty() ->
                                                stringResource(R.string.collab_route_relay, routeDetail)
                                            peer.connectedP2P -> stringResource(R.string.collab_p2p_ready)
                                            else -> stringResource(R.string.collab_p2p_pending)
                                        }
                                        val subColor = when {
                                            peer.connectedP2P && peer.routeMode == CollabRouteMode.RELAYED -> STATUS_AMBER
                                            peer.connectedP2P -> STATUS_GREEN
                                            else -> colors.subText
                                        }
                                        Text(
                                            text = subLabel,
                                            color = subColor,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                                if (hasCursor) {
                                    ReTextButton(
                                        text = stringResource(R.string.collab_locate_peer),
                                        onClick = { vm.focusViewportOnCollabPeer(peer.peerId) },
                                        fontSize = 10.5.sp,
                                    )
                                }
                                if (collab.role == CollabRole.HOST && !isSelf) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        ReTextButton(
                                            text = stringResource(R.string.collab_handoff_short),
                                            onClick = { pendingHandoffPeer = peer },
                                            fontSize = 10.5.sp,
                                        )
                                        ReTextButton(
                                            text = stringResource(R.string.collab_kick),
                                            onClick = { pendingKickPeer = peer },
                                            textColor = colors.error,
                                            fontSize = 10.5.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        ReSectionTitle(stringResource(R.string.collab_chat_section))
                        val quickPhrases = listOf(
                            stringResource(R.string.collab_quick_msg_done),
                            stringResource(R.string.collab_quick_msg_wait),
                            stringResource(R.string.collab_quick_msg_look),
                            stringResource(R.string.collab_quick_msg_save),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            quickPhrases.forEach { phrase ->
                                ReChip(
                                    text = phrase,
                                    selected = false,
                                    onTap = { vm.sendCollabChatMessage(phrase) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (collab.chatMessages.isNotEmpty()) {
                            CollabSectionCard {
                                collab.chatMessages.takeLast(12).forEach { m ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(Color(m.badgeColorArgb)),
                                        )
                                        Text(
                                            text = "${m.senderName}: ${m.text}",
                                            color = if (m.isSelf) colors.subText else colors.text,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.collab_chat_empty),
                                color = colors.subText,
                                fontSize = 11.5.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.panelHi)
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ReTextInput(
                                value = chatDraft,
                                onValueChange = { chatDraft = it.take(80) },
                                placeholder = stringResource(R.string.collab_chat_input_hint),
                                modifier = Modifier.weight(1f),
                            )
                            ReTextButton(
                                text = stringResource(R.string.collab_chat_send),
                                onClick = {
                                    if (chatDraft.isNotBlank()) {
                                        vm.sendCollabChatMessage(chatDraft)
                                        chatDraft = ""
                                    }
                                },
                                enabled = chatDraft.isNotBlank(),
                                primary = true,
                                fontSize = 11.sp,
                            )
                            ReTextButton(
                                text = stringResource(R.string.collab_ping_here),
                                onClick = {
                                    vm.collab.showRoomSheet = false
                                    vm.collab.pendingPingAtViewportCenter = true
                                },
                                containerColor = colors.accent.copy(alpha = 0.16f),
                                contentColor = colors.accent,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    else -> {
                        if (collab.role == CollabRole.HOST) {
                            CollabSectionCard {
                                ReSectionTitle(stringResource(R.string.collab_edit_room_section))
                                ReSettingRow(
                                    label = stringResource(R.string.collab_public_room_toggle),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Dimens.radiusSm))
                                        .background(colors.panel),
                                ) {
                                    ReSwitch(
                                        checked = editIsPublic,
                                        onChecked = { editIsPublic = it },
                                    )
                                }
                                ReSectionTitle(
                                    if (editIsPublic) {
                                        stringResource(R.string.collab_room_title_required_label)
                                    } else {
                                        stringResource(R.string.collab_room_title_optional_label)
                                    },
                                )
                                ReTextInput(
                                    value = editTitle,
                                    onValueChange = { editTitle = it.take(CollabTopologyPolicy.MAX_ROOM_TITLE_LENGTH) },
                                    placeholder = stringResource(R.string.collab_room_title_hint),
                                )
                                if (editIsPublic && editTitle.isBlank()) {
                                    Text(
                                        text = stringResource(R.string.collab_err_room_title_required),
                                        color = colors.error,
                                        fontSize = 11.sp,
                                    )
                                }
                                ReSettingRow(
                                    label = stringResource(R.string.collab_require_approval),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Dimens.radiusSm))
                                        .background(colors.panel),
                                ) {
                                    ReSwitch(
                                        checked = editRequireApproval,
                                        onChecked = { editRequireApproval = it },
                                    )
                                }
                                ReSectionTitle(stringResource(R.string.collab_max_peers))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    listOf(4, 8, 10, 16).forEach { count ->
                                        ReChip(
                                            text = count.toString(),
                                            selected = editMaxPeers == count,
                                            onTap = { editMaxPeers = count },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                ReSectionTitle(stringResource(R.string.collab_public_room_locked_tag))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ReChip(
                                        text = stringResource(R.string.collab_edit_password_keep),
                                        selected = editPwMode == 0,
                                        onTap = { editPwMode = 0 },
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (collab.roomHasPassword) {
                                        ReChip(
                                            text = stringResource(R.string.collab_edit_password_clear),
                                            selected = editPwMode == 1,
                                            onTap = { editPwMode = 1 },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    ReChip(
                                        text = stringResource(R.string.collab_edit_password_set),
                                        selected = editPwMode == 2,
                                        onTap = { editPwMode = 2 },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (editPwMode == 2) {
                                    ReTextInput(
                                        value = editNewPassword,
                                        onValueChange = { editNewPassword = it.take(32) },
                                        placeholder = stringResource(R.string.collab_edit_password_new_hint),
                                        visualTransformation = if (editNewPwVis) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { editNewPwVis = !editNewPwVis }) {
                                                Icon(
                                                    painter = painterResource(if (editNewPwVis) R.drawable.ic_eye else R.drawable.ic_eye_off),
                                                    contentDescription = stringResource(R.string.collab_toggle_password_vis),
                                                    tint = colors.subText,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        },
                                    )
                                }
                                // 保存权限只读展示（不可修改）
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Dimens.radiusSm))
                                        .background(colors.panel)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(R.string.collab_save_policy_label),
                                        color = colors.subText,
                                        fontSize = 11.5.sp,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_lock),
                                            contentDescription = null,
                                            tint = colors.subText,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Text(
                                            text = if (collab.savePolicy == CollabSavePolicy.OWN_ONLY) {
                                                stringResource(R.string.collab_save_policy_own_only)
                                            } else {
                                                stringResource(R.string.collab_save_policy_all)
                                            },
                                            color = colors.text,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                                val pwUpdateArg = when (editPwMode) {
                                    1 -> ""
                                    2 -> editNewPassword
                                    else -> null
                                }
                                val titleValid = CollabTopologyPolicy.isValidPublicRoomTitle(editIsPublic, editTitle)
                                val pwValid = editPwMode != 2 || editNewPassword.isNotBlank()
                                val hasChanges = editIsPublic != collab.isPublicRoom ||
                                    CollabTopologyPolicy.sanitizeRoomTitle(editTitle) != collab.roomTitle ||
                                    editRequireApproval != collab.requireApproval ||
                                    editMaxPeers != collab.maxPeers ||
                                    pwUpdateArg != null
                                ReButton(
                                    text = stringResource(R.string.collab_apply_room_settings),
                                    onClick = {
                                        vm.updateCollabRoomSettings(
                                            isPublic = editIsPublic,
                                            title = editTitle,
                                            requireApproval = editRequireApproval,
                                            maxPeers = editMaxPeers,
                                            passwordUpdate = pwUpdateArg,
                                        )
                                        editPwMode = 0
                                        editNewPassword = ""
                                    },
                                    primary = true,
                                    enabled = titleValid && pwValid && hasChanges && !collab.isUpdatingRoomSettings,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        val orphaned = vm.orphanedCollabLayers()
                        if (collab.role == CollabRole.HOST && orphaned.isNotEmpty()) {
                            CollabSectionCard {
                                ReSectionTitle(stringResource(R.string.collab_layer_reclaim_section, orphaned.size))
                                Text(
                                    text = stringResource(R.string.collab_layer_reclaim_desc),
                                    color = colors.subText,
                                    fontSize = 11.sp,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ReTextButton(
                                        text = stringResource(R.string.collab_layer_reclaim_share_all),
                                        onClick = { vm.reclaimOrphanedLayers(makeShared = true) },
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ReTextButton(
                                        text = stringResource(R.string.collab_layer_reclaim_claim_all),
                                        onClick = { vm.reclaimOrphanedLayers(makeShared = false) },
                                        containerColor = colors.accent,
                                        contentColor = colors.onAccent,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        CollabSectionCard {
                            ReSectionTitle(stringResource(R.string.collab_cursor_mode_label))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    CollabCursorDisplayMode.FULL to stringResource(R.string.collab_cursor_mode_full),
                                    CollabCursorDisplayMode.DOT_ONLY to stringResource(R.string.collab_cursor_mode_dot),
                                    CollabCursorDisplayMode.HIDDEN to stringResource(R.string.collab_cursor_mode_hidden),
                                ).forEach { (mode, label) ->
                                    ReChip(
                                        text = label,
                                        selected = collab.cursorDisplayMode == mode,
                                        onTap = { vm.setCollabCursorDisplayMode(mode) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            HorizontalDivider(color = colors.border.copy(alpha = 0.5f), thickness = 0.6.dp)

                            val nd = collab.networkDiag
                            val anyDirect = collab.peers.any { it.peerId != collab.selfPeerId && it.routeMode == CollabRouteMode.P2P_DIRECT }
                            val anyRelayed = collab.peers.any { it.peerId != collab.selfPeerId && it.routeMode == CollabRouteMode.RELAYED }
                            val netSummaryText = when {
                                !nd.errorMsg.isNullOrBlank() -> nd.errorMsg
                                anyDirect -> stringResource(R.string.collab_net_summary_direct)
                                anyRelayed || nd.forceRelayMode -> stringResource(R.string.collab_net_summary_relay)
                                nd.natDifficultyLevel == 1 -> stringResource(R.string.collab_net_summary_direct)
                                nd.natDifficultyLevel >= 2 -> stringResource(R.string.collab_net_summary_relay)
                                else -> stringResource(R.string.collab_net_summary_probing)
                            }
                            val natTint = when {
                                !nd.errorMsg.isNullOrBlank() -> colors.error
                                nd.natDifficultyLevel == 1 || anyDirect -> STATUS_GREEN
                                nd.natDifficultyLevel >= 2 || anyRelayed || nd.forceRelayMode -> STATUS_AMBER
                                else -> colors.subText
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showNetDetails = !showNetDetails },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(natTint),
                                    )
                                    Text(
                                        text = netSummaryText,
                                        color = natTint,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.collab_net_details_toggle),
                                        color = colors.subText,
                                        fontSize = 10.5.sp,
                                    )
                                    Icon(
                                        painter = painterResource(
                                            if (showNetDetails) R.drawable.ic_chevron_down else R.drawable.ic_chevron,
                                        ),
                                        contentDescription = null,
                                        tint = colors.subText,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                            if (collab.kcpNeedsReconnectHint) {
                                Text(
                                    text = stringResource(R.string.collab_kcp_reconnect_hint),
                                    color = STATUS_AMBER,
                                    fontSize = 10.5.sp,
                                )
                            }
                            if (showNetDetails) {
                                val natDetail = when (nd.natDifficultyLevel) {
                                    1 -> stringResource(R.string.collab_nat_easy, nd.selfNatType.name)
                                    2 -> stringResource(R.string.collab_nat_medium, nd.selfNatType.name)
                                    3 -> stringResource(R.string.collab_nat_hard)
                                    else -> nd.errorMsg ?: stringResource(R.string.collab_nat_probing)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(text = natDetail, color = colors.subText, fontSize = 10.5.sp)
                                    if (nd.selfVirtualIp.isNotEmpty()) {
                                        Text(text = nd.selfVirtualIp, color = colors.subText, fontSize = 10.sp)
                                    }
                                }
                            }
                            ReSettingRow(
                                label = stringResource(R.string.collab_force_relay_label),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Dimens.radiusSm))
                                    .background(colors.panel),
                            ) {
                                ReSwitch(
                                    checked = collab.forceRelayEnabled,
                                    onChecked = { vm.setCollabForceRelayEnabled(it) },
                                )
                            }
                            ReSettingRow(
                                label = stringResource(R.string.collab_kcp_proxy),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Dimens.radiusSm))
                                    .background(colors.panel),
                            ) {
                                ReSwitch(
                                    checked = collab.kcpProxyEnabled,
                                    onChecked = { vm.setCollabKcpProxyEnabled(it) },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReTextButton(
                    text = stringResource(R.string.collab_leave_room),
                    onClick = {
                        vm.requestEndCollabAndPromptSave(closeRoomForAll = false)
                    },
                    textColor = colors.text,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                if (collab.role == CollabRole.HOST) {
                    ReTextButton(
                        text = stringResource(R.string.collab_close_room),
                        onClick = { showCloseRoomConfirm = true },
                        containerColor = colors.error,
                        contentColor = Color.White,
                        fontSize = 12.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showCloseRoomConfirm) {
        CollabConfirmDialog(
            title = stringResource(R.string.collab_confirm_close_room_title),
            description = stringResource(R.string.collab_confirm_close_room_desc),
            confirmText = stringResource(R.string.collab_close_room),
            onConfirm = {
                showCloseRoomConfirm = false
                vm.requestEndCollabAndPromptSave(closeRoomForAll = true)
            },
            onDismiss = { showCloseRoomConfirm = false },
        )
    }

    pendingKickPeer?.let { target ->
        CollabConfirmDialog(
            title = stringResource(R.string.collab_confirm_kick_title),
            description = stringResource(R.string.collab_confirm_kick_desc, target.name),
            confirmText = stringResource(R.string.collab_kick),
            onConfirm = {
                pendingKickPeer = null
                vm.kickCollabPeer(target.peerId)
            },
            onDismiss = { pendingKickPeer = null },
        )
    }

    pendingHandoffPeer?.let { target ->
        CollabConfirmDialog(
            title = stringResource(R.string.collab_confirm_handoff_title),
            description = stringResource(R.string.collab_confirm_handoff_desc, target.name),
            confirmText = stringResource(R.string.collab_handoff_short),
            isDestructive = false,
            onConfirm = {
                pendingHandoffPeer = null
                vm.handoffCollabHost(target.peerId)
            },
            onDismiss = { pendingHandoffPeer = null },
        )
    }
}

/**
 * 茶绘结束保存弹窗: 允许成员保存工程，并在受限房中明确提示已过滤其他画师专属图层。
 */
@Composable
internal fun CollabEndSaveDialog(
    vm: PaintViewModel,
) {
    val colors = Theme.current
    val collab = vm.collab
    var projectName by remember(collab.endSaveDefaultName) {
        mutableStateOf(collab.endSaveDefaultName)
    }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val dialogShape = RoundedCornerShape(20.dp)
    val ownOnly = collab.endSaveOwnOnlyMode

    Dialog(
        onDismissRequest = { collab.showEndSaveDialog = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 310.dp, max = 410.dp)
                .fillMaxWidth(0.9f)
                .clip(dialogShape)
                .background(colors.panel)
                .glassBorder(dialogShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollabDialogHeader(
                title = stringResource(R.string.collab_end_save_title),
                subtitle = if (ownOnly) {
                    stringResource(R.string.collab_end_save_scope_own)
                } else {
                    stringResource(R.string.collab_end_save_scope_all)
                },
                iconTint = if (ownOnly) STATUS_AMBER else colors.accent,
                onClose = { collab.showEndSaveDialog = false },
            )

            Text(
                text = if (ownOnly) {
                    stringResource(R.string.collab_end_save_own_only_desc)
                } else {
                    stringResource(R.string.collab_end_save_desc)
                },
                color = colors.subText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            CollabSectionCard {
                ReSectionTitle(stringResource(R.string.collab_end_project_name))
                ReTextInput(
                    value = projectName,
                    onValueChange = { projectName = it.take(40) },
                    placeholder = stringResource(R.string.collab_end_project_name),
                )
            }

            ReButton(
                text = stringResource(R.string.collab_end_save_stay),
                onClick = { vm.saveCollabArtwork(projectName, exitToHome = false) },
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReTextButton(
                    text = stringResource(R.string.collab_end_discard_exit),
                    onClick = { showDiscardConfirm = true },
                    textColor = colors.error,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                ReTextButton(
                    text = stringResource(R.string.collab_end_save_exit),
                    onClick = { vm.saveCollabArtwork(projectName, exitToHome = true) },
                    textColor = colors.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showDiscardConfirm) {
        CollabConfirmDialog(
            title = stringResource(R.string.collab_confirm_discard_title),
            description = stringResource(R.string.collab_confirm_discard_desc),
            confirmText = stringResource(R.string.collab_end_discard_exit),
            onConfirm = {
                showDiscardConfirm = false
                vm.discardCollabAndExit()
            },
            onDismiss = { showDiscardConfirm = false },
        )
    }
}

/**
 * 新建画布页面内的茶绘开房参数设置弹窗。
 */
@Composable
internal fun CollabCreateConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    LaunchedEffect(Unit) { vm.ensureCollabPrefsLoaded() }
    var nickname by remember(vm.collab.selfName) { mutableStateOf(vm.collab.selfName) }
    var password by remember(vm.collab.pendingCreatePassword) { mutableStateOf(vm.collab.pendingCreatePassword) }
    var passwordVisible by remember { mutableStateOf(false) }
    var requireApproval by remember(vm.collab.pendingCreateRequireApproval) {
        mutableStateOf(vm.collab.pendingCreateRequireApproval)
    }
    var maxPeers by remember(vm.collab.pendingCreateMaxPeers) {
        mutableIntStateOf(vm.collab.pendingCreateMaxPeers)
    }
    var isPublic by remember(vm.collab.pendingCreateIsPublic) {
        mutableStateOf(vm.collab.pendingCreateIsPublic)
    }
    var roomTitle by remember(vm.collab.pendingCreateTitle) {
        mutableStateOf(vm.collab.pendingCreateTitle)
    }
    var savePolicy by remember(vm.collab.pendingCreateSavePolicy) {
        mutableStateOf(vm.collab.pendingCreateSavePolicy)
    }
    var signalingUrl by remember(vm.collab.signalingUrl) { mutableStateOf(vm.collab.signalingUrl) }
    var turnUrl by remember(vm.collab.customTurnUrl) { mutableStateOf(vm.collab.customTurnUrl) }
    var showAdvanced by remember { mutableStateOf(false) }

    val dialogShape = RoundedCornerShape(20.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 440.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp)
                .clip(dialogShape)
                .background(colors.panel)
                .glassBorder(dialogShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollabDialogHeader(
                title = stringResource(R.string.collab_room_settings_btn),
                showBetaBadge = true,
                onClose = onDismiss,
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CollabSectionCard {
                    ReSectionTitle(stringResource(R.string.collab_nickname))
                    ReTextInput(
                        value = nickname,
                        onValueChange = { nickname = it.take(24) },
                        placeholder = stringResource(R.string.collab_nickname_default),
                    )
                }

                CollabCreateOptionsContent(
                    vm = vm,
                    isPublic = isPublic,
                    onPublicChange = { isPublic = it },
                    roomTitle = roomTitle,
                    onTitleChange = { roomTitle = it.take(CollabTopologyPolicy.MAX_ROOM_TITLE_LENGTH) },
                    password = password,
                    onPasswordChange = { password = it.take(32) },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVis = { passwordVisible = !passwordVisible },
                    requireApproval = requireApproval,
                    onRequireApprovalChange = { requireApproval = it },
                    maxPeers = maxPeers,
                    onMaxPeersChange = { maxPeers = it },
                    savePolicy = savePolicy,
                    onSavePolicyChange = { savePolicy = it },
                )

                CollabAdvancedNetworkCard(
                    vm = vm,
                    showAdvanced = showAdvanced,
                    onToggleShow = { showAdvanced = !showAdvanced },
                    signalingUrl = signalingUrl,
                    onSignalingChange = { signalingUrl = it.trim() },
                    turnUrl = turnUrl,
                    onTurnChange = { turnUrl = it.trim() },
                )
            }

            ReButton(
                text = stringResource(R.string.common_save),
                onClick = {
                    vm.openCreateCanvasForCollab(
                        nickname = nickname,
                        password = password,
                        requireApproval = requireApproval,
                        maxPeers = maxPeers,
                        signalingUrl = signalingUrl,
                        turnUrl = turnUrl,
                        isPublic = isPublic,
                        roomTitle = roomTitle,
                        savePolicy = savePolicy,
                    )
                    onDismiss()
                },
                primary = true,
                enabled = CollabTopologyPolicy.isValidPublicRoomTitle(isPublic, roomTitle),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
