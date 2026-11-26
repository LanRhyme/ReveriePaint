/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.collab

import com.reverie.paint.model.CollabPendingRequest
import com.reverie.paint.model.CollabPeer
import com.reverie.paint.model.CollabPublicRoom
import com.reverie.paint.model.CollabRole
import com.reverie.paint.model.CollabRoomSummary
import com.reverie.paint.model.CollabTopologyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CreatedRoomResponse(
    val roomCode: String,
    val hostPeerId: String,
    val hostToken: String,
    val iceServersJson: String,
    val expiresInMs: Long,
    val easyTierJson: String = "{}",
)

interface SignalingEventListener {
    fun onWelcome(
        roomCode: String,
        selfPeerId: String,
        hostPeerId: String,
        status: String,
        iceServersJson: String,
        peers: List<CollabPeer>,
        canvasWidth: Int,
        canvasHeight: Int,
        easyTierJson: String = "{}",
    )

    fun onAdmitted(
        roomCode: String,
        selfPeerId: String,
        hostPeerId: String,
        iceServersJson: String,
        peers: List<CollabPeer>,
        canvasWidth: Int,
        canvasHeight: Int,
        easyTierJson: String = "{}",
    )

    fun onJoinRequest(request: CollabPendingRequest)
    fun onPeerJoined(peer: CollabPeer)
    fun onPeerLeft(peerId: String, reason: String)
    fun onPeerOffline(peerId: String)
    fun onPeerOnline(peerId: String)
    fun onSignal(fromPeerId: String, payloadJson: String)
    fun onSignalUndelivered(targetPeerId: String)
    fun onHostChanged(newHostPeerId: String, hostToken: String?, hostVirtualIp: String = "")
    fun onHostOffline(hostPeerId: String)
    fun onHostOnline(hostPeerId: String)
    fun onRejected()
    fun onKicked()
    fun onRoomClosed(reason: String)
    fun onError(code: String, message: String)
    fun onSocketClosed(code: Int, reason: String)
}

/**
 * Cloudflare Worker + Durable Objects 信令客户端 (HTTP 建房/探查 + WebSocket 交换 SDP/ICE)。
 * 严格遵守不上传任何画布内容与笔画数据的边界: 仅用于建联与房间控制。
 */
