/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.cos
import kotlin.math.sin

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

/** 房间保存权限策略 (建房后不可修改, 房主与普通成员均需遵守) */
enum class CollabSavePolicy(val wireValue: String) {
    /** 默认: 所有成员可随时保存/导出完整协作画布 */
    ALL("all"),

    /** 仅限本人画作: 房内禁用全画布保存/导出, 仅退房时保留本人图层与公共/背景层 */
    OWN_ONLY("own_only"),
    ;

    companion object {
        fun fromWire(raw: String?): CollabSavePolicy =
            entries.find { it.wireValue.equals(raw?.trim(), ignoreCase = true) } ?: ALL
    }
}

/** 断线/进程回收后的可恢复茶绘会话快照 */
data class CollabRecoverableSession(
    val roomCode: String,
    val peerId: String,
    val peerName: String,
    val role: CollabRole,
    val hostToken: String = "",
    val peerToken: String = "",
    val signalingUrl: String = CollabTopologyPolicy.DEFAULT_SIGNALING_URL,
    val roomTitle: String = "",
    val isPublic: Boolean = false,
    val savePolicy: CollabSavePolicy = CollabSavePolicy.ALL,
    val savedAtMs: Long = 0L,
)

/** 房间实时可变配置与元信息快照 */
data class CollabRoomSettingsSnapshot(
    val isPublic: Boolean = false,
    val title: String = "",
    val hasPassword: Boolean = false,
    val requireApproval: Boolean = false,
    val maxPeers: Int = 10,
    val savePolicy: CollabSavePolicy = CollabSavePolicy.ALL,
)

/** 入房前信息接口中的精简成员项 (用于重连时按 peerId 排除自己避免误判重名) */
data class CollabPeerSummary(
    val peerId: String,
    val name: String,
)

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

enum class CollabNatType {
    UNKNOWN,
    OPEN_INTERNET,
    NO_PAT,
    FULL_CONE,
    RESTRICTED,
    PORT_RESTRICTED,
    SYMMETRIC,
    SYM_UDP_FIREWALL,
    SYMMETRIC_EASY_INC,
    SYMMETRIC_EASY_DEC,
}

enum class CollabRouteMode {
    CONNECTING,
    P2P_DIRECT,
    RELAYED,
    LOCAL_HOST,
}

data class CollabPeerRouteDiag(
    val peerId: String,
    val routeMode: CollabRouteMode = CollabRouteMode.CONNECTING,
    val tunnelProto: String = "",
    val latencyMs: Int = -1,
    val lossRate: Float = 0f,
    val natType: CollabNatType = CollabNatType.UNKNOWN,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
)

data class CollabNetworkDiag(
    val ready: Boolean = false,
    val easytierRunning: Boolean = false,
    val selfVirtualIp: String = "",
    val hostVirtualIp: String = "",
    val selfNatType: CollabNatType = CollabNatType.UNKNOWN,
    val tcpNatType: CollabNatType = CollabNatType.UNKNOWN,
    val publicIps: List<String> = emptyList(),
    val natDifficultyLevel: Int = 0, // 0=探测中, 1=简单(直连/Cone), 2=中等(端口受限/规则对称), 3=困难(对称NAT/需中继)
    val forceRelayMode: Boolean = false,
    val autoRelayEscalated: Boolean = false,
    val errorMsg: String? = null,
    val peerRoutes: Map<String, CollabPeerRouteDiag> = emptyMap(),
)

/** 房间内协作者信息 */
data class CollabPeer(
    val peerId: String,
    val name: String,
    val role: CollabRole,
    val joinedAt: Long = 0L,
    val badgeColorArgb: Int = CollabTopologyPolicy.assignBadgeColorArgb(peerId),
    val connectedP2P: Boolean = false,
    val signalingOnline: Boolean = true,
    val virtualIp: String = "",
    val routeMode: CollabRouteMode = CollabRouteMode.CONNECTING,
    val tunnelProto: String = "",
    val latencyMs: Int = -1,
    val natType: CollabNatType = CollabNatType.UNKNOWN,
)

/** 房主审批队列中的待加入成员 */
data class CollabPendingRequest(
    val peerId: String,
    val name: String,
    val joinedAt: Long,
)

