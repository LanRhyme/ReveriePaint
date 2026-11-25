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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.reverie.paint.ui.theme.Morandi

/**
 * 图层详情页内的 Magma 式多人茶绘所有权控制卡片:
 * 切换 公共层 / 专属层 / 全锁定, 认领归属或转让给房间内其他画师。
 */
@Composable
internal fun CollabLayerPermissionCard(
    vm: PaintViewModel,
    index: Int,
    layer: PaintViewModel.LayerUiState,
) {
    val collab = vm.collab
    val isHost = collab.role == CollabRole.HOST
    val canManage = CollabPermissionPolicy.canManageOwnership(
        isHost = isHost,
        ownerPeerId = layer.ownerPeerId,
        selfPeerId = collab.selfPeerId,
    )
    val currentPerm = LayerCollabPermission.fromCode(layer.collabPermission)
    val ownerName = vm.collabOwnerDisplayName(layer.ownerPeerId)
    var showOwnerMenu by remember { mutableStateOf(false) }

    Spacer(Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
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
                    tint = Morandi.accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.collab_layer_perm_title),
                    color = Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (layer.ownerPeerId.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(CollabTopologyPolicy.assignBadgeColorArgb(layer.ownerPeerId))),
                    )
                }
                Text(
                    text = stringResource(R.string.collab_layer_owner_label, ownerName),
                    color = Morandi.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                LayerCollabPermission.SHARED to stringResource(R.string.collab_layer_perm_shared),
                LayerCollabPermission.OWNER_ONLY to stringResource(R.string.collab_layer_perm_owner),
                LayerCollabPermission.LOCKED to stringResource(R.string.collab_layer_perm_locked),
            ).forEach { (perm, label) ->
                val selected = currentPerm == perm
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) Morandi.accent else Morandi.panel)
                        .border(
                            1.dp,
                            if (selected) Morandi.accent else Morandi.border.copy(alpha = 0.6f),
                            RoundedCornerShape(7.dp),
                        )
                        .clickable(enabled = canManage) {
                            if (!selected) {
                                vm.setCollabLayerPermission(index, perm)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = label,
                        color = if (selected) Morandi.onAccent else if (canManage) Morandi.text else Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        if (canManage) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (layer.ownerPeerId != collab.selfPeerId) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Morandi.accent.copy(alpha = 0.14f))
                            .clickable { vm.setCollabLayerOwner(index, collab.selfPeerId) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.collab_layer_claim_mine),
                            color = Morandi.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                val otherPeers = collab.peers.filter { it.peerId != layer.ownerPeerId }
                if (otherPeers.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(Morandi.panel)
                                .border(1.dp, Morandi.border, RoundedCornerShape(7.dp))
                                .clickable { showOwnerMenu = true }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.collab_layer_transfer_owner),
                                color = Morandi.text,
                                fontSize = 11.sp,
                            )
                        }
                        DropdownMenu(
                            expanded = showOwnerMenu,
                            onDismissRequest = { showOwnerMenu = false },
                        ) {
                            otherPeers.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text(target.name, fontSize = 12.sp) },
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
