/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.core.collab.SignalingClient
import com.reverie.paint.core.collab.SignalingEventListener
import com.reverie.paint.model.CollabCanvasPing
import com.reverie.paint.model.CollabChatMessage
import com.reverie.paint.model.CollabConnectionPhase
import com.reverie.paint.model.CollabCursorDisplayMode
import com.reverie.paint.model.CollabLayerDescriptor
import com.reverie.paint.model.CollabNatType
import com.reverie.paint.model.CollabNetworkDiag
import com.reverie.paint.model.CollabPendingRequest
import com.reverie.paint.model.CollabPeer
import com.reverie.paint.model.CollabPeerRouteDiag
import com.reverie.paint.model.CollabPermissionPolicy
import com.reverie.paint.model.CollabPublicRoom
import com.reverie.paint.model.CollabRole
import com.reverie.paint.model.CollabRouteMode
import com.reverie.paint.model.CollabTopologyPolicy
import com.reverie.paint.model.LayerCollabPermission
import com.reverie.paint.model.LayerNodeType
import com.reverie.paint.model.RemoteCursorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val PREF_COLLAB_NICKNAME = "collab_nickname"
private const val PREF_COLLAB_SIGNALING_URL = "collab_signaling_url"
private const val PREF_COLLAB_PEER_ID = "collab_device_peer_id"
private const val PREF_COLLAB_TURN_URL = "collab_turn_url"
private const val PREF_COLLAB_TURN_USER = "collab_turn_user"
private const val PREF_COLLAB_TURN_CRED = "collab_turn_cred"
private const val PREF_COLLAB_KCP_PROXY = "collab_kcp_proxy"
private const val PREF_COLLAB_CURSOR_MODE = "collab_cursor_mode"

private const val COLLAB_CHAT_BANNER_DURATION_MS = 4000L
private const val COLLAB_PING_DURATION_MS = 2500L

private const val COLLAB_DRAIN_INTERVAL_MS = 16L
private const val COLLAB_CURSOR_THROTTLE_MS = 33L
private const val COLLAB_HOST_OFFLINE_CLAIM_MS = 5600L
// 国内连 Cloudflare Workers 的 TLS + WS 握手偶尔接近 4~5s, 窗口过短会在刚连上时被自己掐断重拨
private const val COLLAB_RECONNECT_HANDSHAKE_MS = 12_000L

/**
 * 多人茶绘 (Magma 式 P2P 协同) 的 ViewModel 状态容器。
 */
internal class CollabSessionState {
    var isActive by mutableStateOf(false)
    var phase by mutableStateOf(CollabConnectionPhase.IDLE)
    var role by mutableStateOf(CollabRole.HOST)
    var roomCode by mutableStateOf("")
    var selfPeerId by mutableStateOf("")
    var selfName by mutableStateOf("")
    var hostPeerId by mutableStateOf("")
    var hostToken by mutableStateOf("")
    var signalingUrl by mutableStateOf(CollabTopologyPolicy.DEFAULT_SIGNALING_URL)
    var customTurnUrl by mutableStateOf("")
    var customTurnUsername by mutableStateOf("")
    var customTurnCredential by mutableStateOf("")
    /** 高级网络: 是否启用 EasyTier KCP 代理 (UDP 被 QoS 限速的网络可关闭改走纯 TCP 中继) */
    var kcpProxyEnabled by mutableStateOf(true)
    var requireApproval by mutableStateOf(false)
    var maxPeers by mutableIntStateOf(10)
    var isPublicRoom by mutableStateOf(false)
    var roomTitle by mutableStateOf("")
    var peers by mutableStateOf<List<CollabPeer>>(emptyList())
    var departedPeers by mutableStateOf<Map<String, String>>(emptyMap())
    var pendingRequests by mutableStateOf<List<CollabPendingRequest>>(emptyList())
    var remoteCursors by mutableStateOf<List<RemoteCursorState>>(emptyList())
    var snapshotReceivedChunks by mutableIntStateOf(0)
    var snapshotTotalChunks by mutableIntStateOf(0)
    var statusMessage by mutableStateOf<String?>(null)
    var reconnectAttempt by mutableIntStateOf(0)
    var networkDiag by mutableStateOf(CollabNetworkDiag())
    var publicRooms by mutableStateOf<List<CollabPublicRoom>>(emptyList())
    var isLoadingPublicRooms by mutableStateOf(false)
    var publicRoomsError by mutableStateOf<String?>(null)

    var showLobbyDialog by mutableStateOf(false)
    var showRoomSheet by mutableStateOf(false)
    var showEndSaveDialog by mutableStateOf(false)
    var endSaveDefaultName by mutableStateOf("")
    var savedRoomCodeForDialog by mutableStateOf("")

    // UX 增强运行时状态
    var cursorDisplayMode by mutableStateOf(CollabCursorDisplayMode.FULL)
    var chatMessages by mutableStateOf<List<CollabChatMessage>>(emptyList())
    var latestChatBanner by mutableStateOf<CollabChatMessage?>(null)
    var canvasPings by mutableStateOf<List<CollabCanvasPing>>(emptyList())
    var pendingFocusDocPoint by mutableStateOf<Pair<Float, Float>?>(null)
    var pendingPingAtViewportCenter by mutableStateOf(false)
    var duplicateSuggestedName by mutableStateOf<String?>(null)
    var lastDuplicateRoomCode by mutableStateOf("")
    var lastDuplicatePassword by mutableStateOf("")
    var roomHasPassword by mutableStateOf(false)
    var kcpNeedsReconnectHint by mutableStateOf(false)

    // CreatePage 联动开房参数 (创建房间需先配置并新建画布)
    var pendingCreateMode by mutableStateOf(false)
    var pendingCreatePassword by mutableStateOf("")
    var pendingCreateRequireApproval by mutableStateOf(false)
    var pendingCreateMaxPeers by mutableIntStateOf(10)
    var pendingCreateIsPublic by mutableStateOf(false)
    var pendingCreateTitle by mutableStateOf("")

    internal var sessionGeneration: Long = 0L
    internal var signalingClient: SignalingClient? = null
    internal var drainJob: Job? = null
    internal var reconnectJob: Job? = null
    internal var hostOfflineTimerJob: Job? = null
    internal var publicRoomsLoadJob: Job? = null
    internal var chatBannerClearJob: Job? = null
    internal var pingPruneJob: Job? = null
    internal val drainInFlight = AtomicBoolean(false)

    @Volatile
    internal var nativeStarted: Boolean = false
    internal val signalLock = Any()
    internal val pendingSignals = ArrayList<Pair<String, String>>()

    internal var lastJoinPassword: String = ""
    internal var lastIceServersJson: String = "[]"
    internal var lastEasyTierJson: String = "{}"
    internal var localEndpointUris: List<String> = emptyList()
    internal var lastNetDiagPollMs: Long = 0L
    internal var intentionalLeave: Boolean = false
    internal var lastCursorSentMs: Long = 0L
    internal var lastCursorDocX: Float = 0f
    internal var lastCursorDocY: Float = 0f
    internal var prefsLoaded: Boolean = false
    internal var pendingAutoFocusOnSnapshot: Boolean = false
    internal var pendingJoinCanvasW: Int = 0
    internal var pendingJoinCanvasH: Int = 0
}

internal fun PaintViewModel.ensureCollabPrefsLoaded() {
    if (collab.prefsLoaded || !isAppContextReady()) return
    collab.prefsLoaded = true
    val savedName = prefs.getString(PREF_COLLAB_NICKNAME, null).orEmpty().trim()
    collab.selfName = savedName.ifBlank { getString(R.string.collab_nickname_default) }
    val savedUrl = prefs.getString(PREF_COLLAB_SIGNALING_URL, null).orEmpty().trim()
    collab.signalingUrl = savedUrl.ifBlank { CollabTopologyPolicy.DEFAULT_SIGNALING_URL }
    collab.customTurnUrl = prefs.getString(PREF_COLLAB_TURN_URL, null).orEmpty().trim()
    collab.customTurnUsername = prefs.getString(PREF_COLLAB_TURN_USER, null).orEmpty().trim()
    collab.customTurnCredential = prefs.getString(PREF_COLLAB_TURN_CRED, null).orEmpty()
    collab.kcpProxyEnabled = prefs.getBoolean(PREF_COLLAB_KCP_PROXY, true)
    collab.cursorDisplayMode = CollabCursorDisplayMode.fromCode(
        prefs.getInt(PREF_COLLAB_CURSOR_MODE, CollabCursorDisplayMode.FULL.code),
    )

    var peerId = prefs.getString(PREF_COLLAB_PEER_ID, null).orEmpty().trim()
    if (peerId.isBlank()) {
        peerId = "p_" + UUID.randomUUID().toString().replace("-", "").take(10)
        prefs.edit().putString(PREF_COLLAB_PEER_ID, peerId).apply()
    }
    collab.selfPeerId = peerId
}

internal fun PaintViewModel.saveCollabIdentityPrefs(
    nickname: String,
    signalingUrl: String,
    turnUrl: String = collab.customTurnUrl,
    turnUsername: String = collab.customTurnUsername,
    turnCredential: String = collab.customTurnCredential,
) {
    val cleanName = nickname.trim().ifBlank { getString(R.string.collab_nickname_default) }
    val cleanUrl = signalingUrl.trim().trimEnd('/').ifBlank { CollabTopologyPolicy.DEFAULT_SIGNALING_URL }
    val cleanTurnUrl = turnUrl.trim()
    val cleanTurnUser = turnUsername.trim()
    collab.selfName = cleanName
    collab.signalingUrl = cleanUrl
    collab.customTurnUrl = cleanTurnUrl
    collab.customTurnUsername = cleanTurnUser
    collab.customTurnCredential = turnCredential
    if (isAppContextReady()) {
        prefs.edit()
            .putString(PREF_COLLAB_NICKNAME, cleanName)
            .putString(PREF_COLLAB_SIGNALING_URL, cleanUrl)
            .putString(PREF_COLLAB_TURN_URL, cleanTurnUrl)
            .putString(PREF_COLLAB_TURN_USER, cleanTurnUser)
            .putString(PREF_COLLAB_TURN_CRED, turnCredential)
            .apply()
    }
}

/**
 * 高级网络: 切换 EasyTier KCP 代理开关 (下一次建立/重建网络时生效)。
 * 默认开启; 移动网络对 UDP 做 QoS 限速时关闭可改走纯 TCP 中继。
 */
internal fun PaintViewModel.setCollabKcpProxyEnabled(enabled: Boolean) {
    if (collab.kcpProxyEnabled == enabled) return
    collab.kcpProxyEnabled = enabled
    if (isAppContextReady()) {
        prefs.edit().putBoolean(PREF_COLLAB_KCP_PROXY, enabled).apply()
    }
    if (collab.isActive) {
        collab.kcpNeedsReconnectHint = true
        if (collab.roomCode.isNotEmpty()) {
            collab.lastEasyTierJson = buildEffectiveTransportConfigJson(
                roomCode = collab.roomCode,
                selfPeerId = collab.selfPeerId,
                hostPeerId = collab.hostPeerId,
                baseEasyTierJson = collab.lastEasyTierJson,
            )
        }
    }
}

internal fun PaintViewModel.setCollabCursorDisplayMode(mode: CollabCursorDisplayMode) {
    if (collab.cursorDisplayMode == mode) return
    collab.cursorDisplayMode = mode
    if (isAppContextReady()) {
        prefs.edit().putInt(PREF_COLLAB_CURSOR_MODE, mode.code).apply()
    }
}

