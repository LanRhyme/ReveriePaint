// SPDX-License-Identifier: GPL-3.0-or-later

import {
  CONTRACT_VERSION,
  HttpError,
  ROOM_CODE_RE,
  buildIceServers,
  generateRoomCode,
  isTurnConfigured,
  jsonResponse,
  normalizeRoomCode,
} from './common.js';

export { CollabRoom } from './room.js';

function isDisabled(env) {
  return String(env?.DISABLED || '0') === '1';
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-methods': 'GET, POST, OPTIONS',
          'access-control-allow-headers': 'content-type',
        },
      });
    }

    try {
      if (path === '/c/v1/health' && request.method === 'GET') {
        return jsonResponse({
          ok: true,
          version: CONTRACT_VERSION,
          disabled: isDisabled(env),
          turnConfigured: isTurnConfigured(env),
        });
      }

      if (isDisabled(env)) {
        return jsonResponse({ ok: false, error: 'service_disabled' }, 410);
      }

      if (path === '/c/v1/ice' && request.method === 'GET') {
        const peerId = url.searchParams.get('peerId') || '';
        const iceServers = await buildIceServers(env, peerId);
        return jsonResponse({ ok: true, iceServers });
      }

      if (path === '/c/v1/rooms' && request.method === 'POST') {
        return await handleCreateRoom(request, env);
      }

      const infoMatch = path.match(/^\/c\/v1\/rooms\/([^/]+)\/info$/);
      if (infoMatch && request.method === 'GET') {
        const code = normalizeRoomCode(infoMatch[1]);
        if (!ROOM_CODE_RE.test(code)) {
          throw new HttpError(400, 'invalid_room_code', 'room code must be 6 chars');
        }
        const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
        return await stub.fetch('https://room/info', { method: 'GET' });
      }

      const wsMatch = path.match(/^\/c\/v1\/rooms\/([^/]+)\/ws$/);
      if (wsMatch && request.method === 'GET') {
        const code = normalizeRoomCode(wsMatch[1]);
        if (!ROOM_CODE_RE.test(code)) {
          throw new HttpError(400, 'invalid_room_code', 'room code must be 6 chars');
        }
        const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
        const forwardUrl = new URL('https://room/ws');
        forwardUrl.search = url.search;
        return await stub.fetch(new Request(forwardUrl.toString(), request));
      }

      return jsonResponse({ ok: false, error: 'not_found' }, 404);
    } catch (err) {
      if (err instanceof HttpError) {
        return jsonResponse({ ok: false, error: err.code, message: err.message }, err.status);
      }
      return jsonResponse({ ok: false, error: 'internal' }, 500);
    }
  },
};

async function handleCreateRoom(request, env) {
  const rawBody = await request.text();
  if (rawBody.length > 16 * 1024) {
    throw new HttpError(413, 'body_too_large', 'request body too large');
  }

  let parsed;
  try {
    parsed = JSON.parse(rawBody || '{}');
  } catch {
    throw new HttpError(400, 'bad_json', 'invalid JSON body');
  }

  for (let attempt = 0; attempt < 8; attempt += 1) {
    const roomCode = generateRoomCode();
    const stub = env.ROOMS.get(env.ROOMS.idFromName(roomCode));
    const resp = await stub.fetch('https://room/init', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ...parsed, roomCode }),
    });
    if (resp.status !== 409) {
      return resp;
    }
  }

  throw new HttpError(503, 'room_code_exhausted', 'failed to allocate room code');
}
