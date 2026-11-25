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

    @Test
    fun `协同图层结构属性删除与分组权限校验完整`() {
        // 锁定或背景层不允许非房主改属性
        assertFalse(
            CollabPermissionPolicy.canModifyLayerProperties(
                inCollab = true,
                isHost = false,
                isBackground = false,
                isLayerLocked = true,
                ownerPeerId = "me",
                permissionCode = LayerCollabPermission.SHARED.code,
                selfPeerId = "me",
            ),
        )
        assertFalse(
            CollabPermissionPolicy.canModifyLayerProperties(
                inCollab = true,
                isHost = false,
                isBackground = true,
                isLayerLocked = false,
                ownerPeerId = "host",
                permissionCode = LayerCollabPermission.SHARED.code,
                selfPeerId = "guest",
            ),
        )
        assertTrue(
            CollabPermissionPolicy.canModifyLayerProperties(
                inCollab = true,
                isHost = true,
                isBackground = true,
                isLayerLocked = false,
                ownerPeerId = "host",
                permissionCode = LayerCollabPermission.SHARED.code,
                selfPeerId = "host",
            ),
        )

        // 公共层允许他人在未锁定时调整混合模式/排序等属性, 但禁止他人在无所有权时加锁或删层
        assertTrue(
            CollabPermissionPolicy.canModifyLayerProperties(
                inCollab = true,
                isHost = false,
                isBackground = false,
                isLayerLocked = false,
                ownerPeerId = "other",
                permissionCode = LayerCollabPermission.SHARED.code,
                selfPeerId = "guest",
            ),
        )
        assertFalse(
            CollabPermissionPolicy.canToggleLayerLock(
                inCollab = true,
                isHost = false,
                isBackground = false,
                ownerPeerId = "other",
                selfPeerId = "guest",
            ),
        )
        assertFalse(
            CollabPermissionPolicy.canDeleteLayer(
                inCollab = true,
                isHost = false,
                isBackground = false,
                isLayerLocked = false,
                ownerPeerId = "other",
                permissionCode = LayerCollabPermission.SHARED.code,
                selfPeerId = "guest",
            ),
        )
        assertTrue(
            CollabPermissionPolicy.canDeleteLayer(
                inCollab = true,
                isHost = true,
                isBackground = false,
                isLayerLocked = false,
                ownerPeerId = "other",
                permissionCode = LayerCollabPermission.OWNER_ONLY.code,
                selfPeerId = "host",
            ),
        )
        // 锁定状态无人能直接删除
        assertFalse(
            CollabPermissionPolicy.canDeleteLayer(
                inCollab = true,
                isHost = true,
                isBackground = false,
                isLayerLocked = true,
                ownerPeerId = "host",
                permissionCode = LayerCollabPermission.OWNER_ONLY.code,
                selfPeerId = "host",
            ),
        )

        // 插入分组权限
        assertFalse(
            CollabPermissionPolicy.canInsertIntoGroup(
                inCollab = true,
                isHost = false,
                isGroupLocked = false,
                groupOwnerPeerId = "other",
                groupPermissionCode = LayerCollabPermission.OWNER_ONLY.code,
                selfPeerId = "guest",
            ),
        )
        assertTrue(
            CollabPermissionPolicy.canInsertIntoGroup(
                inCollab = true,
                isHost = false,
                isGroupLocked = false,
                groupOwnerPeerId = "other",
                groupPermissionCode = LayerCollabPermission.SHARED.code,
                selfPeerId = "guest",
            ),
        )
    }

    @Test
    fun `加入自动聚焦优选本人层并识别离房遗留图层`() {
        val layers = listOf(
            CollabLayerDescriptor(0, isBackground = true, isGroup = false, isLocked = true, nodeType = 0, ownerPeerId = "", permissionCode = 0),
            CollabLayerDescriptor(1, isBackground = false, isGroup = false, isLocked = false, nodeType = 0, ownerPeerId = "other", permissionCode = LayerCollabPermission.SHARED.code),
            CollabLayerDescriptor(2, isBackground = false, isGroup = false, isLocked = false, nodeType = 0, ownerPeerId = "me", permissionCode = LayerCollabPermission.OWNER_ONLY.code),
            CollabLayerDescriptor(3, isBackground = false, isGroup = false, isLocked = false, nodeType = 0, ownerPeerId = "other", permissionCode = LayerCollabPermission.OWNER_ONLY.code),
        )
        assertEquals(2, CollabPermissionPolicy.pickAutoFocusLayerIndex(layers, "me"))
        assertEquals(-1, CollabPermissionPolicy.pickAutoFocusLayerIndex(layers, "third"))
        assertEquals(1, CollabPermissionPolicy.pickAutoFocusLayerIndex(layers, "third", fallbackToShared = true))
        assertEquals(
            -1,
            CollabPermissionPolicy.pickAutoFocusLayerIndex(
                layers.filterNot { it.permissionCode == LayerCollabPermission.SHARED.code },
                "third",
                fallbackToShared = true,
            ),
        )

        // 暂时离线成员仍在 activePeerIds 中, 不得视为遗留
        assertFalse(
            CollabPermissionPolicy.isOrphanedDepartedLayer(
                inCollab = true,
                isBackground = false,
                ownerPeerId = "p2",
                selfPeerId = "host",
                activePeerIds = setOf("host", "p2"),
                departedPeerIds = emptySet(),
            ),
        )
        // 已离房成员不再在 activePeerIds 中, 视为遗留可接管
        assertTrue(
            CollabPermissionPolicy.isOrphanedDepartedLayer(
                inCollab = true,
                isBackground = false,
                ownerPeerId = "p2",
                selfPeerId = "host",
                activePeerIds = setOf("host"),
                departedPeerIds = setOf("p2"),
            ),
        )
    }

    @Test
    fun `协同模式下支持与不支持的图层类型分类明确`() {
        assertTrue(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.PAINT))
        assertTrue(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.GROUP))
        assertTrue(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.FILL))
        assertTrue(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.TRANSPARENCY_MASK))
        assertFalse(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.ADJUSTMENT))
        assertFalse(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.STROKE))
        assertFalse(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.PAINT, has3DContent = true))
        assertFalse(CollabPermissionPolicy.isLayerTypeSupportedInCollab(LayerNodeType.PAINT, hasTextContent = true))

        assertEquals("", CollabPermissionPolicy.formatShortOwnerLabel("   "))
        assertEquals("Alex", CollabPermissionPolicy.formatShortOwnerLabel("Alex"))
        assertEquals("LanR…", CollabPermissionPolicy.formatShortOwnerLabel("LanRhyme"))
        assertEquals("", CollabPermissionPolicy.formatAvatarInitial("   "))
        assertEquals("A", CollabPermissionPolicy.formatAvatarInitial("alice"))
        assertEquals("小", CollabPermissionPolicy.formatAvatarInitial("小明"))
    }

    @Test
    fun `重名成员校验忽略首尾空格与英文大小写且放行空白输入`() {
        assertEquals("alice", CollabTopologyPolicy.normalizeNickname("  Alice "))
        assertTrue(CollabTopologyPolicy.isSameNickname("Alice", " alice "))
        assertFalse(CollabTopologyPolicy.isSameNickname("   ", ""))
        assertFalse(CollabTopologyPolicy.isSameNickname("Alice", "Bob"))
        assertTrue(CollabTopologyPolicy.hasDuplicateNickname("小画师", listOf("房主", "小画师 ")))
        assertFalse(CollabTopologyPolicy.hasDuplicateNickname("新画师", listOf("房主", "小画师")))
        assertFalse(CollabTopologyPolicy.hasDuplicateNickname("   ", listOf("   ", "房主")))
    }

    @Test
    fun `公开房间标题规范化与必填校验正确`() {
        assertEquals("深夜 摸鱼房", CollabTopologyPolicy.sanitizeRoomTitle("  深夜\n摸鱼房\t "))
        assertEquals(
            CollabTopologyPolicy.MAX_ROOM_TITLE_LENGTH,
            CollabTopologyPolicy.sanitizeRoomTitle("a".repeat(50)).length,
        )
        assertTrue(CollabTopologyPolicy.isValidPublicRoomTitle(isPublic = false, rawTitle = ""))
        assertTrue(CollabTopologyPolicy.isValidPublicRoomTitle(isPublic = false, rawTitle = "   "))
        assertFalse(CollabTopologyPolicy.isValidPublicRoomTitle(isPublic = true, rawTitle = ""))
        assertFalse(CollabTopologyPolicy.isValidPublicRoomTitle(isPublic = true, rawTitle = " \n\t "))
        assertTrue(CollabTopologyPolicy.isValidPublicRoomTitle(isPublic = true, rawTitle = "周末线稿接龙"))
    }
}