/** 远端协作者光标显示模式 */
enum class CollabCursorDisplayMode(val code: Int) {
    /** 完整展示画笔大小圆环、中心点与昵称胶囊 */
    FULL(0),

    /** 仅展示中心小圆点与落笔光晕，避免多人遮挡画面细节 */
    DOT_ONLY(1),

    /** 隐藏远端光标 */
    HIDDEN(2),
    ;

    companion object {
        fun fromCode(code: Int): CollabCursorDisplayMode = entries.find { it.code == code } ?: FULL
    }
}

/** 茶绘轻量快捷聊天消息 */
data class CollabChatMessage(
    val id: String,
    val peerId: String,
    val senderName: String,
    val badgeColorArgb: Int,
    val text: String,
    val timestampMs: Long,
    val isSelf: Boolean,
)

/** 茶绘画布局部提醒标记 (Ping) */
data class CollabCanvasPing(
    val id: String,
    val peerId: String,
    val senderName: String,
    val badgeColorArgb: Int,
    val docX: Float,
    val docY: Float,
    val createdAtUptimeMs: Long,
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
    val peerNames: List<String> = emptyList(),
    val peers: List<CollabPeerSummary> = emptyList(),
    val isPublic: Boolean = false,
    val title: String = "",
    val savePolicy: CollabSavePolicy = CollabSavePolicy.ALL,
)

/** 在线房间大厅展示的公开房间摘要 */
data class CollabPublicRoom(
    val roomCode: String,
    val title: String,
    val hostName: String,
    val peerCount: Int,
    val maxPeers: Int,
    val hasPassword: Boolean,
    val requireApproval: Boolean,
    val savePolicy: CollabSavePolicy = CollabSavePolicy.ALL,
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
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

    /** 房内全量保存/导出是否允许 (OWN_ONLY 房间下包括房主在内全部禁用, 仅允许退房时保存过滤后的本人图层) */
    fun isFullSaveAllowedInCollab(
        inCollab: Boolean,
        savePolicy: CollabSavePolicy,
    ): Boolean = !inCollab || savePolicy != CollabSavePolicy.OWN_ONLY

    /** OWN_ONLY 退房裁剪规则: 保留背景层、公共层(SHARED)、以及本人专属/未认领层; 剔除他人专属/锁定层 */
    fun shouldKeepLayerOnOwnOnlyExit(
        isBackground: Boolean,
        ownerPeerId: String,
        permissionCode: Int,
        selfPeerId: String,
    ): Boolean {
        if (isBackground) return true
        if (LayerCollabPermission.fromCode(permissionCode) == LayerCollabPermission.SHARED) return true
        if (ownerPeerId.isEmpty()) return true
        return selfPeerId.isNotEmpty() && ownerPeerId == selfPeerId
    }

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

    /**
     * 加入房间/重加载快照后自动选择最上方自己可绘的普通图层:
     * 默认仅匹配本人专属图层 (Magma 风格, 找不到则返回 -1 触发自动建专属层);
     * 当 [fallbackToShared] 为 true 时兜底选最上方可绘公共层。
     */
    fun pickAutoFocusLayerIndex(
        layers: List<CollabLayerDescriptor>,
        selfPeerId: String,
        fallbackToShared: Boolean = false,
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
        if (!fallbackToShared) return -1
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

    /** 生成图层行内紧凑归属简称 (用于专属层徽章展示, 最长 4 字) */
    fun formatShortOwnerLabel(rawName: String, maxChars: Int = 4): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars) + "…"
    }

    /** 生成图层右侧归属圆标的单字头像缩写 (支持 Unicode 码点与英文大写) */
    fun formatAvatarInitial(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return ""
        val firstCp = trimmed.codePointAt(0)
        val charCount = Character.charCount(firstCp)
        return trimmed.substring(0, charCount).uppercase()
    }
}

/** 拓扑模式与确定性房间码 / 选主策略 (与服务端 collab/worker/src/room.js 一致) */
object CollabTopologyPolicy {
    const val DEFAULT_SIGNALING_URL = "https://reverieteapaint.lanrhyme.top"
    const val ROOM_CODE_LENGTH = 6