class SignalingClient(
    private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var lastInboundMs: Long = 0L
    private var pingJob: Job? = null

    suspend fun createRoom(
        baseUrl: String,
        hostPeerId: String,
        hostName: String,
        password: String,
        requireApproval: Boolean,
        maxPeers: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        isPublic: Boolean = false,
        title: String = "",
    ): Result<CreatedRoomResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val cleanTitle = CollabTopologyPolicy.sanitizeRoomTitle(title)
            val bodyJson = JSONObject().apply {
                put("hostPeerId", hostPeerId)
                put("hostName", hostName)
                if (password.isNotEmpty()) put("password", password)
                put("requireApproval", requireApproval)
                put("maxPeers", maxPeers)
                put("isPublic", isPublic)
                if (cleanTitle.isNotEmpty()) put("title", cleanTitle)
                put("canvasMeta", JSONObject().apply {
                    put("width", canvasWidth)
                    put("height", canvasHeight)
                })
            }
            val req = Request.Builder()
                .url("$cleanBase/c/v1/rooms")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = JSONObject(text.ifEmpty { "{}" })
                if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "http_${resp.code}"))
                }
                CreatedRoomResponse(
                    roomCode = json.getString("roomCode"),
                    hostPeerId = json.getString("hostPeerId"),
                    hostToken = json.getString("hostToken"),
                    iceServersJson = json.optJSONArray("iceServers")?.toString() ?: "[]",
                    expiresInMs = json.optLong("expiresInMs", 86400_000L),
                    easyTierJson = json.optJSONObject("easyTier")?.toString() ?: "{}",
                )
            }
        }
    }

    suspend fun fetchRoomInfo(
        baseUrl: String,
        roomCode: String,
    ): Result<CollabRoomSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val normCode = CollabTopologyPolicy.normalizeRoomCode(roomCode)
            val req = Request.Builder()
                .url("$cleanBase/c/v1/rooms/$normCode/info")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = JSONObject(text.ifEmpty { "{}" })
                if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "http_${resp.code}"))
                }
                val meta = json.optJSONObject("canvasMeta")
                val namesArr = json.optJSONArray("peerNames")
                val peerNames = buildList {
                    if (namesArr != null) {
                        for (i in 0 until namesArr.length()) {
                            val n = namesArr.optString(i).trim()
                            if (n.isNotEmpty()) add(n)
                        }
                    }
                }
                CollabRoomSummary(
                    roomCode = json.getString("roomCode"),
                    hostPeerId = json.getString("hostPeerId"),
                    hostName = json.optString("hostName", "Host"),
                    requireApproval = json.optBoolean("requireApproval", false),
                    hasPassword = json.optBoolean("hasPassword", false),
                    maxPeers = json.optInt("maxPeers", 16),
                    peerCount = json.optInt("peerCount", 0),
                    canvasWidth = meta?.optInt("width", 0) ?: 0,
                    canvasHeight = meta?.optInt("height", 0) ?: 0,
                    peerNames = peerNames,
                    isPublic = json.optBoolean("isPublic", false),
                    title = json.optString("title", ""),
                )
            }
        }
    }

    suspend fun fetchPublicRooms(
        baseUrl: String,
        limit: Int = 50,
    ): Result<List<CollabPublicRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val safeLimit = limit.coerceIn(1, 100)
            val req = Request.Builder()
                .url("$cleanBase/c/v1/rooms?limit=$safeLimit")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = JSONObject(text.ifEmpty { "{}" })
                if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "http_${resp.code}"))
                }
                val arr = json.optJSONArray("rooms") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val code = CollabTopologyPolicy.normalizeRoomCode(obj.optString("roomCode"))
                        val title = obj.optString("title").trim()
                        if (!CollabTopologyPolicy.isValidRoomCode(code) || title.isEmpty()) continue
                        add(
                            CollabPublicRoom(
                                roomCode = code,
                                title = title,
                                hostName = obj.optString("hostName", "Host"),
                                peerCount = obj.optInt("peerCount", 1).coerceAtLeast(0),
                                maxPeers = obj.optInt("maxPeers", 16).coerceAtLeast(2),
                                hasPassword = obj.optBoolean("hasPassword", false),
                                requireApproval = obj.optBoolean("requireApproval", false),
                                canvasWidth = obj.optInt("canvasWidth", 0),
                                canvasHeight = obj.optInt("canvasHeight", 0),
                                createdAt = obj.optLong("createdAt", 0L),
                                updatedAt = obj.optLong("updatedAt", 0L),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun connectWebSocket(
        baseUrl: String,
        roomCode: String,
        peerId: String,
        role: CollabRole,
        hostToken: String?,
        name: String,
        password: String?,
        listener: SignalingEventListener,
    ) {
        disconnect()
        val cleanBase = normalizeBaseUrl(baseUrl)
        val normCode = CollabTopologyPolicy.normalizeRoomCode(roomCode)
        val httpUrl = "$cleanBase/c/v1/rooms/$normCode/ws".toHttpUrlOrNull()
            ?: run {
                listener.onError("invalid_url", "Invalid signaling URL")
                return
            }
        val urlBuilder = httpUrl.newBuilder()
            .addQueryParameter("peerId", peerId)
            .addQueryParameter("role", if (role == CollabRole.HOST) "host" else "peer")
            .addQueryParameter("name", name)
        if (!hostToken.isNullOrEmpty() && role == CollabRole.HOST) {
            urlBuilder.addQueryParameter("token", hostToken)
        }
        if (!password.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("pw", password)
        }
        val wsUrl = urlBuilder.build().toString()
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")

        val req = Request.Builder().url(wsUrl).build()
        val ws = http.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    lastInboundMs = android.os.SystemClock.elapsedRealtime()
                    startKeepAlive(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    lastInboundMs = android.os.SystemClock.elapsedRealtime()
                    if (text == "pong") return
                    handleIncomingJson(text, listener)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    stopKeepAlive()
                    listener.onSocketClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    stopKeepAlive()
                    val code = response?.code
                    if (response != null && code != null && code in 400..499) {
                        val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                        val errJson = runCatching { JSONObject(errBody.ifEmpty { "{}" }) }.getOrNull()
                        val errCode = errJson?.optString("error").orEmpty().ifBlank { "http_$code" }
                        val errMsg = errJson?.optString("message").orEmpty()
                        listener.onError(errCode, errMsg)
                        return
                    }
                    listener.onError("ws_failure", t.message ?: "WebSocket connection failed")
                }
            },
        )
        webSocket = ws
    }

    fun sendSignal(targetPeerId: String, payload: JSONObject): Boolean =
        sendJson(
            JSONObject().apply {
                put("type", "signal")
                put("to", targetPeerId)
                put("payload", payload)
            },
        )

    fun approvePeer(targetPeerId: String): Boolean =
        sendJson(JSONObject().apply { put("type", "approve"); put("targetPeerId", targetPeerId) })

    fun rejectPeer(targetPeerId: String): Boolean =
        sendJson(JSONObject().apply { put("type", "reject"); put("targetPeerId", targetPeerId) })

    fun kickPeer(targetPeerId: String): Boolean =
        sendJson(JSONObject().apply { put("type", "kick"); put("targetPeerId", targetPeerId) })

    fun handoffHost(newHostPeerId: String): Boolean =
        sendJson(JSONObject().apply { put("type", "handoff_host"); put("newHostPeerId", newHostPeerId) })

    fun claimHost(): Boolean =
        sendJson(JSONObject().apply { put("type", "claim_host") })

    fun closeRoom(): Boolean =
        sendJson(JSONObject().apply { put("type", "close_room") })

    fun sendLeave(): Boolean =
        sendJson(JSONObject().apply { put("type", "leave") })

    fun disconnect(gracefulLeave: Boolean = false) {
        if (gracefulLeave) {
            runCatching { sendLeave() }
        }
        stopKeepAlive()
        val ws = webSocket
        webSocket = null
        try {
            ws?.close(1000, if (gracefulLeave) "client_leave" else "client_disconnect")
        } catch (_: Throwable) {
        }
    }

    private fun sendJson(obj: JSONObject): Boolean = webSocket?.send(obj.toString()) == true

    private fun startKeepAlive(ws: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20_000L)
                if (webSocket !== ws) break
                val idleMs = android.os.SystemClock.elapsedRealtime() - lastInboundMs
                if (lastInboundMs > 0L && idleMs > 35_000L) {
                    ws.cancel()
                    break
                }
                // 文本 "ping" 触发 Cloudflare DO AutoResponse 边缘直接回 "pong", 零计算账单
                ws.send("ping")
            }
        }
    }

    private fun stopKeepAlive() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun handleIncomingJson(raw: String, listener: SignalingEventListener) {
        val msg = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return
        }
        when (msg.optString("type")) {
            "welcome" -> {
                val meta = msg.optJSONObject("canvasMeta")
                listener.onWelcome(
                    roomCode = msg.optString("roomCode"),
                    selfPeerId = msg.optString("selfPeerId"),
                    hostPeerId = msg.optString("hostPeerId"),
                    status = msg.optString("status", "admitted"),
                    iceServersJson = msg.optJSONArray("iceServers")?.toString() ?: "[]",
                    peers = parsePeers(msg.optJSONArray("peers")),
                    canvasWidth = meta?.optInt("width", 0) ?: 0,
                    canvasHeight = meta?.optInt("height", 0) ?: 0,
                    easyTierJson = msg.optJSONObject("easyTier")?.toString() ?: "{}",
                )
            }
            "admitted" -> {
                val meta = msg.optJSONObject("canvasMeta")
                listener.onAdmitted(
                    roomCode = msg.optString("roomCode"),
                    selfPeerId = msg.optString("selfPeerId"),
                    hostPeerId = msg.optString("hostPeerId"),
                    iceServersJson = msg.optJSONArray("iceServers")?.toString() ?: "[]",
                    peers = parsePeers(msg.optJSONArray("peers")),
                    canvasWidth = meta?.optInt("width", 0) ?: 0,
                    canvasHeight = meta?.optInt("height", 0) ?: 0,
                    easyTierJson = msg.optJSONObject("easyTier")?.toString() ?: "{}",
                )
            }
            "join_request" -> {
                val p = msg.optJSONObject("peer") ?: return
                listener.onJoinRequest(
                    CollabPendingRequest(
                        peerId = p.getString("peerId"),
                        name = p.optString("name", "Artist"),
                        joinedAt = p.optLong("joinedAt", 0L),
                    ),
                )
            }
            "peer_joined" -> {
                val p = msg.optJSONObject("peer") ?: return
                listener.onPeerJoined(parsePeerObj(p))
            }
            "peer_left" -> listener.onPeerLeft(
                peerId = msg.optString("peerId"),
                reason = msg.optString("reason", "disconnected"),
            )
            "peer_offline" -> listener.onPeerOffline(msg.optString("peerId"))
            "peer_online" -> listener.onPeerOnline(msg.optString("peerId"))
            "signal" -> {
                val from = msg.optString("from")
                val payload = msg.optJSONObject("payload")?.toString() ?: return
                listener.onSignal(from, payload)
            }
            "signal_undelivered" -> listener.onSignalUndelivered(msg.optString("to"))
            "host_promoted" -> listener.onHostChanged(
                newHostPeerId = msg.optString("hostPeerId"),
                hostToken = msg.optString("hostToken").takeIf { it.isNotEmpty() },
                hostVirtualIp = msg.optString("hostVirtualIp"),
            )
            "host_changed" -> listener.onHostChanged(
                newHostPeerId = msg.optString("hostPeerId"),
                hostToken = null,
                hostVirtualIp = msg.optString("hostVirtualIp"),
            )
            "host_offline" -> listener.onHostOffline(msg.optString("hostPeerId"))
            "host_online" -> listener.onHostOnline(msg.optString("hostPeerId"))
            "join_rejected" -> listener.onRejected()
            "kicked" -> listener.onKicked()
            "room_closed" -> listener.onRoomClosed(msg.optString("reason", "closed"))
            "error" -> listener.onError(msg.optString("code", "error"), msg.optString("message", ""))
        }
    }

    private fun parsePeers(arr: JSONArray?): List<CollabPeer> {
        if (arr == null) return emptyList()
        val list = ArrayList<CollabPeer>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(parsePeerObj(it)) }
        }
        return list
    }

    private fun parsePeerObj(p: JSONObject): CollabPeer {
        val role = if (p.optString("role") == "host") CollabRole.HOST else CollabRole.PEER
        val peerId = p.getString("peerId")
        return CollabPeer(
            peerId = peerId,
            name = p.optString("name", "Artist"),
            role = role,
            joinedAt = p.optLong("joinedAt", 0L),
            virtualIp = p.optString("virtualIp"),
        )
    }

    private fun normalizeBaseUrl(raw: String): String =
        raw.trim().trimEnd('/')

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
