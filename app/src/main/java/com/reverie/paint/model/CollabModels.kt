/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/** 茶绘会话连接阶段 */
enum class CollabConnectionPhase {
    IDLE,
    CREATING,
    JOINING,
    WAITING_APPROVAL,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

/** 茶绘房间内角色 */
enum class CollabRole {
    HOST,
    PEER,
}

/**
 * Magma 式图层协同权限 (与 C++ reverie::NodeExtra::collabPermission 数值严格对齐)。
 */
enum class LayerCollabPermission(val code: Int) {
    /** 公共层: 房间内任意已准入画师可画 */
    SHARED(0),

    /** 专属层: 仅 ownerPeerId 本人可落笔与修改 */
    OWNER_ONLY(1),

    /** 全锁定: 协同下禁止任何人落笔 */
    LOCKED(2),
    ;

    companion object {
        fun fromCode(code: Int): LayerCollabPermission = entries.find { it.code == code } ?: SHARED
    }
}

/** 房间内协作者信息 */
data class CollabPeer(
    val peerId: String,
    val name: String,
    val role: CollabRole,
    val joinedAt: Long = 0L,
    val badgeColorArgb: Int = CollabTopologyPolicy.assignBadgeColorArgb(peerId),
    val connectedP2P: Boolean = false,
    val signalingOnline: Boolean = true,
)

/** 房主审批队列中的待加入成员 */
data class CollabPendingRequest(
    val peerId: String,
    val name: String,
    val joinedAt: Long,
)

/** 远端协作者在画布上的实时光标投影 (文档坐标系) */
data class RemoteCursorState(
    val peerId: String,
    val name: String,
    val docX: Float,
    val docY: Float,
    val brushSize: Float,
    val brushColorArgb: Int,
    val badgeColorArgb: Int,
    val isDrawing: Boolean,
    val updatedAtMs: Long,
)

/** 入房前探查到的房间概要 */
data class CollabRoomSummary(
    val roomCode: String,
    val hostPeerId: String,
    val hostName: String,
    val requireApproval: Boolean,
    val hasPassword: Boolean,
    val maxPeers: Int,
    val peerCount: Int,
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
)

/** 图层结构校验用的轻量只读描述 (供纯模型决策与自动选层复用, 不依赖 UI State) */
data class CollabLayerDescriptor(
    val index: Int,
    val isBackground: Boolean,
    val isGroup: Boolean,
    val isLocked: Boolean,
    val nodeType: Int,
    val ownerPeerId: String,
    val permissionCode: Int,
    val hasLocalSpecialContent: Boolean = false,
)

/** Magma 图层所有权与写入权限纯规则 */
object CollabPermissionPolicy {
    /**
     * 判断当前成员是否允许在指定图层上作画。
     * 非协同模式始终放行 (由单机 locked/background 判定); 协同模式下附加校验所有权。
     */
    fun canEditLayer(
        inCollab: Boolean,
        isLayerLocked: Boolean,
        isBackground: Boolean,
        isGroup: Boolean,
        ownerPeerId: String,
        permissionCode: Int,
        selfPeerId: String,
    ): Boolean {
        if (isLayerLocked || isBackground || isGroup) return false
        if (!inCollab) return true
        return when (LayerCollabPermission.fromCode(permissionCode)) {
            LayerCollabPermission.LOCKED -> false
            LayerCollabPermission.OWNER_ONLY -> ownerPeerId.isEmpty() || ownerPeerId == selfPeerId
            LayerCollabPermission.SHARED -> true
        }
    }

    /** 仅房主或图层当前拥有者可修改协同权限、锁定状态或转让所有权 */
    fun canManageOwnership(
        isHost: Boolean,
        ownerPeerId: String,
        selfPeerId: String,
    ): Boolean = isHost || ownerPeerId.isEmpty() || ownerPeerId == selfPeerId

    /** 协同模式下仅房主可修改画布背景色或背景层显隐 */
    fun canEditBackground(
        inCollab: Boolean,
        isHost: Boolean,
    ): Boolean = !inCollab || isHost