    /** 服务端未注入专属中继且用户未填自定义节点时的社区引导节点 */
    const val FALLBACK_RELAY_URI = "tcp://public.easytier.top:11010"
    const val STAR_MAX_PEERS = 10
    const val MAX_ROOM_TITLE_LENGTH = 32

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

    fun normalizeNickname(raw: String): String =
        raw.trim().lowercase()

    fun isSameNickname(a: String, b: String): Boolean {
        val na = normalizeNickname(a)
        return na.isNotEmpty() && na == normalizeNickname(b)
    }

    fun hasDuplicateNickname(candidate: String, existingNames: Collection<String>): Boolean =
        existingNames.any { isSameNickname(candidate, it) }

    fun hasDuplicateNicknameExcludingSelf(
        candidate: String,
        summary: CollabRoomSummary,
        selfPeerId: String,
    ): Boolean {
        val otherNames = if (summary.peers.isNotEmpty()) {
            buildList {
                if (summary.hostName.isNotBlank() && summary.hostPeerId != selfPeerId) {
                    add(summary.hostName)
                }
                summary.peers.forEach { peer ->
                    if (peer.peerId != selfPeerId && peer.name.isNotBlank()) {
                        add(peer.name)
                    }
                }
            }
        } else {
            buildList {
                if (summary.hostName.isNotBlank() && summary.hostPeerId != selfPeerId) {
                    add(summary.hostName)
                }
                addAll(summary.peerNames)
            }
        }
        return hasDuplicateNickname(candidate, otherNames)
    }

