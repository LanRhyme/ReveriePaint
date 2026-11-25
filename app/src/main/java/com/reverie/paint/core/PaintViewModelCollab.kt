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
import com.reverie.paint.model.CollabConnectionPhase
import com.reverie.paint.model.CollabPendingRequest
import com.reverie.paint.model.CollabPeer
import com.reverie.paint.model.CollabPermissionPolicy
import com.reverie.paint.model.CollabRole
import com.reverie.paint.model.CollabTopologyPolicy
import com.reverie.paint.model.LayerCollabPermission
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
private const val COLLAB_DRAIN_INTERVAL_MS = 16L
private const val COLLAB_CURSOR_THROTTLE_MS = 33L
private const val COLLAB_HOST_OFFLINE_CLAIM_MS = 3500L

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
    var requireApproval by mutableStateOf(false)
    var maxPeers by mutableIntStateOf(16)
    var peers by mutableStateOf<List<CollabPeer>>(emptyList())
    var pendingRequests by mutableStateOf<List<CollabPendingRequest>>(emptyList())
    var remoteCursors by mutableStateOf<List<RemoteCursorState>>(emptyList())
    var snapshotReceivedChunks by mutableIntStateOf(0)
    var snapshotTotalChunks by mutableIntStateOf(0)
    var statusMessage by mutableStateOf<String?>(null)
    var showLobbyDialog by mutableStateOf(false)
    var showRoomSheet by mutableStateOf(false)

    internal var sessionGeneration: Long = 0L
    internal var signalingClient: SignalingClient? = null
    internal var drainJob: Job? = null
    internal var hostOfflineTimerJob: Job? = null
    internal val drainInFlight = AtomicBoolean(false)
    internal var nativeStarted: Boolean = false
    internal var lastCursorSentMs: Long = 0L
    internal var lastCursorDocX: Float = 0f
    internal var lastCursorDocY: Float = 0f
    internal var prefsLoaded: Boolean = false
}

internal fun PaintViewModel.ensureCollabPrefsLoaded() {
    if (collab.prefsLoaded || !isAppContextReady()) return
    collab.prefsLoaded = true
    val savedName = prefs.getString(PREF_COLLAB_NICKNAME, null).orEmpty().trim()
    collab.selfName = savedName.ifBlank { getString(R.string.collab_nickname_default) }
    val savedUrl = prefs.getString(PREF_COLLAB_SIGNALING_URL, null).orEmpty().trim()
    collab.signalingUrl = savedUrl.ifBlank { CollabTopologyPolicy.DEFAULT_SIGNALING_URL }
    var peerId = prefs.getString(PREF_COLLAB_PEER_ID, null).orEmpty().trim()
    if (peerId.isBlank()) {
        peerId = "p_" + UUID.randomUUID().toString().replace("-", "").take(10)
        prefs.edit().putString(PREF_COLLAB_PEER_ID, peerId).apply()
    }
    collab.selfPeerId = peerId
}

internal fun PaintViewModel.saveCollabIdentityPrefs(nickname: String, signalingUrl: String) {
    val cleanName = nickname.trim().ifBlank { getString(R.string.collab_nickname_default) }
    val cleanUrl = signalingUrl.trim().trimEnd('/').ifBlank { CollabTopologyPolicy.DEFAULT_SIGNALING_URL }
    collab.selfName = cleanName
    collab.signalingUrl = cleanUrl
    if (isAppContextReady()) {
        prefs.edit()
            .putString(PREF_COLLAB_NICKNAME, cleanName)
            .putString(PREF_COLLAB_SIGNALING_URL, cleanUrl)
            .apply()
    }
}