    /** 判断成员是否允许修改图层名称、显隐、透明度、混合模式、剪切蒙版、排序与透明度保护等结构/属性 */
    fun canModifyLayerProperties(
        inCollab: Boolean,
        isHost: Boolean,
        isBackground: Boolean,
        isLayerLocked: Boolean,
        ownerPeerId: String,
        permissionCode: Int,
        selfPeerId: String,
    ): Boolean {
        if (isBackground) return canEditBackground(inCollab, isHost)
        if (!inCollab) return true
        if (isLayerLocked) return false
        return when (LayerCollabPermission.fromCode(permissionCode)) {
            LayerCollabPermission.LOCKED -> false
            LayerCollabPermission.OWNER_ONLY -> canManageOwnership(isHost, ownerPeerId, selfPeerId)
            LayerCollabPermission.SHARED -> true
        }
    }

    /** 判断成员是否允许切换图层本身的双态锁定图标 */
    fun canToggleLayerLock(
        inCollab: Boolean,
        isHost: Boolean,
        isBackground: Boolean,
        ownerPeerId: String,
        selfPeerId: String,
    ): Boolean {
        if (isBackground) return false
        if (!inCollab) return true
        return canManageOwnership(isHost, ownerPeerId, selfPeerId)
    }

    /** 判断成员是否允许删除图层或图层组: 锁定图层无人可删; 未锁定时仅房主或创建者本人可删 */
    fun canDeleteLayer(
        inCollab: Boolean,
        isHost: Boolean,
        isBackground: Boolean,
        isLayerLocked: Boolean,
        ownerPeerId: String,
        permissionCode: Int,
        selfPeerId: String,
    ): Boolean {
        if (isBackground || isLayerLocked) return false
        if (!inCollab) return true
        if (LayerCollabPermission.fromCode(permissionCode) == LayerCollabPermission.LOCKED) return false
        return canManageOwnership(isHost, ownerPeerId, selfPeerId)
    }

    /** 判断成员是否允许将图层放入目标图层组 */
    fun canInsertIntoGroup(
        inCollab: Boolean,
        isHost: Boolean,
        isGroupLocked: Boolean,
        groupOwnerPeerId: String,
        groupPermissionCode: Int,
        selfPeerId: String,
    ): Boolean {
        if (isGroupLocked) return false
        if (!inCollab) return true
        return when (LayerCollabPermission.fromCode(groupPermissionCode)) {
            LayerCollabPermission.LOCKED -> false
            LayerCollabPermission.OWNER_ONLY -> canManageOwnership(isHost, groupOwnerPeerId, selfPeerId)
            LayerCollabPermission.SHARED -> true
        }
    }

    /** 协同会话中仅同步颜料图层、图层组、填充图层与透明度蒙版 */
    fun isLayerTypeSupportedInCollab(
        nodeType: Int,
        has3DContent: Boolean = false,
        hasTextContent: Boolean = false,
    ): Boolean {
        if (has3DContent || hasTextContent) return false
        return nodeType == LayerNodeType.PAINT ||
            nodeType == LayerNodeType.GROUP ||
            nodeType == LayerNodeType.FILL ||
            nodeType == LayerNodeType.TRANSPARENCY_MASK
    }

    /** 加入房间/重加载快照后自动选择最上方自己可绘的普通图层: 优先本人专属, 其次公共层, 找不到返回 -1 */
    fun pickAutoFocusLayerIndex(
        layers: List<CollabLayerDescriptor>,
        selfPeerId: String,
    ): Int {
        fun isCandidate(d: CollabLayerDescriptor): Boolean {
            if (d.isBackground || d.isGroup || LayerNodeType.isMask(d.nodeType)) return false
            if (!isLayerTypeSupportedInCollab(d.nodeType, has3DContent = d.hasLocalSpecialContent)) return false
            if (d.nodeType == LayerNodeType.FILL) return false
            return canEditLayer(
                inCollab = true,
                isLayerLocked = d.isLocked,
                isBackground = false,
                isGroup = false,
                ownerPeerId = d.ownerPeerId,
                permissionCode = d.permissionCode,
                selfPeerId = selfPeerId,
            )
        }
        val ownTop = layers.asReversed().firstOrNull { isCandidate(it) && it.ownerPeerId == selfPeerId }
        if (ownTop != null) return ownTop.index
        val sharedTop = layers.asReversed().firstOrNull { isCandidate(it) }
        return sharedTop?.index ?: -1
    }