private fun PaintViewModel.buildEffectiveTransportConfigJson(
    roomCode: String,
    selfPeerId: String,
    hostPeerId: String,
    baseEasyTierJson: String,
    peers: List<CollabPeer> = collab.peers,
): String {
    val root = runCatching { JSONObject(baseEasyTierJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    val normCode = CollabTopologyPolicy.normalizeRoomCode(roomCode)
    if (root.optString("networkName").isBlank() && normCode.isNotEmpty()) {
        root.put("networkName", "rvp-$normCode")
    }
    if (root.optString("selfVirtualIp").isBlank() && normCode.isNotEmpty() && selfPeerId.isNotEmpty()) {
        val selfPeerVip = peers.firstOrNull { it.peerId == selfPeerId }?.virtualIp.orEmpty()
        root.put(
            "selfVirtualIp",
            selfPeerVip.ifBlank { CollabTopologyPolicy.deriveVirtualIp(normCode, selfPeerId) },
        )
    }
    if (normCode.isNotEmpty() && hostPeerId.isNotEmpty()) {
        val hostVipFromPeers = peers.firstOrNull { it.peerId == hostPeerId }?.virtualIp.orEmpty()
        if (root.optString("hostVirtualIp").isBlank() || hostVipFromPeers.isNotBlank()) {
            root.put(
                "hostVirtualIp",
                hostVipFromPeers.ifBlank {
                    root.optString("hostVirtualIp").ifBlank {
                        CollabTopologyPolicy.deriveVirtualIp(normCode, hostPeerId)
                    }
                },
            )
        }
    }
    if (root.optInt("hostMeshPort", 0) <= 1024 && normCode.isNotEmpty()) {
        root.put("hostMeshPort", CollabTopologyPolicy.deriveMeshPort(normCode))
    }

    val relayCandidates = ArrayList<String>()
    root.optJSONArray("relayPeers")?.let { arr ->
        for (i in 0 until arr.length()) relayCandidates.add(arr.optString(i))
    }
    if (collab.customTurnUrl.isNotBlank()) {
        collab.customTurnUrl.split(',').forEach { relayCandidates.add(it) }
    }
    var relays = CollabTopologyPolicy.normalizeRelayUris(relayCandidates, collab.kcpProxyEnabled)
    // 公共节点兜底 (当服务端未注入专属中继且用户未填自定义节点时, 提供社区引导节点)
    if (relays.isEmpty()) {
        relays = CollabTopologyPolicy.normalizeRelayUris(
            listOf(CollabTopologyPolicy.FALLBACK_RELAY_URI),
            collab.kcpProxyEnabled,
        )
    }
    root.put("relayPeers", JSONArray(relays))

    val stuns = root.optJSONArray("stunServers") ?: JSONArray()
    if (stuns.length() == 0) {
        stuns.put("stun.hitv.com")
        stuns.put("stun.miwifi.com")
        stuns.put("stun.chat.bilibili.com")
        stuns.put("txt:stun.easytier.cn")
        root.put("stunServers", stuns)
    }
    root.put("enableKcpProxy", collab.kcpProxyEnabled)
    root.put("latencyFirst", true)
    return root.toString()
}

private fun PaintViewModel.mergeEffectiveIceServers(baseJson: String): String {
    val arr = runCatching { JSONArray(baseJson.ifBlank { "[]" }) }.getOrNull() ?: JSONArray()
    val turnUrl = collab.customTurnUrl.trim()
    if (turnUrl.isNotEmpty()) {
        val turnObj = JSONObject().apply {
            put("urls", turnUrl)
            if (collab.customTurnUsername.isNotEmpty()) put("username", collab.customTurnUsername)
            if (collab.customTurnCredential.isNotEmpty()) put("credential", collab.customTurnCredential)
        }
        arr.put(turnObj)
    }
    return arr.toString()
}

/**
 * 从主页发起创建茶绘房间: 强制先经过新建画布流程设置画布尺寸与模板。
 */
internal fun PaintViewModel.openCreateCanvasForCollab(
    nickname: String,
    password: String,
    requireApproval: Boolean,
    maxPeers: Int,
    signalingUrl: String,
    turnUrl: String = collab.customTurnUrl,
    turnUsername: String = collab.customTurnUsername,
    turnCredential: String = collab.customTurnCredential,
    isPublic: Boolean = false,
    roomTitle: String = "",
) {
    ensureCollabPrefsLoaded()
    saveCollabIdentityPrefs(nickname, signalingUrl, turnUrl, turnUsername, turnCredential)
    val cleanTitle = CollabTopologyPolicy.sanitizeRoomTitle(roomTitle)
    if (!CollabTopologyPolicy.isValidPublicRoomTitle(isPublic, cleanTitle)) {
        val msg = getString(R.string.collab_err_room_title_required)
        collab.phase = CollabConnectionPhase.ERROR
        collab.statusMessage = msg
        showActionToast(msg, R.drawable.ic_cloud)
        return
    }
    collab.pendingCreateMode = true
    collab.pendingCreatePassword = password
    collab.pendingCreateRequireApproval = requireApproval
    collab.pendingCreateMaxPeers = maxPeers.coerceIn(2, 32)
    collab.pendingCreateIsPublic = isPublic
    collab.pendingCreateTitle = if (isPublic) cleanTitle else ""
    collab.showLobbyDialog = false
    goCreate()
}

internal fun PaintViewModel.hostRoomOnCreatedCanvas(canvasW: Int, canvasH: Int) {
    val pw = collab.pendingCreatePassword
    val approval = collab.pendingCreateRequireApproval
    val maxP = collab.pendingCreateMaxPeers
    val isPublic = collab.pendingCreateIsPublic
    val roomTitle = collab.pendingCreateTitle
    collab.pendingCreateMode = false
    createCollabRoom(
        nickname = collab.selfName,
        password = pw,
        requireApproval = approval,
        maxPeers = maxP,
        signalingUrl = collab.signalingUrl,
        canvasW = canvasW,
        canvasH = canvasH,
        isPublic = isPublic,
        roomTitle = roomTitle,
    )
}

internal fun PaintViewModel.createCollabRoom(
    nickname: String,
    password: String = "",
    requireApproval: Boolean = false,
    maxPeers: Int = 10,
    signalingUrl: String = collab.signalingUrl,
    canvasW: Int = docWidth,
    canvasH: Int = docHeight,
    isPublic: Boolean = false,
    roomTitle: String = "",
) {
    ensureCollabPrefsLoaded()
    saveCollabIdentityPrefs(nickname, signalingUrl)

    val cleanTitle = CollabTopologyPolicy.sanitizeRoomTitle(roomTitle)
    if (!CollabTopologyPolicy.isValidPublicRoomTitle(isPublic, cleanTitle)) {
        val msg = getString(R.string.collab_err_room_title_required)
        collab.phase = CollabConnectionPhase.ERROR
        collab.statusMessage = msg
        showActionToast(msg, R.drawable.ic_cloud)
        return
    }

    if (currentPage != Page.PAINTING) {
        openCreateCanvasForCollab(
            nickname = nickname,
            password = password,
            requireApproval = requireApproval,
            maxPeers = maxPeers,
            signalingUrl = signalingUrl,
            isPublic = isPublic,
            roomTitle = cleanTitle,
        )
        return
    }

    if (collab.isActive) {
        teardownCollabLocalState(gracefulLeave = true)
    }

    val gen = ++collab.sessionGeneration
    val selfId = collab.selfPeerId
    val displayName = collab.selfName
    val baseUrl = collab.signalingUrl
    val clampedMax = maxPeers.coerceIn(2, 32)
    val safeW = canvasW.coerceAtLeast(1)
    val safeH = canvasH.coerceAtLeast(1)
    val effectiveTitle = if (isPublic) cleanTitle else ""

    collab.intentionalLeave = false
    collab.lastJoinPassword = password
    collab.roomHasPassword = password.isNotEmpty()
    collab.kcpNeedsReconnectHint = false
    collab.duplicateSuggestedName = null
    collab.reconnectAttempt = 0
    collab.isActive = true
    collab.phase = CollabConnectionPhase.CREATING
    collab.role = CollabRole.HOST
    collab.requireApproval = requireApproval
    collab.maxPeers = clampedMax
    collab.isPublicRoom = isPublic
    collab.roomTitle = effectiveTitle
    collab.hostPeerId = selfId
    collab.hostToken = ""
    collab.roomCode = ""
    collab.peers = listOf(CollabPeer(peerId = selfId, name = displayName, role = CollabRole.HOST, connectedP2P = true))
    collab.departedPeers = emptyMap()
    collab.pendingAutoFocusOnSnapshot = false
    collab.pendingRequests = emptyList()
    collab.remoteCursors = emptyList()
    collab.snapshotReceivedChunks = 0
    collab.snapshotTotalChunks = 0
    collab.statusMessage = getString(R.string.collab_status_connecting)

    val client = SignalingClient(viewModelScope)
    collab.signalingClient = client

    viewModelScope.launch {
        val result = client.createRoom(
            baseUrl = baseUrl,
            hostPeerId = selfId,
            hostName = displayName,
            password = password,
            requireApproval = requireApproval,
            maxPeers = clampedMax,
            canvasWidth = safeW,
            canvasHeight = safeH,
            isPublic = isPublic,
            title = effectiveTitle,
        )
        if (collab.sessionGeneration != gen) return@launch
        result.onFailure { err ->
            val msg = localizeCollabError(err.message ?: "ws_failure", "")
            collab.phase = CollabConnectionPhase.ERROR
            collab.isActive = false
            collab.statusMessage = msg
            showActionToast(msg, R.drawable.ic_cloud)
        }.onSuccess { created ->
            collab.roomCode = created.roomCode
            collab.hostPeerId = created.hostPeerId
            collab.hostToken = created.hostToken
            collab.lastIceServersJson = mergeEffectiveIceServers(created.iceServersJson)
            collab.lastEasyTierJson = buildEffectiveTransportConfigJson(
                roomCode = created.roomCode,
                selfPeerId = selfId,
                hostPeerId = created.hostPeerId,
                baseEasyTierJson = created.easyTierJson,
            )
            client.connectWebSocket(
                baseUrl = baseUrl,
                roomCode = created.roomCode,
                peerId = selfId,
                role = CollabRole.HOST,
                hostToken = created.hostToken,
                name = displayName,
                password = password.ifEmpty { null },
                listener = buildSignalingListener(gen),
            )
        }
    }
}

internal fun PaintViewModel.joinCollabRoom(
    rawRoomCode: String,
    nickname: String,
    password: String = "",
    signalingUrl: String = collab.signalingUrl,
    turnUrl: String = collab.customTurnUrl,
    turnUsername: String = collab.customTurnUsername,
    turnCredential: String = collab.customTurnCredential,
) {
    ensureCollabPrefsLoaded()
    saveCollabIdentityPrefs(nickname, signalingUrl, turnUrl, turnUsername, turnCredential)

    val normCode = CollabTopologyPolicy.normalizeRoomCode(rawRoomCode)
    if (!CollabTopologyPolicy.isValidRoomCode(normCode)) {
        collab.phase = CollabConnectionPhase.ERROR
        collab.statusMessage = getString(R.string.collab_room_code_hint)
        showActionToast(collab.statusMessage!!, R.drawable.ic_cloud)
        return
    }

    if (collab.isActive) {
        teardownCollabLocalState(gracefulLeave = true)
    }

    val gen = ++collab.sessionGeneration
    val selfId = collab.selfPeerId
    val displayName = collab.selfName
    val baseUrl = collab.signalingUrl

    collab.intentionalLeave = false
    collab.lastJoinPassword = password
    collab.roomHasPassword = password.isNotEmpty()
    collab.kcpNeedsReconnectHint = false
    collab.duplicateSuggestedName = null
    collab.reconnectAttempt = 0
    collab.isActive = true
    collab.phase = CollabConnectionPhase.JOINING
    collab.role = CollabRole.PEER
    collab.roomCode = normCode
    collab.hostToken = ""
    collab.isPublicRoom = false
    collab.roomTitle = ""
    collab.peers = emptyList()
    collab.departedPeers = emptyMap()
    collab.pendingAutoFocusOnSnapshot = true
    collab.pendingRequests = emptyList()
    collab.remoteCursors = emptyList()
    collab.snapshotReceivedChunks = 0
    collab.snapshotTotalChunks = 0
    collab.pendingJoinCanvasW = 0
    collab.pendingJoinCanvasH = 0
    collab.statusMessage = getString(R.string.collab_status_connecting)

    val client = SignalingClient(viewModelScope)
    collab.signalingClient = client

    viewModelScope.launch {
        val infoResult = client.fetchRoomInfo(baseUrl = baseUrl, roomCode = normCode)
        if (collab.sessionGeneration != gen) return@launch
        infoResult.onFailure { err ->
            val msg = localizeCollabError(err.message ?: "room_not_found", "")
            collab.phase = CollabConnectionPhase.ERROR
            collab.isActive = false
            collab.signalingClient = null
            collab.statusMessage = msg
            showActionToast(msg, R.drawable.ic_cloud)
        }.onSuccess { summary ->
            val failJoinWith: (String) -> Unit = { errText ->
                collab.phase = CollabConnectionPhase.ERROR
                collab.isActive = false
                collab.signalingClient = null
                collab.statusMessage = errText
                showActionToast(errText, R.drawable.ic_cloud)
            }
            if (summary.hasPassword && password.isBlank()) {
                failJoinWith(getString(R.string.collab_err_password_required))
                return@onSuccess
            }
            if (summary.peerCount >= summary.maxPeers) {
                failJoinWith(getString(R.string.collab_err_room_full))
                return@onSuccess
            }
            val existingNames = buildList {
                if (summary.hostName.isNotBlank() && summary.hostPeerId != selfId) add(summary.hostName)
                addAll(summary.peerNames)
            }
            if (CollabTopologyPolicy.hasDuplicateNickname(displayName, existingNames)) {
                collab.duplicateSuggestedName = CollabTopologyPolicy.suggestUniqueNickname(displayName, existingNames)
                collab.lastDuplicateRoomCode = normCode
                collab.lastDuplicatePassword = password
                failJoinWith(getString(R.string.collab_err_duplicate_name))
                return@onSuccess
            }
            collab.roomHasPassword = summary.hasPassword
            collab.hostPeerId = summary.hostPeerId
            collab.requireApproval = summary.requireApproval
            collab.maxPeers = summary.maxPeers
            collab.isPublicRoom = summary.isPublic
            collab.roomTitle = summary.title
            collab.pendingJoinCanvasW = summary.canvasWidth
            collab.pendingJoinCanvasH = summary.canvasHeight
            client.connectWebSocket(
                baseUrl = baseUrl,
                roomCode = normCode,
                peerId = selfId,
                role = CollabRole.PEER,
                hostToken = null,
                name = displayName,
                password = password.ifEmpty { null },
                listener = buildSignalingListener(gen),
            )
        }
    }
}

internal fun PaintViewModel.refreshPublicCollabRooms(signalingUrlOverride: String? = null) {
    ensureCollabPrefsLoaded()
    val rawUrl = signalingUrlOverride ?: collab.signalingUrl
    val baseUrl = rawUrl.trim().trimEnd('/').ifBlank { CollabTopologyPolicy.DEFAULT_SIGNALING_URL }
    collab.publicRoomsLoadJob?.cancel()
    collab.isLoadingPublicRooms = true
    collab.publicRoomsError = null
    val client = SignalingClient(viewModelScope)
    collab.publicRoomsLoadJob = viewModelScope.launch {
        val result = client.fetchPublicRooms(baseUrl = baseUrl)
        collab.isLoadingPublicRooms = false
        result.onSuccess { rooms ->
            collab.publicRooms = rooms
            collab.publicRoomsError = null
        }.onFailure { err ->
            collab.publicRoomsError = localizeCollabError(err.message ?: "ws_failure", "")
        }
    }
}

/**
 * 统一的信令重连入口: 房主身份但本地 hostToken 丢失时先以 peer 身份连,
 * 由服务端在认领窗口内补发新 token, 避免必然失败的 403 往返
 */
private fun PaintViewModel.connectSignalingAsRole(
    gen: Long,
    client: SignalingClient,
    tag: String,
) {
    val wantHost = collab.role == CollabRole.HOST
    val effectiveRole = if (wantHost && collab.hostToken.isBlank()) CollabRole.PEER else collab.role
    if (wantHost && effectiveRole == CollabRole.PEER) {
        android.util.Log.w(
            "ReverieCollab",
            "$tag: host role without local hostToken, reconnect as peer to reclaim",
        )
    }
    android.util.Log.i(
        "ReverieCollab",
        "$tag: connect room=${collab.roomCode} role=$effectiveRole attempt=${collab.reconnectAttempt}",
    )
    client.connectWebSocket(
        baseUrl = collab.signalingUrl,
        roomCode = collab.roomCode,
        peerId = collab.selfPeerId,
        role = effectiveRole,
        hostToken = collab.hostToken.ifBlank { null },
        name = collab.selfName,
        password = collab.lastJoinPassword.ifEmpty { null },
        listener = buildSignalingListener(gen),
    )
}

internal fun PaintViewModel.reconnectCollabNow() {
    if (!collab.isActive || collab.roomCode.isBlank()) return
    collab.reconnectJob?.cancel()
    collab.reconnectJob = null
    collab.reconnectAttempt = 0
    collab.kcpNeedsReconnectHint = false
    collab.phase = CollabConnectionPhase.RECONNECTING
    collab.statusMessage = getString(R.string.collab_status_connecting)

    val gen = collab.sessionGeneration
    val client = collab.signalingClient ?: SignalingClient(viewModelScope).also {
        collab.signalingClient = it
    }
    connectSignalingAsRole(gen, client, "manual-reconnect")

    if (collab.nativeStarted && collab.role != CollabRole.HOST && collab.hostPeerId.isNotBlank()) {
        runCore(render = false) {
            ReverieCoreBridge.collabRetryPeer(collab.hostPeerId)
        }
    }
}

private fun PaintViewModel.scheduleAutoReconnect(gen: Long) {
    if (collab.intentionalLeave || !collab.isActive || collab.roomCode.isBlank()) return
    if (collab.reconnectJob?.isActive == true) return
    collab.reconnectJob = viewModelScope.launch {
        val backoffs = longArrayOf(1500L, 3000L, 5000L, 8000L, 12000L)
        while (isActive && collab.sessionGeneration == gen && collab.isActive && !collab.intentionalLeave) {
            val attempt = collab.reconnectAttempt
            val waitMs = backoffs[attempt.coerceAtMost(backoffs.lastIndex)]
            collab.reconnectAttempt = attempt + 1
            collab.phase = CollabConnectionPhase.RECONNECTING
            collab.statusMessage = getString(R.string.collab_reconnecting_msg, collab.reconnectAttempt)
            delay(waitMs)
            if (collab.sessionGeneration != gen || !collab.isActive || collab.intentionalLeave) break

            val client = collab.signalingClient ?: SignalingClient(viewModelScope).also {
                collab.signalingClient = it
            }
            connectSignalingAsRole(gen, client, "auto-reconnect")
            // 等待本次建连落地或下次超时重拨
            delay(COLLAB_RECONNECT_HANDSHAKE_MS)
            if (collab.phase == CollabConnectionPhase.CONNECTED) break
        }
    }
}

private fun PaintViewModel.localizeCollabError(code: String, rawMessage: String): String = when (code) {
    "duplicate_name" -> getString(R.string.collab_err_duplicate_name)
    "room_title_required" -> getString(R.string.collab_err_room_title_required)
    "invalid_password" -> getString(R.string.collab_err_invalid_password)
    "room_full" -> getString(R.string.collab_err_room_full)
    "room_not_found", "not_found", "http_404" -> getString(R.string.collab_err_room_not_found)
    "kicked" -> getString(R.string.collab_kicked_msg)
    "invalid_host_token" -> getString(R.string.collab_err_host_auth)
    "peer_id_conflict" -> getString(R.string.collab_err_peer_id_conflict)
    "ws_failure" -> getString(R.string.collab_err_network)
    "internal", "http_500" -> getString(R.string.collab_err_server)
    else -> {
        val lower = rawMessage.lowercase()
        if ("timeout" in lower || "timed out" in lower) {
            getString(R.string.collab_err_timeout)
        } else {
            getString(R.string.collab_status_error, rawMessage.ifBlank { code })
        }
    }
}

private fun PaintViewModel.handleCollabAdmittedOrWelcome(
    gen: Long,
    roomCode: String,
    selfPeerId: String,
    hostPeerId: String,
    iceServersJson: String,
    peers: List<CollabPeer>,
    canvasWidth: Int,
    canvasHeight: Int,
    easyTierJson: String = "{}",
) {
    if (!collab.nativeStarted &&
        selfPeerId != hostPeerId &&
        peers.any { it.peerId != selfPeerId && CollabTopologyPolicy.isSameNickname(it.name, collab.selfName) }
    ) {
        val dupMsg = getString(R.string.collab_err_duplicate_name)
        val suggested = CollabTopologyPolicy.suggestUniqueNickname(
            collab.selfName,
            peers.filter { it.peerId != selfPeerId }.map { it.name },
        )
        val savedCode = collab.roomCode
        val savedPw = collab.lastJoinPassword
        teardownCollabLocalState(gracefulLeave = true)
        collab.duplicateSuggestedName = suggested
        collab.lastDuplicateRoomCode = savedCode
        collab.lastDuplicatePassword = savedPw
        collab.phase = CollabConnectionPhase.ERROR
        collab.statusMessage = dupMsg
        if (currentPage != Page.PAINTING) {
            collab.showLobbyDialog = true
        }
        showActionToast(dupMsg, R.drawable.ic_cloud)
        return
    }

    collab.reconnectJob?.cancel()
    collab.reconnectJob = null
    collab.reconnectAttempt = 0
    collab.duplicateSuggestedName = null
    collab.lastDuplicateRoomCode = ""
    collab.lastDuplicatePassword = ""
    collab.kcpNeedsReconnectHint = false
    collab.roomCode = roomCode
    collab.selfPeerId = selfPeerId
    collab.hostPeerId = hostPeerId
    collab.role = if (selfPeerId == hostPeerId) CollabRole.HOST else CollabRole.PEER
    val p2pStates = collab.peers.associate { it.peerId to it.connectedP2P }
    val mergedPeers = normalizePeersWithFlags(peers, hostPeerId, p2pStates)
    collab.peers = mergedPeers

    collab.lastIceServersJson = mergeEffectiveIceServers(iceServersJson)
    val effectiveBaseEt = if (easyTierJson.isNotBlank() && easyTierJson != "{}") easyTierJson else collab.lastEasyTierJson
    val effTransport = buildEffectiveTransportConfigJson(
        roomCode = roomCode,
        selfPeerId = selfPeerId,
        hostPeerId = hostPeerId,
        baseEasyTierJson = effectiveBaseEt,
        peers = mergedPeers,
    )
    collab.lastEasyTierJson = effTransport
    collab.phase = CollabConnectionPhase.CONNECTED
    collab.statusMessage = null
    collab.showLobbyDialog = false
    if (!collab.nativeStarted) {
        if (currentPage != Page.PAINTING) {
            val targetW = canvasWidth.takeIf { it > 0 }
                ?: collab.pendingJoinCanvasW.takeIf { it > 0 }
                ?: 1600
            val targetH = canvasHeight.takeIf { it > 0 }
                ?: collab.pendingJoinCanvasH.takeIf { it > 0 }
                ?: 1200
            startPainting(w = targetW, h = targetH, name = "Collab-$roomCode")
        }
        if (collab.role == CollabRole.HOST) {
            collab.showRoomSheet = true
        } else {
            showActionToast(R.string.collab_joined_toast, R.drawable.ic_cloud)
        }
        beginNativeCollabSession(gen, roomCode, selfPeerId, collab.selfName, hostPeerId, effTransport, mergedPeers)
    } else {
        syncNativeOnSignalingReconnect(gen, hostPeerId, effTransport, mergedPeers)
    }
}

private fun PaintViewModel.buildSignalingListener(gen: Long): SignalingEventListener {
    return object : SignalingEventListener {
        override fun onWelcome(
            roomCode: String,
            selfPeerId: String,
            hostPeerId: String,
            status: String,
            iceServersJson: String,
            peers: List<CollabPeer>,
            canvasWidth: Int,
            canvasHeight: Int,
            easyTierJson: String,
        ) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                if (status == "pending" || status == "waiting_approval") {
                    collab.reconnectJob?.cancel()
                    collab.reconnectJob = null
                    collab.reconnectAttempt = 0
                    collab.roomCode = roomCode
                    collab.selfPeerId = selfPeerId
                    collab.hostPeerId = hostPeerId
                    collab.role = if (selfPeerId == hostPeerId) CollabRole.HOST else CollabRole.PEER
                    collab.lastIceServersJson = mergeEffectiveIceServers(iceServersJson)
                    collab.lastEasyTierJson = easyTierJson
                    collab.phase = CollabConnectionPhase.WAITING_APPROVAL
                    collab.statusMessage = getString(R.string.collab_status_waiting_approval)
                } else {
                    handleCollabAdmittedOrWelcome(
                        gen = gen,
                        roomCode = roomCode,
                        selfPeerId = selfPeerId,
                        hostPeerId = hostPeerId,
                        iceServersJson = iceServersJson,
                        peers = peers,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        easyTierJson = easyTierJson,
                    )
                }
            }
        }

        override fun onAdmitted(
            roomCode: String,
            selfPeerId: String,
            hostPeerId: String,
            iceServersJson: String,
            peers: List<CollabPeer>,
            canvasWidth: Int,
            canvasHeight: Int,
            easyTierJson: String,
        ) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                handleCollabAdmittedOrWelcome(
                    gen = gen,
                    roomCode = roomCode,
                    selfPeerId = selfPeerId,
                    hostPeerId = hostPeerId,
                    iceServersJson = iceServersJson,
                    peers = peers,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    easyTierJson = easyTierJson,
                )
            }
        }

        override fun onJoinRequest(request: CollabPendingRequest) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val existing = collab.pendingRequests.filterNot { it.peerId == request.peerId }
                collab.pendingRequests = existing + request
                // 非阻塞提示: 不再强弹全屏弹窗打断房主运笔，由画布顶部横幅承接审批
            }
        }

        override fun onPeerJoined(peer: CollabPeer) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val alreadyInPeers = collab.peers.any { it.peerId == peer.peerId }
                if (peer.peerId in collab.departedPeers) {
                    collab.departedPeers = collab.departedPeers - peer.peerId
                }
                val p2pStates = collab.peers.associate { it.peerId to it.connectedP2P }
                val updated = collab.peers.filterNot { it.peerId == peer.peerId } + peer.copy(signalingOnline = true)
                collab.peers = normalizePeersWithFlags(updated, collab.hostPeerId, p2pStates)
                if (peer.peerId != collab.selfPeerId) {
                    if (collab.nativeStarted && !alreadyInPeers) {
                        showActionToast(getString(R.string.collab_peer_joined_toast, peer.name), R.drawable.ic_cloud)
                    }
                    runCore(render = false) {
                        if (collab.sessionGeneration == gen && collab.nativeStarted) {
                            ReverieCoreBridge.collabOnPeerJoined(
                                peer.peerId,
                                peer.name,
                                peer.role == CollabRole.HOST,
                            )
                        }
                    }
                    if (collab.localEndpointUris.isNotEmpty() &&
                        (collab.role == CollabRole.HOST || peer.peerId == collab.hostPeerId)) {
                        val payload = JSONObject().apply {
                            put("kind", "et_endpoints")
                            put("uris", JSONArray(collab.localEndpointUris))
                        }
                        collab.signalingClient?.sendSignal(peer.peerId, payload)
                    }
                }
            }
        }

        override fun onPeerLeft(peerId: String, reason: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val leftPeer = collab.peers.firstOrNull { it.peerId == peerId }
                if (peerId != collab.selfPeerId && leftPeer != null) {
                    collab.departedPeers = collab.departedPeers + (peerId to leftPeer.name)
                    showActionToast(getString(R.string.collab_peer_left_toast, leftPeer.name), R.drawable.ic_cloud)
                }
                collab.peers = collab.peers.filterNot { it.peerId == peerId }
                collab.pendingRequests = collab.pendingRequests.filterNot { it.peerId == peerId }
                collab.remoteCursors = collab.remoteCursors.filterNot { it.peerId == peerId }
                runCore(render = false) {
                    ReverieCoreBridge.collabOnPeerLeft(peerId)
                }
            }
        }

        override fun onPeerOffline(peerId: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val target = collab.peers.firstOrNull { it.peerId == peerId }
                if (peerId != collab.selfPeerId && target?.signalingOnline == true) {
                    showActionToast(getString(R.string.collab_peer_offline_toast, target.name), R.drawable.ic_cloud)
                }
                collab.peers = collab.peers.map {
                    if (it.peerId == peerId) it.copy(signalingOnline = false) else it
                }
            }
        }

        override fun onPeerOnline(peerId: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.peers = collab.peers.map {
                    if (it.peerId == peerId) it.copy(signalingOnline = true) else it
                }
                val peerInfo = collab.peers.firstOrNull { it.peerId == peerId }
                if (peerInfo != null && !peerInfo.connectedP2P && collab.role != CollabRole.HOST && peerId == collab.hostPeerId) {
                    runCore(render = false) {
                        if (collab.sessionGeneration == gen && collab.nativeStarted) {
                            ReverieCoreBridge.collabRetryPeer(peerId)
                        }
                    }
                }
            }
        }

        override fun onSignal(fromPeerId: String, payloadJson: String) {
            if (payloadJson.contains("\"collab_chat\"") || payloadJson.contains("\"collab_ping\"")) {
                val obj = runCatching { JSONObject(payloadJson) }.getOrNull()
                val kind = obj?.optString("kind").orEmpty()
                if (kind == "collab_chat" && obj != null) {
                    val text = obj.optString("text").trim().take(80)
                    val senderName = obj.optString("name").trim()
                    if (text.isNotEmpty()) {
                        mainHandler.post {
                            if (collab.sessionGeneration == gen) {
                                handleInboundCollabChat(fromPeerId, senderName, text)
                            }
                        }
                    }
                    return
                }
                if (kind == "collab_ping" && obj != null) {
                    val docX = obj.optDouble("docX", Double.NaN).toFloat()
                    val docY = obj.optDouble("docY", Double.NaN).toFloat()
                    val senderName = obj.optString("name").trim()
                    if (docX.isFinite() && docY.isFinite()) {
                        mainHandler.post {
                            if (collab.sessionGeneration == gen) {
                                handleInboundCollabPing(fromPeerId, senderName, docX, docY)
                            }
                        }
                    }
                    return
                }
            }
            val buffered = synchronized(collab.signalLock) {
                if (!collab.nativeStarted) {
                    collab.pendingSignals.add(fromPeerId to payloadJson)
                    true
                } else {
                    false
                }
            }
            if (!buffered) {
                runCore(render = false) {
                    if (collab.sessionGeneration == gen && collab.nativeStarted) {
                        ReverieCoreBridge.collabOnRemoteSignal(fromPeerId, payloadJson)
                    }
                }
            }
        }

        override fun onSignalUndelivered(targetPeerId: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                android.util.Log.w("ReverieCollab", "signal undelivered to=$targetPeerId, marking signaling offline")
                collab.peers = collab.peers.map {
                    if (it.peerId == targetPeerId) it.copy(signalingOnline = false) else it
                }
            }
        }

        override fun onHostChanged(newHostPeerId: String, hostToken: String?, hostVirtualIp: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.hostOfflineTimerJob?.cancel()
                collab.hostOfflineTimerJob = null
                collab.hostPeerId = newHostPeerId
                collab.role = if (newHostPeerId == collab.selfPeerId) CollabRole.HOST else CollabRole.PEER
                if (!hostToken.isNullOrBlank()) {
                    collab.hostToken = hostToken
                }
                val updatedPeers = collab.peers.map { p ->
                    if (p.peerId == newHostPeerId && hostVirtualIp.isNotBlank()) p.copy(virtualIp = hostVirtualIp) else p
                }
                val p2pStates = updatedPeers.associate { it.peerId to it.connectedP2P }
                collab.peers = normalizePeersWithFlags(updatedPeers, newHostPeerId, p2pStates)
                val updatedEt = buildEffectiveTransportConfigJson(
                    roomCode = collab.roomCode,
                    selfPeerId = collab.selfPeerId,
                    hostPeerId = newHostPeerId,
                    baseEasyTierJson = collab.lastEasyTierJson,
                    peers = collab.peers,
                )
                collab.lastEasyTierJson = updatedEt
                syncNativeOnSignalingReconnect(gen, newHostPeerId, updatedEt, collab.peers)
                val newHostName = collab.peers.firstOrNull { it.peerId == newHostPeerId }?.name ?: newHostPeerId
                showActionToast(getString(R.string.collab_host_migrated_msg, newHostName), R.drawable.ic_cloud)
            }
        }

        override fun onHostOffline(hostPeerId: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.peers = collab.peers.map {
                    if (it.peerId == hostPeerId) it.copy(signalingOnline = false) else it
                }
                collab.hostOfflineTimerJob?.cancel()
                collab.hostOfflineTimerJob = viewModelScope.launch {
                    delay(COLLAB_HOST_OFFLINE_CLAIM_MS)
                    if (collab.sessionGeneration != gen || !collab.isActive) return@launch
                    val elected = CollabTopologyPolicy.electNextHost(collab.peers, hostPeerId)
                    if (elected?.peerId == collab.selfPeerId) {
                        collab.signalingClient?.claimHost()
                    }
                }
            }
        }

        override fun onHostOnline(hostPeerId: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.hostOfflineTimerJob?.cancel()
                collab.hostOfflineTimerJob = null
                collab.peers = collab.peers.map {
                    if (it.peerId == hostPeerId) it.copy(signalingOnline = true) else it
                }
                val hostPeer = collab.peers.firstOrNull { it.peerId == hostPeerId }
                if (collab.role != CollabRole.HOST && collab.nativeStarted && hostPeer?.connectedP2P != true) {
                    runCore(render = false) {
                        ReverieCoreBridge.collabRetryPeer(hostPeerId)
                    }
                }
            }
        }

        override fun onRejected() {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                teardownCollabLocalState(gracefulLeave = false)
                collab.phase = CollabConnectionPhase.ERROR
                collab.statusMessage = getString(R.string.collab_rejected_msg)
                showActionToast(R.string.collab_rejected_msg, R.drawable.ic_cloud)
            }
        }

        override fun onKicked() {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                onCollabSessionEndedPromptSave(toastRes = R.string.collab_kicked_msg)
            }
        }

        override fun onRoomClosed(reason: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val toastRes = if (reason == "expired") {
                    R.string.collab_room_expired_msg
                } else {
                    R.string.collab_room_closed_msg
                }
                onCollabSessionEndedPromptSave(toastRes = toastRes)
            }
        }

        override fun onError(code: String, message: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                when (code) {
                    "host_grace_period" -> {
                        collab.hostOfflineTimerJob?.cancel()
                        collab.hostOfflineTimerJob = viewModelScope.launch {
                            delay(2500L)
                            if (collab.sessionGeneration == gen && collab.isActive) {
                                collab.signalingClient?.claimHost()
                            }
                        }
                    }
                    "ws_failure" -> {
                        if (!collab.intentionalLeave && collab.isActive) {
                            scheduleAutoReconnect(gen)
                        }
                    }
                    "invalid_host_token" -> {
                        // 换届后旧房主 / 本地 hostToken 失效: 降级为普通成员重连, 而不是结束整场会话
                        if (collab.isActive && !collab.intentionalLeave) {
                            android.util.Log.w("ReverieCollab", "host token rejected by server, demote to peer")
                            collab.hostToken = ""
                            collab.role = CollabRole.PEER
                            reconnectCollabNow()
                        } else {
                            val detail = localizeCollabError(code, message)
                            collab.phase = CollabConnectionPhase.ERROR
                            collab.statusMessage = detail
                            showActionToast(detail, R.drawable.ic_cloud)
                        }
                    }
                    "duplicate_name", "room_full", "invalid_password", "room_not_found",
                    "kicked", "peer_id_conflict", "service_disabled" -> {
                        val detail = localizeCollabError(code, message)
                        val existingNames = collab.peers.map { it.name } + collab.selfName
                        val suggested = if (code == "duplicate_name") {
                            CollabTopologyPolicy.suggestUniqueNickname(collab.selfName, existingNames)
                        } else {
                            null
                        }
                        val savedCode = collab.roomCode
                        val savedPw = collab.lastJoinPassword
                        teardownCollabLocalState(gracefulLeave = false)
                        if (suggested != null) {
                            collab.duplicateSuggestedName = suggested
                            collab.lastDuplicateRoomCode = savedCode
                            collab.lastDuplicatePassword = savedPw
                        }
                        collab.phase = CollabConnectionPhase.ERROR
                        collab.statusMessage = detail
                        if (currentPage != Page.PAINTING) {
                            collab.showLobbyDialog = true
                        }
                        showActionToast(detail, R.drawable.ic_cloud)
                    }
                    else -> {
                        val detail = localizeCollabError(code, message)
                        collab.statusMessage = detail
                        showActionToast(detail, R.drawable.ic_cloud)
                    }
                }
            }
        }

        override fun onSocketClosed(code: Int, reason: String) {
            android.util.Log.i("ReverieCollab", "socket closed code=$code reason=$reason")
            mainHandler.post {
                if (collab.sessionGeneration != gen || !collab.isActive || collab.intentionalLeave) return@post
                if (code == 4004) {
                    // 房间已达最长有效期或被判定空闲过期, 明示原因并结束会话, 不再盲目重连
                    val detail = getString(R.string.collab_room_expired_msg)
                    onCollabSessionEndedPromptSave(toastRes = R.string.collab_room_expired_msg)
                    collab.phase = CollabConnectionPhase.ERROR
                    collab.statusMessage = detail
                    return@post
                }
                if (code != 1000 && code != 4000 && code != 4003 && code != 4005) {
                    scheduleAutoReconnect(gen)
                }
            }
        }
    }
}

