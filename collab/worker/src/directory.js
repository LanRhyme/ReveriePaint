// SPDX-License-Identifier: GPL-3.0-or-later

import { DurableObject } from 'cloudflare:workers';
import {
  HttpError,
  ROOM_CODE_RE,
  jsonResponse,
  normalizeRoomCode,
  parsePositiveInt,
  sanitizeName,
  sanitizeTitle,
} from './common.js';

export class RoomDirectory extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS public_rooms (
        room_code TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        host_name TEXT NOT NULL,
        peer_count INTEGER NOT NULL,
        max_peers INTEGER NOT NULL,
        has_password INTEGER NOT NULL,
        require_approval INTEGER NOT NULL,
        canvas_width INTEGER NOT NULL,
        canvas_height INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL
      )
    `);
  }

  async fetch(request) {
    const url = new URL(request.url);
    try {
      if (url.pathname === '/upsert' && request.method === 'POST') {
        return await this.handleUpsert(request);
      }
      if (url.pathname === '/remove' && request.method === 'POST') {
        return await this.handleRemove(request);
      }
      if (url.pathname === '/list' && request.method === 'GET') {
        return await this.handleList(url);
      }
      return jsonResponse({ ok: false, error: 'not_found' }, 404);
    } catch (err) {
      if (err instanceof HttpError) {
        return jsonResponse({ ok: false, error: err.code, message: err.message }, err.status);
      }
      return jsonResponse({ ok: false, error: 'internal' }, 500);
    }
  }

  async handleUpsert(request) {
    const body = await request.json().catch(() => null);
    if (!body || typeof body !== 'object') {
      throw new HttpError(400, 'bad_json', 'invalid JSON body');
    }

    const roomCode = normalizeRoomCode(body.roomCode);
    const title = sanitizeTitle(body.title);
    if (!ROOM_CODE_RE.test(roomCode) || !title) {
      throw new HttpError(400, 'invalid_room', 'roomCode and title are required');
    }

    const now = Date.now();
    const hostName = sanitizeName(body.hostName, 'Host');
    const peerCount = parsePositiveInt(body.peerCount, 1, 1, 1024);
    const maxPeers = parsePositiveInt(body.maxPeers, 16, 2, 1024);
    const hasPassword = body.hasPassword ? 1 : 0;
    const requireApproval = body.requireApproval ? 1 : 0;
    const canvasWidth = parsePositiveInt(body.canvasWidth, 0, 0, 65535);
    const canvasHeight = parsePositiveInt(body.canvasHeight, 0, 0, 65535);
    const createdAt = parsePositiveInt(body.createdAt, now, 1, Number.MAX_SAFE_INTEGER);
    const updatedAt = parsePositiveInt(body.updatedAt, now, 1, Number.MAX_SAFE_INTEGER);
    const expiresAt = parsePositiveInt(body.expiresAt, now + 60000, now, Number.MAX_SAFE_INTEGER);

    this.ctx.storage.sql.exec('DELETE FROM public_rooms WHERE expires_at <= ?', now);
    this.ctx.storage.sql.exec(
      `INSERT OR REPLACE INTO public_rooms (
        room_code, title, host_name, peer_count, max_peers,
        has_password, require_approval, canvas_width, canvas_height,
        created_at, updated_at, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      roomCode,
      title,
      hostName,
      peerCount,
      maxPeers,
      hasPassword,
      requireApproval,
      canvasWidth,
      canvasHeight,
      createdAt,
      updatedAt,
      expiresAt,
    );

    return jsonResponse({ ok: true });
  }

  async handleRemove(request) {
    const body = await request.json().catch(() => null);
    const roomCode = normalizeRoomCode(body?.roomCode);
    if (ROOM_CODE_RE.test(roomCode)) {
      this.ctx.storage.sql.exec('DELETE FROM public_rooms WHERE room_code = ?', roomCode);
    }
    return jsonResponse({ ok: true });
  }

  async handleList(url) {
    const now = Date.now();
    const limit = parsePositiveInt(url.searchParams.get('limit'), 50, 1, 100);
    this.ctx.storage.sql.exec('DELETE FROM public_rooms WHERE expires_at <= ?', now);

    const rows = this.ctx.storage.sql
      .exec(
        `SELECT room_code, title, host_name, peer_count, max_peers,
                has_password, require_approval, canvas_width, canvas_height,
                created_at, updated_at
         FROM public_rooms
         WHERE expires_at > ? AND peer_count > 0
         ORDER BY updated_at DESC
         LIMIT ?`,
        now,
        limit,
      )
      .toArray();

    const rooms = rows.map((r) => ({
      roomCode: r.room_code,
      title: r.title,
      hostName: r.host_name,
      peerCount: Number(r.peer_count) || 0,
      maxPeers: Number(r.max_peers) || 16,
      hasPassword: Boolean(r.has_password),
      requireApproval: Boolean(r.require_approval),
      canvasWidth: Number(r.canvas_width) || 0,
      canvasHeight: Number(r.canvas_height) || 0,
      createdAt: Number(r.created_at) || 0,
      updatedAt: Number(r.updated_at) || 0,
    }));

    return jsonResponse({ ok: true, rooms });
  }
}