    /** 判断某图层是否属于已离房/被移出成员留下的遗留专属/有主图层 (暂时离线仍保留所有权, 不视为遗留) */
    fun isOrphanedDepartedLayer(
        inCollab: Boolean,
        isBackground: Boolean,
        ownerPeerId: String,
        selfPeerId: String,
        activePeerIds: Set<String>,
        departedPeerIds: Set<String>,
    ): Boolean {
        if (!inCollab || isBackground || ownerPeerId.isEmpty() || ownerPeerId == selfPeerId) return false
        if (ownerPeerId in activePeerIds) return false
        return ownerPeerId in departedPeerIds || activePeerIds.isNotEmpty()
    }
}

/** 拓扑模式与确定性房间码 / 选主策略 (与服务端 collab/worker/src/room.js 一致) */
object CollabTopologyPolicy {
    const val DEFAULT_SIGNALING_URL = "https://reverieteapaint.lanrhyme.top"
    const val ROOM_CODE_LENGTH = 6
    const val STAR_MAX_PEERS = 10

    private val ROOM_CODE_REGEX = Regex("^[A-Z2-9]{6}$")

    /** 莫兰迪高辨识协作者徽章色板 (用于区分各画师光标与专属图层标签) */
    private val BADGE_PALETTE = intArrayOf(
        0xFFD4746A.toInt(), // 陶土红
        0xFF5D8AA8.toInt(), // 雾霾蓝
        0xFF6B9E78.toInt(), // 鼠尾草绿
        0xFFC99E52.toInt(), // 芥末黄
        0xFF9B72AA.toInt(), // 烟熏紫
        0xFF5A9AA8.toInt(), // 莫兰迪青
        0xFFCC825D.toInt(), // 焦糖橘
        0xFF7B889E.toInt(), // 石板蓝
    )

    enum class TopologyMode {
        /** 2~10 人: 房主权威星型直连定序 */
        STAR,

        /** >10 人: 分层二跳树形 P2P 转发 */
        TREE_RELAY,
    }

    fun modeForPeerCount(peerCount: Int): TopologyMode =
        if (peerCount <= STAR_MAX_PEERS) TopologyMode.STAR else TopologyMode.TREE_RELAY

    fun normalizeRoomCode(raw: String): String =
        raw.trim().uppercase().replace("-", "").replace(" ", "")

    fun isValidRoomCode(raw: String): Boolean =
        ROOM_CODE_REGEX.matches(normalizeRoomCode(raw))

    /**
     * 房主离线后的确定性继任选举:
     * 排除掉线房主后, 取加入时间最早 (joinedAt 最小) 且 peerId 字典序最小的成员。
     * 必须与服务端 CollabRoom::earliestAdmittedPeer 严格对齐。
     */
    fun electNextHost(peers: List<CollabPeer>, deadHostPeerId: String): CollabPeer? =
        peers
            .asSequence()
            .filter { it.peerId != deadHostPeerId }
            .minWithOrNull(compareBy<CollabPeer> { it.joinedAt }.thenBy { it.peerId })

    fun assignBadgeColorArgb(peerId: String): Int {
        if (peerId.isEmpty()) return BADGE_PALETTE[0]
        var hash = 0x811C9DC5.toInt()
        for (ch in peerId) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        val idx = (hash ushr 1) % BADGE_PALETTE.size
        return BADGE_PALETTE[idx]
    }
}

/** 协同模式下工具可用性与局部像素互斥策略 */
object CollabToolSupportPolicy {
    /** 协同会话期间禁止改变画布宽高，避免成员间坐标系撕裂 */
    fun canResizeOrCropCanvas(inCollab: Boolean): Boolean = !inCollab

    /** 仅会改写当前图层像素的工具需要通过 Magma 图层所有权/锁定校验 */
    fun requiresLayerWritePermission(tool: Tool): Boolean = when (tool) {
        Tool.BRUSH,
        Tool.ERASER,
        Tool.SMUDGE,
        Tool.LIQUIFY,
        Tool.FILL,
        Tool.ENCLOSE_FILL,
        Tool.SHAPES,
        Tool.LINE,
        Tool.RECT,
        Tool.ELLIPSE,
        Tool.POLYGON,
        Tool.POLYLINE,
        Tool.PATH,
        Tool.GRADIENT,
        Tool.TRANSFORM,
        Tool.TEXT -> true
        else -> false
    }
}