private fun PaintViewModel.beginNativeCollabSession(
    gen: Long,
    roomCode: String,
    selfPeerId: String,
    selfName: String,
    hostPeerId: String,
    iceServersJson: String,
    initialPeers: List<CollabPeer>,
) {
    val cacheDir = File(appContext.cacheDir, "collab").apply { mkdirs() }.absolutePath
    var startedOk = false
    runCore(render = true, after = {
        if (collab.sessionGeneration == gen) {
            if (startedOk) {
                syncLayersFromNative()
                layerRevision++
            }
            startCollabPollingLoop(gen)
            refreshUndoCounts()
        }
    }) {
        if (collab.sessionGeneration != gen) return@runCore
        val ok = ReverieCoreBridge.collabStartSession(
            roomCode,
            selfPeerId,
            selfName,
            hostPeerId,
            iceServersJson.ifBlank { "[]" },
            cacheDir,
        )
        if (ok) {
            startedOk = true
            for (p in initialPeers) {
                if (p.peerId != selfPeerId) {
                    ReverieCoreBridge.collabOnPeerJoined(p.peerId, p.name, p.role == CollabRole.HOST)
                }
            }
            val earlySignals = synchronized(collab.signalLock) {
                collab.nativeStarted = true
                val copy = collab.pendingSignals.toList()
                collab.pendingSignals.clear()
                copy
            }
            for ((from, payload) in earlySignals) {
                ReverieCoreBridge.collabOnRemoteSignal(from, payload)
            }
        }
    }
}

