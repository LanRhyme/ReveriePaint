// SPDX-License-Identifier: GPL-3.0-or-later

import { DurableObject } from 'cloudflare:workers';
import {
  DEFAULT_MAX_PEERS,
  HttpError,
  MAX_ROOM_CONNECTIONS,
  MAX_SIGNAL_BYTES,
  PEER_ID_RE,
  buildIceServers,
  jsonResponse,
  parsePositiveInt,
  randomTokenHex,
  sanitizeName,
  sanitizeTitle,
  sha256Hex,
  timingSafeEqualStr,
} from './common.js';

const STORAGE_ROOM_KEY = 'room_meta';
const HOST_CLAIM_GRACE_MS = 5000;

export class CollabRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // Hibernation 下由边缘直接响应文本 "ping" -> "pong", 零计算耗时与请求配额消耗
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  async fetch(request) {
    const url = new URL(request.url);
    try {
      if (url.pathname === '/init' && request.method === 'POST') {
        return await this.handleInit(request);
      }
      if (url.pathname === '/info' && request.method === 'GET') {
        return await this.handleInfo();
      }
      if (url.pathname === '/ws' && request.method === 'GET') {
        return await this.handleWebSocket(request, url);
      }
      return jsonResponse({ ok: false, error: 'not_found' }, 404);
    } catch (err) {
      if (err instanceof HttpError) {
        return jsonResponse({ ok: false, error: err.code, message: err.message }, err.status);
      }
      return jsonResponse({ ok: false, error: 'internal' }, 500);
    }
  }

  async handleInit(request) {
    const body = await request.json().catch(() => null);
    if (!body || typeof body !== 'object') {
      throw new HttpError(400, 'bad_json', 'invalid JSON body');
    }

    const roomCode = String(body.roomCode || '');
    const hostPeerId = String(body.hostPeerId || '');
    if (!PEER_ID_RE.test(hostPeerId)) {
      throw new HttpError(400, 'invalid_peer_id', 'invalid hostPeerId');
    }

    const now = Date.now();
    const existing = await this.ctx.storage.get(STORAGE_ROOM_KEY);
    if (existing && now < existing.expiresAt && this.ctx.getWebSockets().length > 0) {
      throw new HttpError(409, 'room_exists', 'room code already active');
    }

    const roomTtlMs = parsePositiveInt(this.env.ROOM_TTL_MS, 86400000, 60000, 7 * 86400000);
    const idleTtlMs = parsePositiveInt(this.env.IDLE_TTL_MS, 1800000, 60000, roomTtlMs);
    const maxPeers = parsePositiveInt(body.maxPeers, DEFAULT_MAX_PEERS, 2, MAX_ROOM_CONNECTIONS);
    const requireApproval = Boolean(body.requireApproval);
    const isPublic = Boolean(body.isPublic);
    const title = sanitizeTitle(body.title);
    if (isPublic && !title) {
      throw new HttpError(400, 'room_title_required', 'public room requires a title');
    }
    const password = typeof body.password === 'string' ? body.password : '';
    const passwordHash = password.length > 0 ? await sha256Hex(password) : '';
    const hostToken = randomTokenHex(24);
    const hostName = sanitizeName(body.hostName, 'Host');
    const canvasMeta = body.canvasMeta && typeof body.canvasMeta === 'object' ? body.canvasMeta : null;

    const meta = {
      roomCode,
      hostPeerId,
      hostName,
      hostToken,
      passwordHash,
      requireApproval,
      maxPeers,
      isPublic,
      title: isPublic ? title : '',
      canvasMeta,
      admittedPeers: {
        [hostPeerId]: { name: hostName, joinedAt: now, disconnectedAt: 0 },
      },
      kickedPeers: [],
      createdAt: now,
      lastActiveAt: now,
      expiresAt: now + roomTtlMs,
      hostDisconnectedAt: 0,
    };

    await this.ctx.storage.put(STORAGE_ROOM_KEY, meta);
    await this.ctx.storage.setAlarm(now + idleTtlMs);
    if (isPublic) {
      await this.syncDirectory(meta, {
        peerCountOverride: 1,
        expiresAtOverride: Math.min(meta.expiresAt, now + 60_000),
      });
    } else if (existing?.isPublic) {
      await this.syncDirectory({ roomCode }, { forceRemove: true });
    }

    const iceServers = await buildIceServers(this.env, hostPeerId);
    return jsonResponse({
      ok: true,
      roomCode,
      hostPeerId,
      hostToken,
      requireApproval,
      hasPassword: passwordHash.length > 0,
      maxPeers,
      isPublic,
      title: meta.title,
      expiresInMs: roomTtlMs,
      iceServers,
    });
  }

  async handleInfo() {
    const meta = await this.loadActiveRoom();
    if (!meta) {
      throw new HttpError(404, 'room_not_found', 'room not found or expired');
    }
    const peers = this.listAdmittedPeers();
    return jsonResponse({
      ok: true,
      roomCode: meta.roomCode,
      hostPeerId: meta.hostPeerId,
      hostName: meta.hostName,
      requireApproval: meta.requireApproval,
      hasPassword: meta.passwordHash.length > 0,
      maxPeers: meta.maxPeers,
      isPublic: Boolean(meta.isPublic),
      title: meta.title || '',
      peerCount: peers.length,
      peerNames: peers.map((p) => p.name),
      canvasMeta: meta.canvasMeta,
      expiresAt: meta.expiresAt,
    });
  }

  async handleWebSocket(request, url) {
    if ((request.headers.get('upgrade') || '').toLowerCase() !== 'websocket') {
      throw new HttpError(426, 'upgrade_required', 'expected WebSocket upgrade');
    }

    const meta = await this.loadActiveRoom();
    if (!meta) {
      throw new HttpError(404, 'room_not_found', 'room not found or expired');
    }

    const peerId = String(url.searchParams.get('peerId') || '');
    if (!PEER_ID_RE.test(peerId)) {
      throw new HttpError(400, 'invalid_peer_id', 'invalid peerId');
    }

    const roleParam = url.searchParams.get('role') || 'peer';
    if (roleParam !== 'host' && roleParam !== 'peer') {
      throw new HttpError(400, 'invalid_role', 'role must be host or peer');
    }

    const token = String(url.searchParams.get('token') || '');
    const isHost = roleParam === 'host';
    if (isHost) {
      if (peerId !== meta.hostPeerId || !timingSafeEqualStr(token, meta.hostToken)) {
        throw new HttpError(403, 'invalid_host_token', 'host authentication failed');
      }
    } else if (peerId === meta.hostPeerId) {
      throw new HttpError(409, 'peer_id_conflict', 'peerId conflicts with host');
    }

    if (!isHost && meta.passwordHash.length > 0) {
      const providedPw = String(url.searchParams.get('pw') || '');
      const providedHash = providedPw.length > 0 ? await sha256Hex(providedPw) : '';
      if (!timingSafeEqualStr(providedHash, meta.passwordHash)) {
        throw new HttpError(403, 'invalid_password', 'wrong room password');
      }
    }

    if (!isHost && Array.isArray(meta.kickedPeers) && meta.kickedPeers.includes(peerId)) {
      throw new HttpError(403, 'kicked', 'removed from room by host');
    }

    const existingForPeer = this.ctx.getWebSockets(peerId);
    const activeSockets = this.ctx.getWebSockets();
    if (existingForPeer.length === 0 && activeSockets.length >= MAX_ROOM_CONNECTIONS) {
      throw new HttpError(429, 'room_full', 'room connection limit reached');
    }

    const admittedPeers = this.listAdmittedPeers();
    const prevAdmitted = meta.admittedPeers && meta.admittedPeers[peerId];
    const alreadyAdmitted = Boolean(prevAdmitted) ||
      existingForPeer.some((s) => s.deserializeAttachment()?.status === 'admitted');
    if (!isHost && !alreadyAdmitted && admittedPeers.length >= meta.maxPeers) {
      throw new HttpError(409, 'room_full', 'room reached maxPeers');
    }

    const name = sanitizeName(url.searchParams.get('name'), isHost ? meta.hostName : 'Artist');
    if (!isHost && this.isNicknameTaken(meta, name, peerId)) {
      throw new HttpError(409, 'duplicate_name', 'nickname already taken in room');
    }

    // 若同 peerId 重连, 标记旧连接为 replaced 避免延迟 close 误发断线广播
    for (const oldWs of existingForPeer) {
      const att = oldWs.deserializeAttachment() || {};
      oldWs.serializeAttachment({ ...att, replaced: true });
      try {
        oldWs.close(4001, 'replaced');
      } catch {}
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const now = Date.now();
    const status = isHost || !meta.requireApproval || alreadyAdmitted ? 'admitted' : 'pending';
    const joinedAt = prevAdmitted?.joinedAt || now;

    this.ctx.acceptWebSocket(server, [peerId]);
    server.serializeAttachment({
      peerId,
      role: isHost ? 'host' : 'peer',
      name,
      status,
      joinedAt,
      replaced: false,
    });

    meta.lastActiveAt = now;
    if (!meta.admittedPeers || typeof meta.admittedPeers !== 'object') {
      meta.admittedPeers = {};
    }
    if (status === 'admitted') {
      meta.admittedPeers[peerId] = { name, joinedAt, disconnectedAt: 0 };
    }
    if (isHost) {
      meta.hostDisconnectedAt = 0;
    }
    await this.ctx.storage.put(STORAGE_ROOM_KEY, meta);
    await this.scheduleIdleAlarm(meta);
    if (status === 'admitted' && meta.isPublic) {
      await this.syncDirectory(meta);
    }

    const iceServers = await buildIceServers(this.env, peerId);
    if (status === 'admitted') {
      const currentAdmitted = this.listAdmittedPeers();
      this.sendJson(server, {
        type: 'welcome',
        roomCode: meta.roomCode,
        selfPeerId: peerId,
        hostPeerId: meta.hostPeerId,
        status: 'admitted',
        iceServers,
        peers: currentAdmitted,
        canvasMeta: meta.canvasMeta,
      });

      if (!isHost) {
        this.broadcastAdmitted(
          { type: 'peer_joined', peer: { peerId, role: 'peer', name, joinedAt } },
          peerId,
        );
      } else {
        // 房主上线/重连时把待审批列表与在线成员状态推给房主
        this.broadcastAdmitted({ type: 'host_online', hostPeerId: meta.hostPeerId }, peerId);
        for (const ws of this.ctx.getWebSockets()) {
          const att = ws.deserializeAttachment();
          if (att && att.status === 'pending') {
            this.sendJson(server, {
              type: 'join_request',
              peer: { peerId: att.peerId, name: att.name, joinedAt: att.joinedAt },
            });
          }
        }
      }
    } else {
      this.sendJson(server, {
        type: 'welcome',
        roomCode: meta.roomCode,
        selfPeerId: peerId,
        hostPeerId: meta.hostPeerId,
        status: 'pending',
        iceServers,
      });
      this.sendToPeer(meta.hostPeerId, {
        type: 'join_request',
        peer: { peerId, name, joinedAt: now },
      });
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== 'string') {
      this.sendJson(ws, { type: 'error', code: 'text_only' });
      return;
    }
    if (new TextEncoder().encode(message).byteLength > MAX_SIGNAL_BYTES) {
      this.sendJson(ws, { type: 'error', code: 'payload_too_large' });
      return;
    }

    let msg;
    try {
      msg = JSON.parse(message);
    } catch {
      this.sendJson(ws, { type: 'error', code: 'bad_json' });
      return;
    }
    if (!msg || typeof msg !== 'object' || typeof msg.type !== 'string') {
      this.sendJson(ws, { type: 'error', code: 'bad_message' });
      return;
    }

    const sender = ws.deserializeAttachment();
    if (!sender || !sender.peerId) {
      ws.close(4002, 'missing_session');
      return;
    }

    const meta = await this.loadActiveRoom();
    if (!meta) {
      this.sendJson(ws, { type: 'room_closed', reason: 'expired' });
      ws.close(4004, 'expired');
      return;
    }

    switch (msg.type) {
      case 'signal': {
        if (sender.status !== 'admitted') {
          this.sendJson(ws, { type: 'error', code: 'not_admitted' });
          return;
        }
        const to = typeof msg.to === 'string' ? msg.to : '';
        if (!to || to === sender.peerId || msg.payload === undefined) {
          this.sendJson(ws, { type: 'error', code: 'invalid_signal_target' });
          return;
        }
        const delivered = this.sendToAdmittedPeer(to, {
          type: 'signal',
          from: sender.peerId,
          payload: msg.payload,
        });
        if (!delivered) {
          this.sendJson(ws, { type: 'signal_undelivered', to });
        }
        break;
      }

      case 'approve':
      case 'reject': {
        if (sender.peerId !== meta.hostPeerId) {
          this.sendJson(ws, { type: 'error', code: 'host_only' });
          return;
        }
        const targetId = typeof msg.targetPeerId === 'string' ? msg.targetPeerId : '';
        const targetWs = this.findSocketByPeerId(targetId);
        if (!targetWs) return;
        const targetAtt = targetWs.deserializeAttachment();
        if (!targetAtt || targetAtt.status !== 'pending') return;

        if (msg.type === 'approve') {
          targetWs.serializeAttachment({ ...targetAtt, status: 'admitted' });
          if (!meta.admittedPeers || typeof meta.admittedPeers !== 'object') {
            meta.admittedPeers = {};
          }
          meta.admittedPeers[targetAtt.peerId] = {
            name: targetAtt.name,
            joinedAt: targetAtt.joinedAt,
            disconnectedAt: 0,
          };
          const iceServers = await buildIceServers(this.env, targetAtt.peerId);
          this.sendJson(targetWs, {
            type: 'admitted',
            roomCode: meta.roomCode,
            selfPeerId: targetAtt.peerId,
            hostPeerId: meta.hostPeerId,
            iceServers,
            peers: this.listAdmittedPeers(),
            canvasMeta: meta.canvasMeta,
          });
          this.broadcastAdmitted(
            {
              type: 'peer_joined',
              peer: {
                peerId: targetAtt.peerId,
                role: 'peer',
                name: targetAtt.name,
                joinedAt: targetAtt.joinedAt,
              },
            },
            targetAtt.peerId,
          );
          if (meta.isPublic) {
            await this.syncDirectory(meta);
          }
        } else {
          targetWs.serializeAttachment({ ...targetAtt, replaced: true });
          this.sendJson(targetWs, { type: 'join_rejected' });
          try {
            targetWs.close(4003, 'rejected');
          } catch {}
        }
        break;
      }

      case 'kick': {
        if (sender.peerId !== meta.hostPeerId) {
          this.sendJson(ws, { type: 'error', code: 'host_only' });
          return;
        }
        const targetId = typeof msg.targetPeerId === 'string' ? msg.targetPeerId : '';
        if (!targetId || targetId === meta.hostPeerId) return;
        if (meta.admittedPeers && meta.admittedPeers[targetId]) {
          delete meta.admittedPeers[targetId];
        }
        if (!Array.isArray(meta.kickedPeers)) meta.kickedPeers = [];
        if (!meta.kickedPeers.includes(targetId)) {
          meta.kickedPeers.push(targetId);
        }
        const targetWs = this.findSocketByPeerId(targetId);
        if (!targetWs) return;
        const targetAtt = targetWs.deserializeAttachment();
        if (targetAtt) {
          targetWs.serializeAttachment({ ...targetAtt, replaced: true });
          if (targetAtt.status === 'admitted') {
            this.broadcastAdmitted({ type: 'peer_left', peerId: targetId, reason: 'kicked' }, targetId);
          }
        }
        this.sendJson(targetWs, { type: 'kicked' });
        try {
          targetWs.close(4005, 'kicked');
        } catch {}
        if (meta.isPublic) {
          await this.syncDirectory(meta);
        }
        break;
      }

      case 'handoff_host': {
        if (sender.peerId !== meta.hostPeerId) {
          this.sendJson(ws, { type: 'error', code: 'host_only' });
          return;
        }
        const newHostId = typeof msg.newHostPeerId === 'string' ? msg.newHostPeerId : '';
        const newHostWs = this.findAdmittedSocket(newHostId);
        if (!newHostWs || newHostId === meta.hostPeerId) {
          this.sendJson(ws, { type: 'error', code: 'invalid_new_host' });
          return;
        }
        await this.promoteHost(meta, ws, newHostWs);
        break;
      }

      case 'claim_host': {
        if (sender.status !== 'admitted') {
          this.sendJson(ws, { type: 'error', code: 'not_admitted' });
          return;
        }
        const hostOnline = Boolean(this.findAdmittedSocket(meta.hostPeerId));
        if (hostOnline) {
          this.sendJson(ws, { type: 'error', code: 'host_still_online' });
          return;
        }
        const now = Date.now();
        if (meta.hostDisconnectedAt > 0 && now - meta.hostDisconnectedAt < HOST_CLAIM_GRACE_MS) {
          this.sendJson(ws, { type: 'error', code: 'host_grace_period' });
          return;
        }
        // 只允许在线且入房最早的非房主成员接管, 避免并发抢主分叉
        const candidate = this.earliestAdmittedPeer(meta.hostPeerId);
        if (!candidate || candidate.peerId !== sender.peerId) {
          this.sendJson(ws, { type: 'error', code: 'not_next_in_line' });
          return;
        }
        await this.promoteHost(meta, null, ws);
        break;
      }

      case 'leave': {
        if (meta.admittedPeers && meta.admittedPeers[sender.peerId]) {
          delete meta.admittedPeers[sender.peerId];
        }
        ws.serializeAttachment({ ...sender, replaced: true });
        if (sender.status === 'admitted') {
          this.broadcastAdmitted(
            { type: 'peer_left', peerId: sender.peerId, reason: 'left' },
            sender.peerId,
          );
        }
        meta.lastActiveAt = Date.now();
        await this.ctx.storage.put(STORAGE_ROOM_KEY, meta);
        if (meta.isPublic) {
          await this.syncDirectory(meta, { excludeWs: ws });
        }
        try {
          ws.close(1000, 'left');
        } catch {}
        return;
      }

      case 'close_room': {
        if (sender.peerId !== meta.hostPeerId) {
          this.sendJson(ws, { type: 'error', code: 'host_only' });
          return;
        }
        this.broadcastAll({ type: 'room_closed', reason: 'host_closed' });
        for (const sock of this.ctx.getWebSockets()) {
          const att = sock.deserializeAttachment();
          if (att) sock.serializeAttachment({ ...att, replaced: true });
          try {
            sock.close(4000, 'room_closed');
          } catch {}
        }
        if (meta.isPublic) {
          await this.syncDirectory(meta, { forceRemove: true });
        }
        await this.ctx.storage.deleteAll();
        return;
      }

      default:
        this.sendJson(ws, { type: 'error', code: 'unknown_type' });
        return;
    }

    meta.lastActiveAt = Date.now();
    await this.ctx.storage.put(STORAGE_ROOM_KEY, meta);
  }

  async webSocketClose(ws) {
    await this.handleSocketDisconnect(ws);
  }

  async webSocketError(ws) {
    await this.handleSocketDisconnect(ws);
  }

  async handleSocketDisconnect(ws) {
    const att = ws.deserializeAttachment();
    if (!att || att.replaced) return;

    const meta = await this.ctx.storage.get(STORAGE_ROOM_KEY);
    if (!meta) return;

    const now = Date.now();
    if (att.peerId === meta.hostPeerId) {
      meta.hostDisconnectedAt = now;
      this.broadcastAdmitted({ type: 'host_offline', hostPeerId: meta.hostPeerId }, att.peerId);
    } else if (att.status === 'admitted') {
      if (meta.admittedPeers && meta.admittedPeers[att.peerId]) {
        meta.admittedPeers[att.peerId].disconnectedAt = now;
      }
      // 仅广播 peer_offline 通知信令暂断, 保留已建立的 P2P 通道等待几秒内无感重连
      this.broadcastAdmitted({ type: 'peer_offline', peerId: att.peerId }, att.peerId);
    }

    meta.lastActiveAt = now;
    await this.ctx.storage.put(STORAGE_ROOM_KEY, meta);
    await this.scheduleIdleAlarm(meta);
    if (att.status === 'admitted' && meta.isPublic) {
      await this.syncDirectory(meta, { excludeWs: ws });
    }
  }

  async alarm() {
    const meta = await this.ctx.storage.get(STORAGE_ROOM_KEY);
    if (!meta) return;

    const now = Date.now();
    const activeCount = this.ctx.getWebSockets().length;
    const idleTtlMs = parsePositiveInt(this.env.IDLE_TTL_MS, 1800000, 60000, 7 * 86400000);
    const expired = now >= meta.expiresAt;
    const idleExpired = activeCount === 0 && now - meta.lastActiveAt >= idleTtlMs;

    if (expired || idleExpired) {
      for (const sock of this.ctx.getWebSockets()) {
        try {
          sock.close(4004, 'expired');
        } catch {}
      }
      if (meta.isPublic) {
        await this.syncDirectory(meta, { forceRemove: true });
      }
      await this.ctx.storage.deleteAll();
      return;
    }

    await this.scheduleIdleAlarm(meta);
  }

  async promoteHost(meta, oldHostWs, newHostWs) {
    const newAtt = newHostWs.deserializeAttachment();
    const newToken = randomTokenHex(24);
    if (oldHostWs) {
      const oldAtt = oldHostWs.deserializeAttachment();
      if (oldAtt) {
        oldHostWs.serializeAttachment({ ...oldAtt, role: 'peer' });
      }
    }
    newHostWs.serializeAttachment({ ...newAtt, role: 'host' });

    meta.hostPeerId = newAtt.peerId;
    meta.hostName = newAtt.name;
    meta.hostToken = newToken;
    meta.hostDisconnectedAt = 0;
    meta.lastActiveAt = Date.now();
    await this.ctx.storage.put(STORAGE_ROOM_KEY, meta);
    if (meta.isPublic) {
      await this.syncDirectory(meta);
    }

    this.sendJson(newHostWs, {
      type: 'host_promoted',
      hostPeerId: meta.hostPeerId,
      hostToken: newToken,
    });
    this.broadcastAdmitted(
      {
        type: 'host_changed',
        hostPeerId: meta.hostPeerId,
        hostName: meta.hostName,
      },
      newAtt.peerId,
    );
  }

  async loadActiveRoom() {
    const meta = await this.ctx.storage.get(STORAGE_ROOM_KEY);
    if (!meta) return null;
    if (Date.now() >= meta.expiresAt) {
      if (meta.isPublic) {
        await this.syncDirectory(meta, { forceRemove: true });
      }
      await this.ctx.storage.deleteAll();
      return null;
    }
    return meta;
  }

  async scheduleIdleAlarm(meta) {
    const idleTtlMs = parsePositiveInt(this.env.IDLE_TTL_MS, 1800000, 60000, 7 * 86400000);
    const target = Math.min(meta.expiresAt, Date.now() + idleTtlMs);
    await this.ctx.storage.setAlarm(target);
  }

  async syncDirectory(
    meta,
    { forceRemove = false, peerCountOverride = null, expiresAtOverride = null, excludeWs = null } = {},
  ) {
    if (!this.env.DIRECTORY || !meta?.roomCode) return;
    try {
      const stub = this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName('global'));
      if (forceRemove || !meta.isPublic || !meta.title) {
        await stub.fetch('https://directory/remove', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ roomCode: meta.roomCode }),
        });
        return;
      }
      const peerCount =
        peerCountOverride !== null ? peerCountOverride : this.listAdmittedPeers(excludeWs).length;
      if (peerCount <= 0) {
        await stub.fetch('https://directory/remove', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ roomCode: meta.roomCode }),
        });
        return;
      }
      await stub.fetch('https://directory/upsert', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          roomCode: meta.roomCode,
          title: meta.title,
          hostName: meta.hostName,
          peerCount,
          maxPeers: meta.maxPeers,
          hasPassword: Boolean(meta.passwordHash),
          requireApproval: Boolean(meta.requireApproval),
          canvasWidth: Number(meta.canvasMeta?.width) || 0,
          canvasHeight: Number(meta.canvasMeta?.height) || 0,
          createdAt: meta.createdAt,
          updatedAt: Date.now(),
          expiresAt: expiresAtOverride || meta.expiresAt,
        }),
      });
    } catch {}
  }

  listAdmittedPeers(excludeWs = null) {
    const peers = [];
    for (const ws of this.ctx.getWebSockets()) {
      if (excludeWs && ws === excludeWs) continue;
      const att = ws.deserializeAttachment();
      if (att && !att.replaced && att.status === 'admitted') {
        peers.push({
          peerId: att.peerId,
          role: att.role,
          name: att.name,
          joinedAt: att.joinedAt,
        });
      }
    }
    peers.sort((a, b) => a.joinedAt - b.joinedAt);
    return peers;
  }

  earliestAdmittedPeer(excludePeerId) {
    const peers = this.listAdmittedPeers().filter((p) => p.peerId !== excludePeerId);
    return peers.length > 0 ? peers[0] : null;
  }

  isNicknameTaken(meta, candidateName, excludePeerId = '') {
    const norm = String(candidateName || '').trim().toLowerCase();
    if (!norm) return false;
    for (const ws of this.ctx.getWebSockets()) {
      const att = ws.deserializeAttachment();
      if (!att || att.replaced || att.peerId === excludePeerId) continue;
      if (String(att.name || '').trim().toLowerCase() === norm) {
        return true;
      }
    }
    const admitted = meta?.admittedPeers || {};
    for (const [pId, info] of Object.entries(admitted)) {
      if (pId === excludePeerId) continue;
      if (String(info?.name || '').trim().toLowerCase() === norm) {
        return true;
      }
    }
    return false;
  }

  findSocketByPeerId(peerId) {
    if (!peerId) return null;
    for (const ws of this.ctx.getWebSockets(peerId)) {
      const att = ws.deserializeAttachment();
      if (att && !att.replaced) return ws;
    }
    return null;
  }

  findAdmittedSocket(peerId) {
    const ws = this.findSocketByPeerId(peerId);
    if (!ws) return null;
    const att = ws.deserializeAttachment();
    return att && att.status === 'admitted' ? ws : null;
  }

  sendToPeer(peerId, payload) {
    const ws = this.findSocketByPeerId(peerId);
    if (!ws) return false;
    return this.sendJson(ws, payload);
  }

  sendToAdmittedPeer(peerId, payload) {
    const ws = this.findAdmittedSocket(peerId);
    if (!ws) return false;
    return this.sendJson(ws, payload);
  }

  broadcastAdmitted(payload, excludePeerId = '') {
    const raw = JSON.stringify(payload);
    for (const ws of this.ctx.getWebSockets()) {
      const att = ws.deserializeAttachment();
      if (!att || att.replaced || att.status !== 'admitted') continue;
      if (excludePeerId && att.peerId === excludePeerId) continue;
      try {
        ws.send(raw);
      } catch {}
    }
  }

  broadcastAll(payload) {
    const raw = JSON.stringify(payload);
    for (const ws of this.ctx.getWebSockets()) {
      const att = ws.deserializeAttachment();
      if (!att || att.replaced) continue;
      try {
        ws.send(raw);
      } catch {}
    }
  }

  sendJson(ws, payload) {
    try {
      ws.send(JSON.stringify(payload));
      return true;
    } catch {
      return false;
    }
  }
}
