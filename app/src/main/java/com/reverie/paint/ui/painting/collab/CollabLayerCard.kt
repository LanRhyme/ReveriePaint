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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.collabOwnerDisplayName
import com.reverie.paint.core.setCollabLayerOwner
import com.reverie.paint.core.setCollabLayerPermission
import com.reverie.paint.model.CollabPermissionPolicy
import com.reverie.paint.model.CollabRole
import com.reverie.paint.model.CollabTopologyPolicy
import com.reverie.paint.model.LayerCollabPermission
import com.reverie.paint.ui.components.Dimens
import com.reverie.paint.ui.components.ReChip
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.glassBorder

/**
 * 图层详情页内的 Magma 式多人茶绘所有权控制卡片 (使用项目 ReComponents)。
 */
@Composable
internal fun CollabLayerPermissionCard(
    vm: PaintViewModel,
    index: Int,
    layer: PaintViewModel.LayerUiState,
) {
    val colors = Theme.current
    val collab = vm.collab
    val isHost = collab.role == CollabRole.HOST
    val canManage = CollabPermissionPolicy.canManageOwnership(
        isHost = isHost,
        ownerPeerId = layer.ownerPeerId,
        selfPeerId = collab.selfPeerId,
    )
    val currentPerm = LayerCollabPermission.fromCode(layer.collabPermission)
    val ownerLabelText = if (currentPerm == LayerCollabPermission.SHARED || layer.ownerPeerId.isEmpty()) {
        stringResource(R.string.collab_layer_mode_shared)
    } else {
        stringResource(R.string.collab_layer_owner_label, vm.collabOwnerDisplayName(layer.ownerPeerId))
    }
    var showOwnerMenu by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(Dimens.radius)

    Spacer(Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.panelHi)
            .glassBorder(cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_cloud),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.collab_layer_perm_title),
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (currentPerm != LayerCollabPermission.SHARED && layer.ownerPeerId.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(CollabTopologyPolicy.assignBadgeColorArgb(layer.ownerPeerId))),
                    )
                }
                Text(
                    text = ownerLabelText,
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val isSharedMode = currentPerm == LayerCollabPermission.SHARED
        val isPersonalMode = currentPerm != LayerCollabPermission.SHARED
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                Triple(
                    LayerCollabPermission.OWNER_ONLY,
                    stringResource(R.string.collab_layer_mode_personal),
                    isPersonalMode,
                ),
                Triple(
                    LayerCollabPermission.SHARED,
                    stringResource(R.string.collab_layer_mode_shared),
                    isSharedMode,
                ),
            ).forEach { (perm, label, selected) ->
                val itemShape = RoundedCornerShape(Dimens.radiusSm)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(itemShape)
                        .background(if (selected) colors.accent else colors.panel)
                        .then(
                            if (!selected) {
                                Modifier.border(1.dp, colors.border.copy(alpha = 0.55f), itemShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable(enabled = canManage) {
                            if (currentPerm != perm) {
                                vm.setCollabLayerPermission(index, perm)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = label,
                        color = if (selected) colors.onAccent else if (canManage) colors.text else colors.subText,
                        fontSize = 11.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }

        if (canManage) {
            val otherPeers = collab.peers.filter { it.peerId != layer.ownerPeerId }
            val needsClaim = layer.ownerPeerId != collab.selfPeerId
            if (needsClaim || otherPeers.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (needsClaim) {
                        ReTextButton(
                            text = stringResource(R.string.collab_layer_claim_mine),
                            onClick = { vm.setCollabLayerOwner(index, collab.selfPeerId) },
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (otherPeers.isNotEmpty()) {
                        Box(modifier = Modifier.weight(1f)) {
                            ReChip(
                                text = stringResource(R.string.collab_layer_transfer_owner),
                                onTap = { showOwnerMenu = true },
                                selected = showOwnerMenu,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ReDropdownMenu(
                                expanded = showOwnerMenu,
                                onDismissRequest = { showOwnerMenu = false },
                            ) {
                                otherPeers.forEach { target ->
                                    ReDropdownMenuItem(
                                        text = target.name,
                                        onClick = {
                                            showOwnerMenu = false
                                            vm.setCollabLayerOwner(index, target.peerId)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