private fun PaintViewModel.syncNativeOnSignalingReconnect(
    gen: Long,
    hostPeerId: String,
    transportConfigJson: String,
    peers: List<CollabPeer>,
) {
    val peersArr = JSONArray()
    for (p in peers) {
        peersArr.put(
            JSONObject().apply {
                put("peerId", p.peerId)
                put("name", p.name)
                put("isHost", p.role == CollabRole.HOST)
                if (p.virtualIp.isNotEmpty()) put("virtualIp", p.virtualIp)
            },
        )
    }
    runCore(render = false) {
        if (collab.sessionGeneration == gen && collab.nativeStarted) {
            ReverieCoreBridge.collabSyncOnReconnect(hostPeerId, transportConfigJson, peersArr.toString())
        }
    }
}

private fun PaintViewModel.startCollabPollingLoop(gen: Long) {
    collab.drainJob?.cancel()
    collab.drainJob = viewModelScope.launch {
        while (isActive && collab.sessionGeneration == gen && collab.isActive) {
            val h = renderHandler
            if (h != null && collab.nativeStarted && collab.drainInFlight.compareAndSet(false, true)) {
                h.post {
                    try {
                        if (collab.sessionGeneration == gen && collab.nativeStarted) {
                            val dirty = ReverieCoreBridge.collabDrainInbound()
                            if (dirty) {
                                scheduleRender()
                            }
                            val eventsJson = ReverieCoreBridge.collabPollEventsJson()
                            val cursorsJson = ReverieCoreBridge.collabPollCursorsJson()
                            val nowMs = android.os.SystemClock.uptimeMillis()
                            val netStatusJson = if (nowMs - collab.lastNetDiagPollMs >= 1500L) {
                                collab.lastNetDiagPollMs = nowMs
                                ReverieCoreBridge.collabNetStatusJson()
                            } else {
                                null
                            }
                            if (eventsJson != "[]" || cursorsJson != "[]" || collab.remoteCursors.isNotEmpty() || netStatusJson != null) {
                                mainHandler.post {
                                    if (collab.sessionGeneration == gen) {
                                        handleCollabPolledData(gen, eventsJson, cursorsJson, netStatusJson)
                                    }
                                }
                            }
                        }
                    } finally {
                        collab.drainInFlight.set(false)
                    }
                }
            }
            delay(COLLAB_DRAIN_INTERVAL_MS)
        }
    }
}