internal fun PaintViewModel.createCollabRoom(
    nickname: String,
    password: String = "",
    requireApproval: Boolean = false,
    maxPeers: Int = 16,
    signalingUrl: String = collab.signalingUrl,
    defaultCanvasW: Int = 2048,
    defaultCanvasH: Int = 2048,
) {
    ensureCollabPrefsLoaded()
    saveCollabIdentityPrefs(nickname, signalingUrl)

    if (collab.isActive) {
        leaveCollabRoom()
    }

    val gen = ++collab.sessionGeneration
    val selfId = collab.selfPeerId
    val displayName = collab.selfName
    val baseUrl = collab.signalingUrl
    val clampedMax = maxPeers.coerceIn(2, 32)

    val canvasW: Int
    val canvasH: Int
    if (currentPage != Page.PAINTING) {
        canvasW = defaultCanvasW.coerceIn(128, 8192)
        canvasH = defaultCanvasH.coerceIn(128, 8192)
        startPainting(w = canvasW, h = canvasH, name = "Collab")
    } else {
        canvasW = docWidth.coerceAtLeast(1)
        canvasH = docHeight.coerceAtLeast(1)
    }

    collab.isActive = true
    collab.phase = CollabConnectionPhase.CREATING
    collab.role = CollabRole.HOST
    collab.requireApproval = requireApproval
    collab.maxPeers = clampedMax
    collab.hostPeerId = selfId
    collab.hostToken = ""
    collab.roomCode = ""
    collab.peers = listOf(CollabPeer(peerId = selfId, name = displayName, role = CollabRole.HOST, connectedP2P = true))
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
            canvasWidth = canvasW,
            canvasHeight = canvasH,
        )
        if (collab.sessionGeneration != gen) return@launch
        result.onFailure { err ->
            val msg = err.message ?: "network_error"
            collab.phase = CollabConnectionPhase.ERROR
            collab.isActive = false
            collab.statusMessage = getString(R.string.collab_status_error, msg)
            showActionToast(collab.statusMessage!!, R.drawable.ic_cloud)
        }.onSuccess { created ->
            collab.roomCode = created.roomCode
            collab.hostPeerId = created.hostPeerId
            collab.hostToken = created.hostToken
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
) {
    ensureCollabPrefsLoaded()
    saveCollabIdentityPrefs(nickname, signalingUrl)

    val normCode = CollabTopologyPolicy.normalizeRoomCode(rawRoomCode)
    if (!CollabTopologyPolicy.isValidRoomCode(normCode)) {
        collab.phase = CollabConnectionPhase.ERROR
        collab.statusMessage = getString(R.string.collab_room_code_hint)
        showActionToast(collab.statusMessage!!, R.drawable.ic_cloud)
        return
    }

    if (collab.isActive) {
        leaveCollabRoom()
    }

    val gen = ++collab.sessionGeneration
    val selfId = collab.selfPeerId
    val displayName = collab.selfName
    val baseUrl = collab.signalingUrl

    collab.isActive = true
    collab.phase = CollabConnectionPhase.JOINING
    collab.role = CollabRole.PEER
    collab.roomCode = normCode
    collab.hostToken = ""
    collab.peers = emptyList()
    collab.pendingRequests = emptyList()
    collab.remoteCursors = emptyList()
    collab.snapshotReceivedChunks = 0
    collab.snapshotTotalChunks = 0
    collab.statusMessage = getString(R.string.collab_status_connecting)

    val client = SignalingClient(viewModelScope)
    collab.signalingClient = client

    viewModelScope.launch {
        val infoResult = client.fetchRoomInfo(baseUrl = baseUrl, roomCode = normCode)
        if (collab.sessionGeneration != gen) return@launch
        infoResult.onFailure { err ->
            val msg = err.message ?: "not_found"
            collab.phase = CollabConnectionPhase.ERROR
            collab.isActive = false
            collab.statusMessage = getString(R.string.collab_status_error, msg)
            showActionToast(collab.statusMessage!!, R.drawable.ic_cloud)
        }.onSuccess { summary ->
            collab.hostPeerId = summary.hostPeerId
            collab.requireApproval = summary.requireApproval
            collab.maxPeers = summary.maxPeers
            if (currentPage != Page.PAINTING) {
                val targetW = summary.canvasWidth.takeIf { it > 0 } ?: 1600
                val targetH = summary.canvasHeight.takeIf { it > 0 } ?: 1200
                startPainting(w = targetW, h = targetH, name = "Collab-$normCode")
            }
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
        ) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.roomCode = roomCode
                collab.selfPeerId = selfPeerId
                collab.hostPeerId = hostPeerId
                collab.role = if (selfPeerId == hostPeerId) CollabRole.HOST else CollabRole.PEER
                collab.peers = normalizePeersWithFlags(peers, hostPeerId, emptyMap())
                if (status == "waiting_approval") {
                    collab.phase = CollabConnectionPhase.WAITING_APPROVAL
                    collab.statusMessage = getString(R.string.collab_status_waiting_approval)
                } else {
                    collab.phase = CollabConnectionPhase.CONNECTED
                    collab.statusMessage = null
                    collab.showLobbyDialog = false
                    collab.showRoomSheet = true
                    beginNativeCollabSession(gen, roomCode, selfPeerId, collab.selfName, hostPeerId, iceServersJson, peers)
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
        ) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.roomCode = roomCode
                collab.selfPeerId = selfPeerId
                collab.hostPeerId = hostPeerId
                collab.role = if (selfPeerId == hostPeerId) CollabRole.HOST else CollabRole.PEER
                collab.peers = normalizePeersWithFlags(peers, hostPeerId, emptyMap())
                collab.phase = CollabConnectionPhase.CONNECTED
                collab.statusMessage = null
                collab.showLobbyDialog = false
                collab.showRoomSheet = true
                beginNativeCollabSession(gen, roomCode, selfPeerId, collab.selfName, hostPeerId, iceServersJson, peers)
            }
        }

        override fun onJoinRequest(request: CollabPendingRequest) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val existing = collab.pendingRequests.filterNot { it.peerId == request.peerId }
                collab.pendingRequests = existing + request
                collab.showRoomSheet = true
            }
        }

        override fun onPeerJoined(peer: CollabPeer) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val p2pStates = collab.peers.associate { it.peerId to it.connectedP2P }
                val updated = collab.peers.filterNot { it.peerId == peer.peerId } + peer
                collab.peers = normalizePeersWithFlags(updated, collab.hostPeerId, p2pStates)
                if (peer.peerId != collab.selfPeerId) {
                    runCore(render = false) {
                        ReverieCoreBridge.collabOnPeerJoined(
                            peer.peerId,
                            peer.name,
                            peer.role == CollabRole.HOST,
                        )
                    }
                }
            }
        }

        override fun onPeerLeft(peerId: String, reason: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.peers = collab.peers.filterNot { it.peerId == peerId }
                collab.pendingRequests = collab.pendingRequests.filterNot { it.peerId == peerId }
                collab.remoteCursors = collab.remoteCursors.filterNot { it.peerId == peerId }
                runCore(render = false) {
                    ReverieCoreBridge.collabOnPeerLeft(peerId)
                }
            }
        }

        override fun onSignal(fromPeerId: String, payloadJson: String) {
            runCore(render = false) {
                if (collab.sessionGeneration == gen && collab.nativeStarted) {
                    ReverieCoreBridge.collabOnRemoteSignal(fromPeerId, payloadJson)
                }
            }
        }

        override fun onHostChanged(newHostPeerId: String, hostToken: String?) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                collab.hostOfflineTimerJob?.cancel()
                collab.hostOfflineTimerJob = null
                collab.hostPeerId = newHostPeerId
                collab.role = if (newHostPeerId == collab.selfPeerId) CollabRole.HOST else CollabRole.PEER
                if (!hostToken.isNullOrBlank()) {
                    collab.hostToken = hostToken
                }
                val p2pStates = collab.peers.associate { it.peerId to it.connectedP2P }
                collab.peers = normalizePeersWithFlags(collab.peers, newHostPeerId, p2pStates)
                runCore(render = false) {
                    ReverieCoreBridge.collabOnHostChanged(newHostPeerId)
                }
                val newHostName = collab.peers.firstOrNull { it.peerId == newHostPeerId }?.name ?: newHostPeerId
                showActionToast(getString(R.string.collab_host_migrated_msg, newHostName), R.drawable.ic_cloud)
            }
        }

        override fun onHostOffline(hostPeerId: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
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
            }
        }

        override fun onRejected() {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                teardownCollabLocalState()
                collab.phase = CollabConnectionPhase.ERROR
                collab.statusMessage = getString(R.string.collab_rejected_msg)
                showActionToast(R.string.collab_rejected_msg, R.drawable.ic_cloud)
            }
        }

        override fun onKicked() {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                teardownCollabLocalState()
                showActionToast(R.string.collab_kicked_msg, R.drawable.ic_cloud)
            }
        }

        override fun onRoomClosed(reason: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                teardownCollabLocalState()
                showActionToast(R.string.collab_room_closed_msg, R.drawable.ic_cloud)
            }
        }

        override fun onError(code: String, message: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen) return@post
                val detail = message.ifBlank { code }
                collab.phase = CollabConnectionPhase.ERROR
                collab.statusMessage = getString(R.string.collab_status_error, detail)
                showActionToast(collab.statusMessage!!, R.drawable.ic_cloud)
            }
        }

        override fun onSocketClosed(code: Int, reason: String) {
            mainHandler.post {
                if (collab.sessionGeneration != gen || !collab.isActive) return@post
                if (code != 1000 && collab.phase != CollabConnectionPhase.ERROR) {
                    collab.phase = CollabConnectionPhase.RECONNECTING
                    collab.statusMessage = reason.ifBlank { getString(R.string.collab_status_connecting) }
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
    runCore(render = true, after = {
        if (collab.sessionGeneration == gen) {
            collab.nativeStarted = true
            startCollabPollingLoop(gen)
            refreshUndoCounts()
        }
    }) {
        val ok = ReverieCoreBridge.collabStartSession(
            roomCode,
            selfPeerId,
            selfName,
            hostPeerId,
            iceServersJson.ifBlank { "[]" },
            cacheDir,
        )
        if (ok) {
            for (p in initialPeers) {
                if (p.peerId != selfPeerId) {
                    ReverieCoreBridge.collabOnPeerJoined(p.peerId, p.name, p.role == CollabRole.HOST)
                }
            }
            syncLayersFromNative()
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
                            if (eventsJson != "[]" || cursorsJson != "[]" || collab.remoteCursors.isNotEmpty()) {
                                mainHandler.post {
                                    if (collab.sessionGeneration == gen) {
                                        handleCollabPolledData(gen, eventsJson, cursorsJson)
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

private fun PaintViewModel.handleCollabPolledData(gen: Long, eventsJson: String, cursorsJson: String) {
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
                            isBlockingLoading = false
                            refreshLayerThumbs(force = true)
                            refreshUndoCounts()
                        }) {
                            displayBufferInvalid = true
                            syncLayersFromNative()
                        }
                    }
                    "layers_changed" -> {
                        runCore(render = true, after = {
                            if (collab.sessionGeneration == gen) {
                                refreshLayerThumbs(force = false)
                                refreshUndoCounts()
                            }
                        }) {
                            syncLayersFromNative()
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
}

internal fun PaintViewModel.handoffCollabHost(newHostPeerId: String) {
    collab.signalingClient?.handoffHost(newHostPeerId)
}

internal fun PaintViewModel.leaveCollabRoom(synchronousNativeStop: Boolean = false) {
    teardownCollabLocalState(synchronousNativeStop = synchronousNativeStop)
}

internal fun PaintViewModel.closeCollabRoom() {
    if (collab.role == CollabRole.HOST) {
        collab.signalingClient?.closeRoom()
    }
    teardownCollabLocalState(synchronousNativeStop = false)
}

private fun PaintViewModel.teardownCollabLocalState(synchronousNativeStop: Boolean = false) {
    collab.sessionGeneration++
    collab.drainJob?.cancel()
    collab.drainJob = null
    collab.hostOfflineTimerJob?.cancel()
    collab.hostOfflineTimerJob = null
    collab.signalingClient?.disconnect()
    collab.signalingClient = null

    val wasNativeStarted = collab.nativeStarted
    collab.nativeStarted = false
    collab.isActive = false
    collab.phase = CollabConnectionPhase.IDLE
    collab.roomCode = ""
    collab.hostPeerId = ""
    collab.hostToken = ""
    collab.peers = emptyList()
    collab.pendingRequests = emptyList()
    collab.remoteCursors = emptyList()
    collab.snapshotReceivedChunks = 0
    collab.snapshotTotalChunks = 0
    collab.statusMessage = null
    collab.showRoomSheet = false

    if (wasNativeStarted) {
        if (synchronousNativeStop || renderHandler == null) {
            runCatching { ReverieCoreBridge.collabStopSession() }
        } else {
            runCore(render = true, after = { refreshUndoCounts() }) {
                ReverieCoreBridge.collabStopSession()
                syncLayersFromNative()
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

internal fun PaintViewModel.setCollabLayerPermission(index: Int, perm: LayerCollabPermission) {
    val curOwner = layers.getOrNull(index)?.ownerPeerId.orEmpty()
    val nextOwner = if (perm == LayerCollabPermission.OWNER_ONLY && curOwner.isEmpty()) {
        collab.selfPeerId
    } else {
        curOwner
    }
    runCore(render = false, after = {
        refreshLayerThumbs(force = false)
    }) {
        if (nextOwner != curOwner) {
            ReverieCoreBridge.setLayerOwnerPeerId(index, nextOwner)
        }
        ReverieCoreBridge.setLayerCollabPermission(index, perm.code)
        syncLayersFromNative()
    }
}

internal fun PaintViewModel.setCollabLayerOwner(index: Int, ownerPeerId: String) {
    runCore(render = false, after = {
        refreshLayerThumbs(force = false)
    }) {
        ReverieCoreBridge.setLayerOwnerPeerId(index, ownerPeerId)
        if (ownerPeerId.isNotEmpty() && ReverieCoreBridge.layerCollabPermission(index) == LayerCollabPermission.SHARED.code) {
            ReverieCoreBridge.setLayerCollabPermission(index, LayerCollabPermission.OWNER_ONLY.code)
        }
        syncLayersFromNative()
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

internal fun PaintViewModel.collabOwnerDisplayName(ownerPeerId: String): String {
    if (ownerPeerId.isBlank()) return getString(R.string.collab_layer_owner_unassigned)
    if (ownerPeerId == collab.selfPeerId && collab.selfName.isNotBlank()) return collab.selfName
    return collab.peers.firstOrNull { it.peerId == ownerPeerId }?.name ?: ownerPeerId.take(8)
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
