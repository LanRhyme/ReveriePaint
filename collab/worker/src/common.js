// SPDX-License-Identifier: GPL-3.0-or-later

export const CONTRACT_VERSION = 1;
export const MAX_SIGNAL_BYTES = 64 * 1024;
export const MAX_ROOM_CONNECTIONS = 64;
export const DEFAULT_MAX_PEERS = 16;
export const MAX_ROOM_TITLE_LEN = 32;
export const ROOM_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const ROOM_CODE_LEN = 6;
export const ROOM_CODE_RE = /^[A-Z2-9]{6}$/;
export const PEER_ID_RE = /^[A-Za-z0-9_-]{4,64}$/;

export class HttpError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.status = status;
    this.code = code;
  }
}

export function jsonResponse(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'access-control-allow-origin': '*',
      ...extraHeaders,
    },
  });
}

export function randomTokenHex(bytes = 24) {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return Array.from(buf, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function generateRoomCode() {
  const buf = new Uint8Array(ROOM_CODE_LEN);
  crypto.getRandomValues(buf);
  let out = '';
  for (let i = 0; i < ROOM_CODE_LEN; i += 1) {
    out += ROOM_CODE_ALPHABET[buf[i] % ROOM_CODE_ALPHABET.length];
  }
  return out;
}

export function normalizeRoomCode(raw) {
  if (typeof raw !== 'string') return '';
  return raw.trim().toUpperCase();
}

export function sanitizeName(raw, fallback = 'Guest') {
  if (typeof raw !== 'string') return fallback;
  const trimmed = raw.trim().slice(0, 32);
  return trimmed.length > 0 ? trimmed : fallback;
}

export function sanitizeTitle(raw) {
  if (typeof raw !== 'string') return '';
  return raw.replace(/[\r\n\t]+/g, ' ').trim().slice(0, MAX_ROOM_TITLE_LEN);
}

export function timingSafeEqualStr(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

export async function sha256Hex(text) {
  const data = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

export async function buildIceServers(env, peerId = '') {
  const servers = [];
  const rawStun = String(env?.STUN_URLS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  for (const url of rawStun) {
    servers.push({ urls: url });
  }

  // 预留可选 TURN 注入 (如自建 coturn HMAC 或静态配置, 未绑卡时默认不配置)
  const rawTurn = String(env?.TURN_URLS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (rawTurn.length > 0) {
    if (env.TURN_SHARED_SECRET) {
      const ttlSec = 12 * 3600;
      const expiry = Math.floor(Date.now() / 1000) + ttlSec;
      const username = `${expiry}:${peerId || 'rp'}`;
      const key = await crypto.subtle.importKey(
        'raw',
        new TextEncoder().encode(env.TURN_SHARED_SECRET),
        { name: 'HMAC', hash: 'SHA-1' },
        false,
        ['sign'],
      );
      const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(username));
      const credential = btoa(String.fromCharCode(...new Uint8Array(sig)));
      servers.push({ urls: rawTurn, username, credential });
    } else if (env.TURN_USERNAME && env.TURN_CREDENTIAL) {
      servers.push({
        urls: rawTurn,
        username: String(env.TURN_USERNAME),
        credential: String(env.TURN_CREDENTIAL),
      });
    }
  }
  return servers;
}

export function isTurnConfigured(env) {
  return Boolean(
    String(env?.TURN_URLS || '').trim() &&
      (env?.TURN_SHARED_SECRET || (env?.TURN_USERNAME && env?.TURN_CREDENTIAL)),
  );
}

export const DEFAULT_STUN_SERVERS = [
  'stun.hitv.com',
  'stun.miwifi.com',
  'stun.chat.bilibili.com',
  'txt:stun.easytier.cn',
];

export const SAVE_POLICIES = ['all', 'own_only'];

export function sanitizeSavePolicy(raw) {
  return raw === 'own_only' ? 'own_only' : 'all';
}

export const DEFAULT_STUN_V6_SERVERS = ['txt:stun-v6.easytier.cn'];

export function parseCsvList(raw) {
  return String(raw || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function parseStunServers(env) {
  const configured = parseCsvList(env?.STUN_URLS).map((u) => u.replace(/^stun:/i, ''));
  return configured.length > 0 ? configured : [...DEFAULT_STUN_SERVERS];
}

export function parseStunV6Servers(env) {
  const configured = parseCsvList(env?.STUN_V6_URLS);
  return configured.length > 0 ? configured : [...DEFAULT_STUN_V6_SERVERS];
}

export function parseRelayPeers(env) {
  return parseCsvList(env?.RELAY_PEERS);
}

export function deriveMeshSubnetByte(roomCode) {
  let h = 2166136261;
  for (let i = 0; i < roomCode.length; i += 1) {
    h ^= roomCode.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return 10 + (h % 240);
}

export function deriveMeshPort(roomCode) {
  let h = 2166136261;
  for (let i = 0; i < roomCode.length; i += 1) {
    h ^= roomCode.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return 39000 + (h % 10000);
}

export function isRelayConfigured(env) {
  return parseRelayPeers(env).length > 0;
}

export function parsePositiveInt(raw, fallback, min, max) {
  const n = Number(raw);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(n)));
}