private fun parseCollabNetworkDiag(rawJson: String): CollabNetworkDiag {
    val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return CollabNetworkDiag()
    val selfNat = CollabTopologyPolicy.parseNatType(obj.optString("udpNatType"))
    val tcpNat = CollabTopologyPolicy.parseNatType(obj.optString("tcpNatType"))
    val pubArr = obj.optJSONArray("publicIps")
    val pubIps = ArrayList<String>(pubArr?.length() ?: 0)
    if (pubArr != null) {
        for (i in 0 until pubArr.length()) {
            val s = pubArr.optString(i).trim()
            if (s.isNotEmpty()) pubIps.add(s)
        }
    }
    val routesMap = HashMap<String, CollabPeerRouteDiag>()
    val peersArr = obj.optJSONArray("peers")
    if (peersArr != null) {
        for (i in 0 until peersArr.length()) {
            val po = peersArr.optJSONObject(i) ?: continue
            val pid = po.optString("peerId")
            if (pid.isEmpty()) continue
            val mode = when (po.optString("routeMode")) {
                "p2p" -> CollabRouteMode.P2P_DIRECT
                "relay" -> CollabRouteMode.RELAYED
                else -> CollabRouteMode.CONNECTING
            }
            routesMap[pid] = CollabPeerRouteDiag(
                peerId = pid,
                routeMode = mode,
                tunnelProto = po.optString("tunnelProto"),
                latencyMs = po.optInt("latencyMs", -1),
                lossRate = po.optDouble("lossRate", 0.0).toFloat(),
                natType = CollabTopologyPolicy.parseNatType(po.optString("natType")),
                rxBytes = po.optLong("rxBytes", 0L),
                txBytes = po.optLong("txBytes", 0L),
            )
        }
    }
    return CollabNetworkDiag(
        ready = obj.optBoolean("ready", false),
        easytierRunning = obj.optBoolean("easytierRunning", false),
        selfVirtualIp = obj.optString("selfVirtualIp"),
        hostVirtualIp = obj.optString("hostVirtualIp"),
        selfNatType = selfNat,
        tcpNatType = tcpNat,
        publicIps = pubIps,
        natDifficultyLevel = CollabTopologyPolicy.natDifficultyLevel(selfNat),
        errorMsg = obj.optString("error").takeIf { it.isNotBlank() },
        peerRoutes = routesMap,
    )
}

private fun PaintViewModel.handleCollabPolledData(
    gen: Long,
    eventsJson: String,
    cursorsJson: String,
    netStatusJson: String? = null,
) {
    if (!netStatusJson.isNullOrBlank()) {
        val diag = parseCollabNetworkDiag(netStatusJson)
        collab.networkDiag = diag
        if (diag.peerRoutes.isNotEmpty()) {
            collab.peers = collab.peers.map { peer ->
                val rd = diag.peerRoutes[peer.peerId]
                if (rd != null) {
                    peer.copy(
                        routeMode = rd.routeMode,
                        tunnelProto = rd.tunnelProto,
                        latencyMs = rd.latencyMs,
                        natType = rd.natType,
                    )
                } else {
                    peer
                }
            }
        }
    }
    if (eventsJson != "[]") {
        val arr = runCatching { JSONArray(eventsJson) }.getOrNull()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                when (obj.optString("type")) {
                    "signal_out" -> {
                        val toPeer = obj.optString("to")
                        val payloadObj = obj.optJSONObject("payload")
                        if (toPeer.isNotEmpty() && payloadObj != null) {
                            collab.signalingClient?.sendSignal(toPeer, payloadObj)
                        }
                    }
                    "local_endpoints" -> {
                        val urisArr = obj.optJSONArray("uris")
                        val uris = ArrayList<String>(urisArr?.length() ?: 0)
                        if (urisArr != null) {
                            for (k in 0 until urisArr.length()) {
                                val u = urisArr.optString(k).trim()
                                if (u.isNotEmpty()) uris.add(u)
                            }
                        }
                        if (uris.isNotEmpty()) {
                            collab.localEndpointUris = uris
                            val payload = JSONObject().apply {
                                put("kind", "et_endpoints")
                                put("uris", JSONArray(uris))
                            }
                            if (collab.role == CollabRole.HOST) {
                                for (p in collab.peers) {
                                    if (p.peerId != collab.selfPeerId && p.signalingOnline) {
                                        collab.signalingClient?.sendSignal(p.peerId, payload)
                                    }
                                }
                            } else if (collab.hostPeerId.isNotEmpty()) {
                                collab.signalingClient?.sendSignal(collab.hostPeerId, payload)
                            }
                        }
                    }
                    "transport_error" -> {
                        val err = obj.optString("error")
                        if (err.isNotBlank()) {
                            collab.statusMessage = err
                        }
                    }
                    "peer_p2p_state" -> {
                        val peerId = obj.optString("peerId")
                        val connected = obj.optBoolean("connected", false)
                        collab.peers = collab.peers.map {
                            if (it.peerId == peerId) it.copy(connectedP2P = connected) else it
                        }
                    }
                    "snapshot_progress" -> {
                        val received = obj.optInt("received", 0)
                        val total = obj.optInt("total", 0)
                        if (received >= total) {
                            collab.snapshotReceivedChunks = 0
                            collab.snapshotTotalChunks = 0
                        } else {
                            collab.snapshotReceivedChunks = received
                            collab.snapshotTotalChunks = total
                        }
                    }
                    "snapshot_error" -> {
                        val reason = obj.optString("reason", "snapshot")
                        showActionToast(getString(R.string.collab_status_error, reason), R.drawable.ic_cloud)
                    }
                    "doc_reloaded" -> {
                        val w = obj.optInt("width", 0)
                        val h = obj.optInt("height", 0)
                        collab.snapshotReceivedChunks = 0
                        collab.snapshotTotalChunks = 0
                        if (w > 0 && h > 0 && (docWidth != w || docHeight != h)) {
                            discardTileGridForDocumentSwitch()
                            docWidth = w
                            docHeight = h
                            coreW = w
                            coreH = h
                            renderW = w
                            renderH = h
                        }
                        runCore(render = true, after = {
                            if (collab.sessionGeneration == gen) {
                                syncLayersFromNative()
                                layerRevision++
                                isBlockingLoading = false
                                refreshLayerThumbs(force = true)
                                refreshUndoCounts()
                                val needRefocus = collab.pendingAutoFocusOnSnapshot ||
                                    !canEditLayerInCollab(layers.getOrNull(currentLayerIndex))
                                collab.pendingAutoFocusOnSnapshot = false
                                if (needRefocus) {
                                    applyAutoFocusOrCreateCollabLayer()
                                }
                            }
                        }) {
                            displayBufferInvalid = true
                        }
                    }
                    "layers_changed" -> {
                        if (collab.sessionGeneration == gen) {
                            syncLayersFromNative()
                            layerRevision++
                            refreshLayerThumbs(force = false)
                            refreshUndoCounts()
                            scheduleRender()
                        }
                    }
                    "thumb_dirty" -> {
                        if (collab.sessionGeneration == gen) {
                            refreshLayerThumbs(force = false)
                        }
                    }
                }
            }
        }
    }

    if (cursorsJson == "[]") {
        if (collab.remoteCursors.isNotEmpty()) {
            collab.remoteCursors = emptyList()
        }
        return
    }

    val curArr = runCatching { JSONArray(cursorsJson) }.getOrNull() ?: return
    val parsed = ArrayList<RemoteCursorState>(curArr.length())
    for (i in 0 until curArr.length()) {
        val o = curArr.optJSONObject(i) ?: continue
        val peerId = o.optString("peerId")
        if (peerId.isEmpty() || peerId == collab.selfPeerId) continue
        parsed.add(
            RemoteCursorState(
                peerId = peerId,
                name = o.optString("name", peerId),
                docX = o.optDouble("docX", 0.0).toFloat(),
                docY = o.optDouble("docY", 0.0).toFloat(),
                brushSize = o.optDouble("brushSize", 12.0).toFloat(),
                brushColorArgb = o.optLong("colorArgb", 0xFF000000L).toInt(),
                badgeColorArgb = CollabTopologyPolicy.assignBadgeColorArgb(peerId),
                isDrawing = o.optBoolean("isDrawing", false),
                updatedAtMs = o.optLong("updatedAtMs", 0L),
            )
        )
    }
    if (parsed != collab.remoteCursors) {
        collab.remoteCursors = parsed
    }
}