    fun sanitizeRoomTitle(raw: String): String =
        raw.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_ROOM_TITLE_LENGTH)

    fun isValidPublicRoomTitle(isPublic: Boolean, rawTitle: String): Boolean =
        !isPublic || sanitizeRoomTitle(rawTitle).isNotEmpty()

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

    private fun fnv1a32(raw: String): Long {
        var h = 0x811C9DC5L
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xFFL))
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h
    }

    /**
     * 归一化 EasyTier 中继节点列表:
     * - 丢弃旧版本误填的 TURN/STUN 地址, 只接纳 EasyTier 节点 URI 或裸 host:port
     * - 裸 host:port 补 tcp:// 前缀
     * - 关闭 KCP 加速时剔除 udp:// 节点, 让受限网络真正走纯 TCP
     * - 保序去重
     */
    fun normalizeRelayUris(rawUris: Collection<String>, kcpProxyEnabled: Boolean = true): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in rawUris) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("turn:", ignoreCase = true) ||
                trimmed.startsWith("turns:", ignoreCase = true) ||
                trimmed.startsWith("stun:", ignoreCase = true) ||
                trimmed.startsWith("stuns:", ignoreCase = true)
            ) {
                continue
            }
            val uri = if ("://" in trimmed) trimmed else "tcp://$trimmed"
            if (!kcpProxyEnabled && uri.startsWith("udp://", ignoreCase = true)) continue
            out.add(uri)
        }
        return out.toList()
    }

    fun deriveMeshSubnetByte(roomCode: String): Int =
        10 + (fnv1a32(normalizeRoomCode(roomCode)) % 240L).toInt()

    fun deriveMeshPort(roomCode: String): Int =
        39000 + (fnv1a32(normalizeRoomCode(roomCode)) % 10000L).toInt()

    fun deriveVirtualIp(roomCode: String, peerId: String): String {
        val sb = deriveMeshSubnetByte(roomCode)
        val hb = 10 + (fnv1a32(peerId) % 240L).toInt()
        return "10.144.$sb.$hb"
    }

    fun parseNatType(raw: String): CollabNatType = when (raw.trim()) {
        "OpenInternet" -> CollabNatType.OPEN_INTERNET
        "NoPAT" -> CollabNatType.NO_PAT
        "FullCone" -> CollabNatType.FULL_CONE
        "Restricted" -> CollabNatType.RESTRICTED
        "PortRestricted" -> CollabNatType.PORT_RESTRICTED
        "Symmetric" -> CollabNatType.SYMMETRIC
        "SymUdpFirewall" -> CollabNatType.SYM_UDP_FIREWALL
        "SymmetricEasyInc" -> CollabNatType.SYMMETRIC_EASY_INC
        "SymmetricEasyDec" -> CollabNatType.SYMMETRIC_EASY_DEC
        else -> CollabNatType.UNKNOWN
    }

    fun natDifficultyLevel(natType: CollabNatType): Int = when (natType) {
        CollabNatType.UNKNOWN -> 0
        CollabNatType.OPEN_INTERNET,
        CollabNatType.NO_PAT,
        CollabNatType.FULL_CONE,
        CollabNatType.RESTRICTED -> 1
        CollabNatType.PORT_RESTRICTED,
        CollabNatType.SYMMETRIC_EASY_INC,
        CollabNatType.SYMMETRIC_EASY_DEC -> 2
        CollabNatType.SYMMETRIC,
        CollabNatType.SYM_UDP_FIREWALL -> 3
    }

    private val ROOM_CODE_SEARCH_REGEX = Regex("(?<![A-Z0-9])[A-Z2-9]{6}(?![A-Z0-9])")

    /** 从剪贴板文本或邀请口令中提取 6 位房间码，未命中则返回 null */
    fun extractRoomCodeFromText(raw: String): String? {
        val direct = normalizeRoomCode(raw)
        if (isValidRoomCode(direct)) return direct
        val upper = raw.uppercase()
        return ROOM_CODE_SEARCH_REGEX.find(upper)?.value
    }

    /** 昵称重名时生成不冲突的建议昵称（保留最多 24 个字符） */
    fun suggestUniqueNickname(baseName: String, existingNames: Collection<String>): String {
        val cleanBase = baseName.trim().ifBlank { "Artist" }.take(24)
        if (!hasDuplicateNickname(cleanBase, existingNames)) return cleanBase
        for (suffixNum in 2..99) {
            val suffix = "_$suffixNum"
            val candidate = cleanBase.take(24 - suffix.length) + suffix
            if (!hasDuplicateNickname(candidate, existingNames)) return candidate
        }
        return cleanBase.take(20) + "_new"
    }

    /** 生成便于微信/QQ 转发的完整茶绘邀请文案 */
    fun formatInviteText(
        roomCode: String,
        roomTitle: String,
        hasPassword: Boolean,
        isChinese: Boolean,
    ): String {
        val code = normalizeRoomCode(roomCode)
        val title = roomTitle.trim()
        return if (isChinese) {
            buildString {
                if (title.isNotEmpty()) {
                    append("来 ReveriePaint 茶绘「").append(title).append("」一起画画吧，")
                } else {
                    append("来 ReveriePaint 茶绘一起画画吧，")
                }
                append("房间码：").append(code)
                if (hasPassword) append("（需要密码）")
            }
        } else {
            buildString {
                if (title.isNotEmpty()) {
                    append("Join \"").append(title).append("\" on ReveriePaint Collab, ")
                } else {
                    append("Join my ReveriePaint Collab room, ")
                }
                append("Code: ").append(code)
                if (hasPassword) append(" (Password required)")
            }
        }
    }

    /**
     * 计算使文档坐标 (docX, docY) 恰好位于视口正中心的 (panX, panY) 偏移量，
     * 精确对齐 CanvasView.imageToWidget 的正向旋转与翻转变换
     */
    fun computeCenterPanForDocPoint(
        docX: Float,
        docY: Float,
        docW: Int,
        docH: Int,
        bmpW: Int,
        bmpH: Int,
        zoom: Float,
        fitScale: Float,
        rotationDeg: Float,
        flipX: Boolean = false,
        flipY: Boolean = false,
    ): Pair<Float, Float> {
        val scale = (zoom * fitScale).coerceAtLeast(0.001f)
        val safeDocW = maxOf(1, docW)
        val safeDocH = maxOf(1, docH)
        val signX = if (flipX) -1f else 1f
        val signY = if (flipY) -1f else 1f
        val bx = (docX * (bmpW.toFloat() / safeDocW) - bmpW / 2f) * signX
        val by = (docY * (bmpH.toFloat() / safeDocH) - bmpH / 2f) * signY
        val radians = Math.toRadians(rotationDeg.toDouble())
        val cosR = cos(radians).toFloat()
        val sinR = sin(radians).toFloat()
        val rotX = (bx * scale) * cosR - (by * scale) * sinR
        val rotY = (bx * scale) * sinR + (by * scale) * cosR
        return -rotX to -rotY
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
