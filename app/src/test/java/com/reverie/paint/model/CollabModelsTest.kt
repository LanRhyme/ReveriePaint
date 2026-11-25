/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabModelsTest {

    @Test
    fun `房间码规范化去除空格和横线并剔除易混淆字符`() {
        assertEquals("AB2C9K", CollabTopologyPolicy.normalizeRoomCode(" ab2-c9k "))
        assertTrue(CollabTopologyPolicy.isValidRoomCode("ab2c9k"))
        assertTrue(CollabTopologyPolicy.isValidRoomCode("TG7-X3G"))
        assertFalse(CollabTopologyPolicy.isValidRoomCode("AB01OL"))
        assertFalse(CollabTopologyPolicy.isValidRoomCode("12345"))
    }

    @Test
    fun `Magma 图层所有权与锁定权限校验`() {
        // 非协同模式只受单机锁定限制
        assertTrue(
            CollabPermissionPolicy.canEditLayer(
                inCollab = false,
                isLayerLocked = false,
                isBackground = false,
                isGroup = false,
                ownerPeerId = "other",
                permissionCode = LayerCollabPermission.OWNER_ONLY.code,
                selfPeerId = "me",
            ),
        )

        // 协同专属层: 本人可画, 他人被拦截
        assertTrue(
            CollabPermissionPolicy.canEditLayer(
                inCollab = true,
                isLayerLocked = false,
                isBackground = false,
                isGroup = false,
                ownerPeerId = "me",
                permissionCode = LayerCollabPermission.OWNER_ONLY.code,
                selfPeerId = "me",
            ),
        )
        assertFalse(
            CollabPermissionPolicy.canEditLayer(
                inCollab = true,
                isLayerLocked = false,
                isBackground = false,
                isGroup = false,
                ownerPeerId = "other",
                permissionCode = LayerCollabPermission.OWNER_ONLY.code,
                selfPeerId = "me",
            ),
        )

        // 协同公共层: 他人也可画, 但全局锁定时禁用
        assertTrue(
            CollabPermissionPolicy.canEditLayer(
                inCollab = true,
                isLayerLocked = false,
                isBackground = false,
                isGroup = false,
                ownerPeerId = "other",
                permissionCode = LayerCollabPermission.SHARED.code,
                selfPeerId = "me",
            ),
        )
        assertFalse(
            CollabPermissionPolicy.canEditLayer(
                inCollab = true,
                isLayerLocked = false,
                isBackground = false,
                isGroup = false,
                ownerPeerId = "me",
                permissionCode = LayerCollabPermission.LOCKED.code,
                selfPeerId = "me",
            ),
        )
    }

    @Test
    fun `房主掉线选主确定按加入时间最早且 peerId 最小`() {
        val host = CollabPeer("host1", "Host", CollabRole.HOST, joinedAt = 100L)
        val p2 = CollabPeer("peer_b", "B", CollabRole.PEER, joinedAt = 300L)
        val p1 = CollabPeer("peer_a", "A", CollabRole.PEER, joinedAt = 200L)
        val p1Tie = CollabPeer("peer_0", "Z", CollabRole.PEER, joinedAt = 200L)

        val elected = CollabTopologyPolicy.electNextHost(listOf(host, p2, p1, p1Tie), "host1")
        assertEquals("peer_0", elected?.peerId)
        assertNull(CollabTopologyPolicy.electNextHost(listOf(host), "host1"))
    }

    @Test
    fun `房间人数阈值切换星型与树形拓扑`() {
        assertEquals(CollabTopologyPolicy.TopologyMode.STAR, CollabTopologyPolicy.modeForPeerCount(2))
        assertEquals(CollabTopologyPolicy.TopologyMode.STAR, CollabTopologyPolicy.modeForPeerCount(10))
        assertEquals(CollabTopologyPolicy.TopologyMode.TREE_RELAY, CollabTopologyPolicy.modeForPeerCount(11))
    }

    @Test
    fun `同一 peerId 始终分配相同的徽章色`() {
        val c1 = CollabTopologyPolicy.assignBadgeColorArgb("artist_01")
        val c2 = CollabTopologyPolicy.assignBadgeColorArgb("artist_01")
        assertEquals(c1, c2)
        assertTrue((c1 ushr 24) == 0xFF)
    }

    @Test
    fun `图层所有权管理与权限枚举映射边界正确`() {
        assertEquals(LayerCollabPermission.SHARED, LayerCollabPermission.fromCode(0))
        assertEquals(LayerCollabPermission.OWNER_ONLY, LayerCollabPermission.fromCode(1))
        assertEquals(LayerCollabPermission.LOCKED, LayerCollabPermission.fromCode(2))
        assertEquals(LayerCollabPermission.SHARED, LayerCollabPermission.fromCode(999))

        assertTrue(CollabPermissionPolicy.canManageOwnership(isHost = true, ownerPeerId = "other", selfPeerId = "host"))
        assertTrue(CollabPermissionPolicy.canManageOwnership(isHost = false, ownerPeerId = "me", selfPeerId = "me"))
        assertTrue(CollabPermissionPolicy.canManageOwnership(isHost = false, ownerPeerId = "", selfPeerId = "me"))
        assertFalse(CollabPermissionPolicy.canManageOwnership(isHost = false, ownerPeerId = "other", selfPeerId = "me"))

        val offlinePeer = CollabPeer("p1", "Artist", CollabRole.PEER).copy(signalingOnline = false)
        assertFalse(offlinePeer.signalingOnline)
        assertEquals(CollabTopologyPolicy.assignBadgeColorArgb("p1"), offlinePeer.badgeColorArgb)
    }

    @Test
    fun `协同模式下像素工具写入门禁与画布尺寸保护正确`() {
        assertTrue(CollabToolSupportPolicy.canResizeOrCropCanvas(inCollab = false))
        assertFalse(CollabToolSupportPolicy.canResizeOrCropCanvas(inCollab = true))

        assertTrue(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.FILL))
        assertTrue(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.ENCLOSE_FILL))
        assertTrue(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.GRADIENT))
        assertTrue(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.LIQUIFY))
        assertTrue(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.TRANSFORM))
        assertTrue(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.SHAPES))
        assertTrue(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.TEXT))

        assertFalse(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.PICKER))
        assertFalse(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.LASSO))
        assertFalse(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.MAGICWAND))
        assertFalse(CollabToolSupportPolicy.requiresLayerWritePermission(Tool.PAN))
    }
}