internal fun PaintViewModel.approveCollabPeer(peerId: String) {
    collab.signalingClient?.approvePeer(peerId)
    collab.pendingRequests = collab.pendingRequests.filterNot { it.peerId == peerId }
}

internal fun PaintViewModel.rejectCollabPeer(peerId: String) {
    collab.signalingClient?.rejectPeer(peerId)
    collab.pendingRequests = collab.pendingRequests.filterNot { it.peerId == peerId }
}

internal fun PaintViewModel.kickCollabPeer(peerId: String) {
    collab.signalingClient?.kickPeer(peerId)
    collab.peers = collab.peers.filterNot { it.peerId == peerId }
    runCore(render = false) {
        ReverieCoreBridge.collabOnPeerLeft(peerId)
    }
}

internal fun PaintViewModel.handoffCollabHost(newHostPeerId: String) {
    collab.signalingClient?.handoffHost(newHostPeerId)
}

/**
 * 主动离房或解散房间 (若仍在画布页, 弹出「保存茶绘工程」供所有参与成员保存到本地并附带茶绘标签)。
 */
internal fun PaintViewModel.requestEndCollabAndPromptSave(closeRoomForAll: Boolean = false) {
    val roomForTag = collab.roomCode
    if (closeRoomForAll && collab.role == CollabRole.HOST) {
        collab.signalingClient?.closeRoom()
    }
    onCollabSessionEndedPromptSave(roomCodeOverride = roomForTag, toastRes = null)
}

private fun PaintViewModel.onCollabSessionEndedPromptSave(
    roomCodeOverride: String? = null,
    toastRes: Int? = null,
) {
    val savedCode = (roomCodeOverride ?: collab.roomCode).trim()
    teardownCollabLocalState(gracefulLeave = !false)
    if (toastRes != null) {
        showActionToast(toastRes, R.drawable.ic_cloud)
    }
    if (currentPage == Page.PAINTING) {
        collab.savedRoomCodeForDialog = savedCode
        collab.endSaveDefaultName = generateNextCollabProjectName(savedCode)
        collab.showEndSaveDialog = true
    }
}

internal fun PaintViewModel.saveCollabArtwork(projectName: String, exitToHome: Boolean) {
    val fallback = collab.endSaveDefaultName.ifBlank { generateNextCollabProjectName(collab.savedRoomCodeForDialog) }
    val cleanName = projectName.trim().replace("/", "_").replace("\\", "_").ifBlank { fallback }
    val roomTag = collab.savedRoomCodeForDialog
    collab.showEndSaveDialog = false
    runCore(render = false) {
        ReverieCoreBridge.setProjectCollabMeta(true, roomTag)
    }
    // 保证非新建路径覆盖其他无关文件: 若原本没绑定 .revp 路径或命名更改则写向新名字
    saveProject(cleanName) {
        if (exitToHome) {
            goHome()
        } else {
            showActionToast(R.string.toast_project_saved, R.drawable.ic_save)
        }
    }
}

internal fun PaintViewModel.discardCollabAndExit() {
    collab.showEndSaveDialog = false
    discardAndExit()
}

internal fun PaintViewModel.generateNextCollabProjectName(roomCode: String): String {
    val prefix = if (LanguageManager.isChinese()) {
        if (roomCode.isNotBlank()) "茶绘-$roomCode" else "茶绘作品"
    } else {
        if (roomCode.isNotBlank()) "Collab-$roomCode" else "Collab Artwork"
    }
    val existingNames = mutableSetOf<String>()
    projectDir().walkTopDown().filter { it.isFile }.forEach { existingNames.add(it.nameWithoutExtension.lowercase()) }
    if (!existingNames.contains(prefix.lowercase())) return prefix
    var idx = 2
    while (existingNames.contains("$prefix $idx".lowercase())) {
        idx++
    }
    return "$prefix $idx"
}

internal fun PaintViewModel.leaveCollabRoom(synchronousNativeStop: Boolean = false) {
    teardownCollabLocalState(synchronousNativeStop = synchronousNativeStop, gracefulLeave = true)
}

internal fun PaintViewModel.closeCollabRoom() {
    if (collab.role == CollabRole.HOST) {
        collab.signalingClient?.closeRoom()
    }
    teardownCollabLocalState(synchronousNativeStop = false, gracefulLeave = false)
}

private fun PaintViewModel.teardownCollabLocalState(
    synchronousNativeStop: Boolean = false,
    gracefulLeave: Boolean = true,
) {
    collab.intentionalLeave = true
    collab.sessionGeneration++
    collab.reconnectJob?.cancel()
    collab.reconnectJob = null
    collab.drainJob?.cancel()
    collab.drainJob = null
    collab.hostOfflineTimerJob?.cancel()
    collab.hostOfflineTimerJob = null
    collab.chatBannerClearJob?.cancel()
    collab.chatBannerClearJob = null
    collab.pingPruneJob?.cancel()
    collab.pingPruneJob = null
    collab.signalingClient?.disconnect(gracefulLeave = gracefulLeave)
    collab.signalingClient = null

    val wasNativeStarted = synchronized(collab.signalLock) {
        val prev = collab.nativeStarted
        collab.nativeStarted = false
        collab.pendingSignals.clear()
        prev
    }
    collab.isActive = false
    collab.phase = CollabConnectionPhase.IDLE
    collab.roomCode = ""
    collab.hostPeerId = ""
    collab.hostToken = ""
    collab.isPublicRoom = false
    collab.roomTitle = ""
    collab.reconnectAttempt = 0
    collab.peers = emptyList()
    collab.departedPeers = emptyMap()
    collab.pendingAutoFocusOnSnapshot = false
    collab.pendingRequests = emptyList()
    collab.remoteCursors = emptyList()
    collab.snapshotReceivedChunks = 0
    collab.snapshotTotalChunks = 0
    collab.pendingJoinCanvasW = 0
    collab.pendingJoinCanvasH = 0
    collab.statusMessage = null
    collab.showRoomSheet = false
    collab.networkDiag = CollabNetworkDiag()
    collab.lastEasyTierJson = "{}"
    collab.localEndpointUris = emptyList()
    collab.lastNetDiagPollMs = 0L
    collab.chatMessages = emptyList()
    collab.latestChatBanner = null
    collab.canvasPings = emptyList()
    collab.pendingFocusDocPoint = null
    collab.pendingPingAtViewportCenter = false
    collab.duplicateSuggestedName = null
    collab.lastDuplicateRoomCode = ""
    collab.lastDuplicatePassword = ""
    collab.roomHasPassword = false
    collab.kcpNeedsReconnectHint = false

    if (wasNativeStarted) {
        if (synchronousNativeStop || renderHandler == null) {
            runCatching { ReverieCoreBridge.collabStopSession() }
        } else {
            runCore(render = true, after = {
                syncLayersFromNative()
                layerRevision++
                refreshUndoCounts()
            }) {
                ReverieCoreBridge.collabStopSession()
            }
        }
    }
}

internal fun PaintViewModel.canEditLayerInCollab(layer: PaintViewModel.LayerUiState?): Boolean {
    if (layer == null) return false
    return CollabPermissionPolicy.canEditLayer(
        inCollab = collab.isActive,
        isLayerLocked = layer.locked,
        isBackground = layer.isBackground,
        isGroup = layer.isGroup,
        ownerPeerId = layer.ownerPeerId,
        permissionCode = layer.collabPermission,
        selfPeerId = collab.selfPeerId,
    )
}

internal fun PaintViewModel.canManageLayerOwnershipInCollab(layer: PaintViewModel.LayerUiState?): Boolean {
    if (layer == null || layer.isBackground) return false
    if (!collab.isActive) return true
    return CollabPermissionPolicy.canManageOwnership(
        isHost = collab.role == CollabRole.HOST,
        ownerPeerId = layer.ownerPeerId,
        selfPeerId = collab.selfPeerId,
    )
}

internal fun PaintViewModel.canModifyLayerPropsInCollab(layer: PaintViewModel.LayerUiState?): Boolean {
    if (layer == null) return false
    return CollabPermissionPolicy.canModifyLayerProperties(
        inCollab = collab.isActive,
        isHost = collab.role == CollabRole.HOST,
        isBackground = layer.isBackground,
        isLayerLocked = layer.locked,
        ownerPeerId = layer.ownerPeerId,
        permissionCode = layer.collabPermission,
        selfPeerId = collab.selfPeerId,
    )
}

internal fun PaintViewModel.canToggleLayerLockInCollab(layer: PaintViewModel.LayerUiState?): Boolean {
    if (layer == null) return false
    return CollabPermissionPolicy.canToggleLayerLock(
        inCollab = collab.isActive,
        isHost = collab.role == CollabRole.HOST,
        isBackground = layer.isBackground,
        ownerPeerId = layer.ownerPeerId,
        selfPeerId = collab.selfPeerId,
    )
}

internal fun PaintViewModel.canDeleteLayerInCollab(layer: PaintViewModel.LayerUiState?): Boolean {
    if (layer == null) return false
    return CollabPermissionPolicy.canDeleteLayer(
        inCollab = collab.isActive,
        isHost = collab.role == CollabRole.HOST,
        isBackground = layer.isBackground,
        isLayerLocked = layer.locked,
        ownerPeerId = layer.ownerPeerId,
        permissionCode = layer.collabPermission,
        selfPeerId = collab.selfPeerId,
    )
}

internal fun PaintViewModel.canInsertIntoGroupInCollab(groupLayer: PaintViewModel.LayerUiState?): Boolean {
    if (groupLayer == null || !groupLayer.isGroup) return false
    return CollabPermissionPolicy.canInsertIntoGroup(
        inCollab = collab.isActive,
        isHost = collab.role == CollabRole.HOST,
        isGroupLocked = groupLayer.locked,
        groupOwnerPeerId = groupLayer.ownerPeerId,
        groupPermissionCode = groupLayer.collabPermission,
        selfPeerId = collab.selfPeerId,
    )
}

internal fun PaintViewModel.canEditBackgroundInCollab(): Boolean =
    CollabPermissionPolicy.canEditBackground(
        inCollab = collab.isActive,
        isHost = collab.role == CollabRole.HOST,
    )

internal fun PaintViewModel.isLayerTypeSupportedInCollab(layer: PaintViewModel.LayerUiState?): Boolean {
    if (layer == null) return false
    return CollabPermissionPolicy.isLayerTypeSupportedInCollab(
        nodeType = layer.nodeType,
        has3DContent = active3DLayers.containsKey(layer.id),
        hasTextContent = activeTextLayers.containsKey(layer.id),
    ) && !layer.isStrokeLayer
}

internal fun PaintViewModel.ensureCollabLayerEditable(
    index: Int = currentLayerIndex,
    showToast: Boolean = true,
): Boolean {
    if (!collab.isActive) return true
    val layer = layers.getOrNull(index)
    if (canEditLayerInCollab(layer)) return true
    if (showToast) {
        when {
            layer?.locked == true -> {
                showActionToast(R.string.canvas_toast_layer_locked, R.drawable.ic_lock)
            }
            layer != null &&
                layer.collabPermission == LayerCollabPermission.OWNER_ONLY.code &&
                layer.ownerPeerId.isNotEmpty() &&
                layer.ownerPeerId != collab.selfPeerId -> {
                showActionToast(
                    getString(R.string.collab_layer_no_permission_owner, collabOwnerDisplayName(layer.ownerPeerId)),
                    R.drawable.ic_lock,
                )
            }
            else -> {
                showActionToast(R.string.collab_layer_no_permission, R.drawable.ic_lock)
            }
        }
    }
    return false
}

internal fun PaintViewModel.ensureCollabLayerPropsEditable(
    index: Int,
    showToast: Boolean = true,
): Boolean {
    if (!collab.isActive) return true
    val layer = layers.getOrNull(index) ?: return false
    if (canModifyLayerPropsInCollab(layer)) return true
    if (showToast) {
        val reasonRes = when {
            layer.isBackground -> R.string.collab_layer_bg_host_only
            layer.locked || layer.collabPermission == LayerCollabPermission.LOCKED.code -> R.string.canvas_toast_layer_locked
            else -> R.string.collab_layer_props_no_permission
        }
        showActionToast(reasonRes, R.drawable.ic_lock)
    }
    return false
}

internal fun PaintViewModel.ensureCollabLayerLockToggleAllowed(
    index: Int,
    showToast: Boolean = true,
): Boolean {
    if (!collab.isActive) return true
    val layer = layers.getOrNull(index) ?: return false
    if (canToggleLayerLockInCollab(layer)) return true
    if (showToast) {
        showActionToast(R.string.collab_layer_props_no_permission, R.drawable.ic_lock)
    }
    return false
}

internal fun PaintViewModel.ensureCollabLayerDeletable(
    index: Int,
    showToast: Boolean = true,
): Boolean {
    if (!collab.isActive) return true
    val layer = layers.getOrNull(index) ?: return false
    if (!canDeleteLayerInCollab(layer)) {
        if (showToast) {
            val res = if (layer.locked || layer.collabPermission == LayerCollabPermission.LOCKED.code) {
                R.string.toast_layer_delete_locked
            } else {
                R.string.collab_layer_delete_no_permission
            }
            showActionToast(res, R.drawable.ic_lock)
        }
        return false
    }
    if (layer.isGroup) {
        val rootDepth = layer.depth
        for (k in (index + 1) until layers.size) {
            val child = layers[k]
            if (child.depth <= rootDepth) break
            if (!canDeleteLayerInCollab(child)) {
                if (showToast) {
                    showActionToast(R.string.collab_layer_delete_no_permission, R.drawable.ic_lock)
                }
                return false
            }
        }
    }
    return true
}

internal fun PaintViewModel.ensureCollabHostForBackground(showToast: Boolean = true): Boolean {
    if (canEditBackgroundInCollab()) return true
    if (showToast) {
        showActionToast(R.string.collab_layer_bg_host_only, R.drawable.ic_lock)
    }
    return false
}

internal fun PaintViewModel.ensureCollabInsertIntoGroup(
    groupIndex: Int,
    showToast: Boolean = true,
): Boolean {
    if (!collab.isActive) return true
    val grp = layers.getOrNull(groupIndex) ?: return false
    if (canInsertIntoGroupInCollab(grp)) return true
    if (showToast) {
        showActionToast(R.string.collab_layer_group_no_permission, R.drawable.ic_lock)
    }
    return false
}

internal fun PaintViewModel.ensureCollabLayerTypeSupported(
    index: Int,
    showToast: Boolean = true,
): Boolean {
    if (!collab.isActive) return true
    val layer = layers.getOrNull(index) ?: return false
    if (!isLayerTypeSupportedInCollab(layer)) {
        if (showToast) {
            showActionToast(R.string.collab_layer_type_unsupported, R.drawable.ic_lock)
        }
        return false
    }
    if (layer.isGroup) {
        val rootDepth = layer.depth
        for (k in (index + 1) until layers.size) {
            val child = layers[k]
            if (child.depth <= rootDepth) break
            if (!isLayerTypeSupportedInCollab(child)) {
                if (showToast) {
                    showActionToast(R.string.collab_layer_type_unsupported, R.drawable.ic_lock)
                }
                return false
            }
        }
    }
    return true
}

internal fun PaintViewModel.collabEnclosingGroupIndex(index: Int): Int {
    val target = layers.getOrNull(index) ?: return -1
    if (target.depth <= 0) return -1
    for (k in (index - 1) downTo 0) {
        val cand = layers[k]
        if (cand.isGroup && cand.depth == target.depth - 1) {
            return k
        }
    }
    return -1
}

internal fun PaintViewModel.collabOwnerDisplayName(ownerPeerId: String): String {
    if (ownerPeerId.isEmpty()) return getString(R.string.collab_layer_owner_unassigned)
    if (ownerPeerId == collab.selfPeerId) {
        return collab.selfName.ifBlank { getString(R.string.collab_layer_owner_self_short) }
    }
    collab.peers.firstOrNull { it.peerId == ownerPeerId }?.let { return it.name }
    val leftTag = getString(R.string.collab_peer_departed_short)
    val oldName = collab.departedPeers[ownerPeerId]
    return if (!oldName.isNullOrBlank()) "$oldName ($leftTag)" else leftTag
}

internal fun PaintViewModel.collabOwnerShortBadgeLabel(ownerPeerId: String): String {
    if (ownerPeerId.isEmpty()) return getString(R.string.collab_layer_badge_owner)
    if (ownerPeerId == collab.selfPeerId) return getString(R.string.collab_layer_owner_self_short)
    collab.peers.firstOrNull { it.peerId == ownerPeerId }?.let { peer ->
        return CollabPermissionPolicy.formatShortOwnerLabel(peer.name)
            .ifEmpty { getString(R.string.collab_layer_badge_owner) }
    }
    return getString(R.string.collab_peer_departed_short)
}

internal fun PaintViewModel.collabOwnerAvatarInitial(ownerPeerId: String): String {
    if (ownerPeerId.isEmpty()) return getString(R.string.collab_layer_badge_shared_short)
    if (ownerPeerId == collab.selfPeerId) return getString(R.string.collab_layer_owner_self_short)
    collab.peers.firstOrNull { it.peerId == ownerPeerId }?.let { peer ->
        val initial = CollabPermissionPolicy.formatAvatarInitial(peer.name)
        if (initial.isNotEmpty()) return initial
    }
    return getString(R.string.collab_peer_departed_initial)
}

internal fun PaintViewModel.isOrphanedCollabLayer(layer: PaintViewModel.LayerUiState?): Boolean {
    if (layer == null || !collab.isActive) return false
    return CollabPermissionPolicy.isOrphanedDepartedLayer(
        inCollab = true,
        isBackground = layer.isBackground,
        ownerPeerId = layer.ownerPeerId,
        selfPeerId = collab.selfPeerId,
        activePeerIds = collab.peers.mapTo(HashSet()) { it.peerId },
        departedPeerIds = collab.departedPeers.keys,
    )
}

internal fun PaintViewModel.orphanedCollabLayers(): List<PaintViewModel.LayerUiState> {
    if (!collab.isActive) return emptyList()
    val activeIds = collab.peers.mapTo(HashSet()) { it.peerId }
    val departedIds = collab.departedPeers.keys
    return layers.filter { layer ->
        CollabPermissionPolicy.isOrphanedDepartedLayer(
            inCollab = true,
            isBackground = layer.isBackground,
            ownerPeerId = layer.ownerPeerId,
            selfPeerId = collab.selfPeerId,
            activePeerIds = activeIds,
            departedPeerIds = departedIds,
        )
    }
}

internal fun PaintViewModel.reclaimOrphanedLayers(
    targetOwnerPeerId: String? = null,
    makeShared: Boolean,
) {
    if (!collab.isActive || collab.role != CollabRole.HOST) return
    val orphanedIds = orphanedCollabLayers()
        .filter { targetOwnerPeerId == null || it.ownerPeerId == targetOwnerPeerId }
        .mapTo(HashSet()) { it.uuid }
    if (orphanedIds.isEmpty()) return

    var updatedCount = 0
    val newOwner = if (makeShared) "" else collab.selfPeerId
    val newPerm = if (makeShared) LayerCollabPermission.SHARED.code else LayerCollabPermission.OWNER_ONLY.code
    runCore(render = false, after = {
        if (updatedCount > 0) {
            syncLayersFromNative()
            layerRevision++
        }
        refreshLayerThumbs(force = false)
        if (updatedCount > 0) {
            showActionToast(getString(R.string.collab_toast_reclaimed_layers, updatedCount), R.drawable.ic_layers)
        }
    }) {
        val count = ReverieCoreBridge.layerCount()
        for (idx in 1 until count) {
            val uuid = ReverieCoreBridge.layerUuid(idx)
            if (uuid in orphanedIds) {
                if (ReverieCoreBridge.layerLocked(idx)) {
                    ReverieCoreBridge.setLayerLocked(idx, false)
                }
                ReverieCoreBridge.setLayerCollabPermission(idx, newPerm)
                ReverieCoreBridge.setLayerOwnerPeerId(idx, newOwner)
                updatedCount++
            }
        }
    }
}

internal fun PaintViewModel.applyAutoFocusOrCreateCollabLayer() {
    if (!collab.isActive || currentPage != Page.PAINTING || layers.isEmpty()) return
    val current = layers.getOrNull(currentLayerIndex)
    val currentReady = current != null &&
        current.ownerPeerId == collab.selfPeerId &&
        canEditLayerInCollab(current) &&
        isLayerTypeSupportedInCollab(current) &&
        !current.isGroup &&
        !LayerNodeType.isMask(current.nodeType) &&
        current.nodeType != LayerNodeType.FILL
    if (currentReady) return

    val descriptors = layers.map { l ->
        CollabLayerDescriptor(
            index = l.index,
            isBackground = l.isBackground,
            isGroup = l.isGroup,
            isLocked = l.locked,
            nodeType = l.nodeType,
            ownerPeerId = l.ownerPeerId,
            permissionCode = l.collabPermission,
            hasLocalSpecialContent = active3DLayers.containsKey(l.id) || activeTextLayers.containsKey(l.id) || l.isStrokeLayer,
        )
    }
    val pickedIdx = CollabPermissionPolicy.pickAutoFocusLayerIndex(descriptors, collab.selfPeerId)
    if (pickedIdx >= 0) {
        val pickedName = layers.getOrNull(pickedIdx)?.name.orEmpty()
        setCurrentLayer(pickedIdx)
        if (pickedName.isNotEmpty()) {
            showActionToast(getString(R.string.collab_toast_autofocus_layer, pickedName), R.drawable.ic_layers)
        }
        return
    }

    val displayArtist = collab.selfName.ifBlank { "Artist" }
    val personalGroupName = getString(R.string.collab_default_personal_group_name, displayArtist)
    val personalName = getString(R.string.collab_default_personal_layer_name, displayArtist)
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
        showActionToast(getString(R.string.collab_toast_autocreate_personal_group, personalGroupName), R.drawable.ic_layers)
    }) {
        // 先创建本人专属图层组，再在组内创建初始颜料层（addLayer 会自动落入当前组并选中它）
        ReverieCoreBridge.addGroupLayer(personalGroupName)
        ReverieCoreBridge.addLayer(personalName)
    }
}

internal fun PaintViewModel.setCollabLayerPermission(index: Int, perm: LayerCollabPermission) {
    val curLayer = layers.getOrNull(index) ?: return
    val curOwner = curLayer.ownerPeerId
    val unlockNative = perm != LayerCollabPermission.LOCKED && curLayer.locked
    runCore(render = false, after = {
        syncLayersFromNative()
        layerRevision++
        refreshLayerThumbs(force = false)
    }) {
        if (unlockNative || (perm != LayerCollabPermission.LOCKED && ReverieCoreBridge.layerLocked(index))) {
            ReverieCoreBridge.setLayerLocked(index, false)
        }
        if (perm == LayerCollabPermission.SHARED) {
            ReverieCoreBridge.setLayerCollabPermission(index, perm.code)
            if (curOwner.isNotEmpty()) {
                ReverieCoreBridge.setLayerOwnerPeerId(index, "")
            }
        } else {
            ReverieCoreBridge.setLayerCollabPermission(index, perm.code)
            if (perm == LayerCollabPermission.OWNER_ONLY && curOwner.isEmpty()) {
                ReverieCoreBridge.setLayerOwnerPeerId(index, collab.selfPeerId)
            }
        }
    }
}

internal fun PaintViewModel.setCollabLayerOwner(index: Int, ownerPeerId: String) {
    runCore(render = false, after = {
        syncLayersFromNative()
        layerRevision++
        refreshLayerThumbs(force = false)
    }) {
        if (ownerPeerId.isNotEmpty()) {
            if (ReverieCoreBridge.layerCollabPermission(index) != LayerCollabPermission.OWNER_ONLY.code) {
                ReverieCoreBridge.setLayerCollabPermission(index, LayerCollabPermission.OWNER_ONLY.code)
            }
            ReverieCoreBridge.setLayerOwnerPeerId(index, ownerPeerId)
        } else {
            if (ReverieCoreBridge.layerCollabPermission(index) != LayerCollabPermission.SHARED.code) {
                ReverieCoreBridge.setLayerCollabPermission(index, LayerCollabPermission.SHARED.code)
            }
            ReverieCoreBridge.setLayerOwnerPeerId(index, "")
        }
    }
}

internal fun PaintViewModel.reportCollabCursor(docX: Float, docY: Float, isDrawing: Boolean, force: Boolean = false) {
    if (!collab.isActive || !collab.nativeStarted) return
    collab.lastCursorDocX = docX
    collab.lastCursorDocY = docY
    val now = android.os.SystemClock.uptimeMillis()
    if (!force && now - collab.lastCursorSentMs < COLLAB_CURSOR_THROTTLE_MS) return
    collab.lastCursorSentMs = now
    renderHandler?.post {
        if (collab.nativeStarted) {
            ReverieCoreBridge.collabSendLocalCursor(docX, docY, isDrawing)
        }
    }
}

internal fun PaintViewModel.reportCollabCursorIdle() {
    if (!collab.isActive || !collab.nativeStarted) return
    reportCollabCursor(collab.lastCursorDocX, collab.lastCursorDocY, isDrawing = false, force = true)
}

internal fun PaintViewModel.enrichLayersWithCollabMeta(
    source: List<PaintViewModel.LayerUiState>,
): List<PaintViewModel.LayerUiState> {
    if (!collab.isActive && !ReverieCoreBridge.collabIsActive()) return source
    val raw = ReverieCoreBridge.collabLayersMetaJson()
    if (raw.isBlank() || raw == "[]") return source
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return source
    val byIndex = HashMap<Int, JSONObject>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        byIndex[o.optInt("index", -1)] = o
    }
    return source.map { layer ->
        val m = byIndex[layer.index]
        if (m == null) {
            layer
        } else {
            layer.copy(
                uuid = m.optString("uuid", layer.uuid),
                ownerPeerId = m.optString("ownerPeerId", layer.ownerPeerId),
                collabPermission = m.optInt("collabPermission", layer.collabPermission),
            )
        }
    }
}

private fun normalizePeersWithFlags(
    peers: List<CollabPeer>,
    hostPeerId: String,
    p2pMap: Map<String, Boolean>,
): List<CollabPeer> {
    return peers.distinctBy { it.peerId }.map { p ->
        val role = if (p.peerId == hostPeerId) CollabRole.HOST else CollabRole.PEER
        val p2p = p2pMap[p.peerId] ?: p.connectedP2P
        p.copy(
            role = role,
            badgeColorArgb = CollabTopologyPolicy.assignBadgeColorArgb(p.peerId),
            connectedP2P = p2p,
        )
    }.sortedWith(
        compareByDescending<CollabPeer> { it.role == CollabRole.HOST }
            .thenBy { it.joinedAt }
            .thenBy { it.peerId }
    )
}

/** 房内不退出保存当前合作画布副本到本地工程列表 */
internal fun PaintViewModel.saveCollabBackupInPlace() {
    if (!collab.isActive) return
    val name = generateNextCollabProjectName(collab.roomCode)
    val roomTag = collab.roomCode
    runCore(render = false) {
        ReverieCoreBridge.setProjectCollabMeta(true, roomTag)
    }
    saveProject(name) {
        showActionToast(R.string.collab_backup_saved_toast, R.drawable.ic_save)
    }
}

/** 跳转到指定协作者当前画布坐标（关闭弹窗并居中对准对方光标） */
internal fun PaintViewModel.focusViewportOnCollabPeer(peerId: String) {
    val cursor = collab.remoteCursors.firstOrNull { it.peerId == peerId }
    val name = collab.peers.firstOrNull { it.peerId == peerId }?.name ?: peerId
    if (cursor == null) {
        showActionToast(getString(R.string.collab_peer_no_cursor_toast, name), R.drawable.ic_cloud)
        return
    }
    collab.showRoomSheet = false
    collab.pendingFocusDocPoint = cursor.docX to cursor.docY
    showActionToast(getString(R.string.collab_located_peer_toast, name), R.drawable.ic_cloud)
}

/** 发送轻量房间短消息（经由信令透传，不干扰 C++ 笔画数据流） */
internal fun PaintViewModel.sendCollabChatMessage(rawText: String) {
    if (!collab.isActive) return
    val clean = rawText.replace(Regex("[\\r\\n]+"), " ").trim().take(80)
    if (clean.isEmpty()) return
    val client = collab.signalingClient
    val payload = JSONObject().apply {
        put("kind", "collab_chat")
        put("text", clean)
        put("name", collab.selfName)
    }
    if (client != null) {
        for (p in collab.peers) {
            if (p.peerId != collab.selfPeerId && p.signalingOnline) {
                client.sendSignal(p.peerId, payload)
            }
        }
    }
    appendCollabChatEntry(
        peerId = collab.selfPeerId,
        senderName = collab.selfName,
        text = clean,
        isSelf = true,
        showBanner = false,
    )
}

/** 在指定文档坐标发送脉冲提醒标记（Ping），通知全房画师查看当前区域 */
internal fun PaintViewModel.sendCollabCanvasPing(docX: Float, docY: Float) {
    if (!collab.isActive || !docX.isFinite() || !docY.isFinite()) return
    val client = collab.signalingClient
    val payload = JSONObject().apply {
        put("kind", "collab_ping")
        put("docX", docX.toDouble())
        put("docY", docY.toDouble())
        put("name", collab.selfName)
    }
    if (client != null) {
        for (p in collab.peers) {
            if (p.peerId != collab.selfPeerId && p.signalingOnline) {
                client.sendSignal(p.peerId, payload)
            }
        }
    }
    appendCollabPingEntry(
        peerId = collab.selfPeerId,
        senderName = collab.selfName,
        docX = docX,
        docY = docY,
    )
    showActionToast(R.string.collab_ping_sent_toast, R.drawable.ic_cloud)
}

private fun PaintViewModel.handleInboundCollabChat(fromPeerId: String, senderName: String, text: String) {
    val resolvedName = senderName.ifBlank { collabOwnerDisplayName(fromPeerId) }
    appendCollabChatEntry(
        peerId = fromPeerId,
        senderName = resolvedName,
        text = text,
        isSelf = false,
        showBanner = true,
    )
}

private fun PaintViewModel.appendCollabChatEntry(
    peerId: String,
    senderName: String,
    text: String,
    isSelf: Boolean,
    showBanner: Boolean,
) {
    val msg = CollabChatMessage(
        id = UUID.randomUUID().toString(),
        peerId = peerId,
        senderName = senderName,
        badgeColorArgb = CollabTopologyPolicy.assignBadgeColorArgb(peerId),
        text = text,
        timestampMs = System.currentTimeMillis(),
        isSelf = isSelf,
    )
    collab.chatMessages = (collab.chatMessages + msg).takeLast(30)
    if (showBanner) {
        val gen = collab.sessionGeneration
        collab.latestChatBanner = msg
        collab.chatBannerClearJob?.cancel()
        collab.chatBannerClearJob = viewModelScope.launch {
            delay(COLLAB_CHAT_BANNER_DURATION_MS)
            if (collab.sessionGeneration == gen && collab.latestChatBanner?.id == msg.id) {
                collab.latestChatBanner = null
            }
        }
    }
}

private fun PaintViewModel.handleInboundCollabPing(
    fromPeerId: String,
    senderName: String,
    docX: Float,
    docY: Float,
) {
    val resolvedName = senderName.ifBlank { collabOwnerDisplayName(fromPeerId) }
    appendCollabPingEntry(
        peerId = fromPeerId,
        senderName = resolvedName,
        docX = docX,
        docY = docY,
    )
    showActionToast(getString(R.string.collab_ping_received_toast, resolvedName), R.drawable.ic_cloud)
}

private fun PaintViewModel.appendCollabPingEntry(
    peerId: String,
    senderName: String,
    docX: Float,
    docY: Float,
) {
    val now = android.os.SystemClock.uptimeMillis()
    val ping = CollabCanvasPing(
        id = UUID.randomUUID().toString(),
        peerId = peerId,
        senderName = senderName,
        badgeColorArgb = CollabTopologyPolicy.assignBadgeColorArgb(peerId),
        docX = docX,
        docY = docY,
        createdAtUptimeMs = now,
    )
    collab.canvasPings = (collab.canvasPings.filter { now - it.createdAtUptimeMs < COLLAB_PING_DURATION_MS } + ping).takeLast(12)
    val gen = collab.sessionGeneration
    if (collab.pingPruneJob?.isActive != true) {
        collab.pingPruneJob = viewModelScope.launch {
            while (isActive && collab.sessionGeneration == gen && collab.canvasPings.isNotEmpty()) {
                delay(250L)
                val cur = android.os.SystemClock.uptimeMillis()
                collab.canvasPings = collab.canvasPings.filter { cur - it.createdAtUptimeMs < COLLAB_PING_DURATION_MS }
            }
        }
    }
}
