// SPDX-License-Identifier: GPL-3.0-or-later
// 茶绘信令端端到端测试夹具: 拉起本地 wrangler dev, 提供建房/连 WS/等待消息等原语

import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const WORKER_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 启动本地 worker, 轮询 /health 直到就绪 */
function resolveWranglerCommand() {
  // 优先用本地安装的 wrangler (CI 里先 npm install), 缺失时回退 npx
  const localBin = resolve(WORKER_DIR, 'node_modules', '.bin', 'wrangler');
  if (existsSync(localBin)) return { cmd: localBin, prefix: [] };
  return { cmd: 'npx', prefix: ['-y', 'wrangler@4'] };
}

export async function startWorker({ port, vars = {}, timeoutMs = 180000 } = {}) {
  const { cmd, prefix } = resolveWranglerCommand();
  // 每个实例独立 persist 目录: 同一项目下并行跑两个 wrangler dev 会争用 workerd 的本地 socket,
  // CI 上表现为 "Address already in use (socket direct:1:default)"
  const stateDir = resolve(WORKER_DIR, '.wrangler', 'test-state', String(port));
  const args = [...prefix, 'dev', '--ip', '127.0.0.1', '--port', String(port), '--local', '--persist-to', stateDir];
  for (const [key, value] of Object.entries(vars)) args.push('--var', `${key}:${value}`);
  const child = spawn(cmd, args, {
    cwd: WORKER_DIR,
    env: { ...process.env, WRANGLER_SEND_METRICS: 'false', CI: '1' },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
  });
  let log = '';
  const echo = Boolean(process.env.COLLAB_TEST_VERBOSE) || Boolean(process.env.CI);
  const absorb = (d) => {
    const text = String(d);
    log += text;
    if (echo) {
      for (const line of text.split('\n')) {
        if (line.trim()) console.log(`[wrangler:${port}] ${line.trimEnd()}`);
      }
    }
  };
  child.stdout.on('data', absorb);
  child.stderr.on('data', absorb);

  const base = `http://127.0.0.1:${port}`;
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let lastHeartbeat = 0;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      await stopWorker({ child });
      throw new Error(`wrangler exited early:\n${log.slice(-4000)}`);
    }
    try {
      const res = await fetch(`${base}/c/v1/health`, { signal: AbortSignal.timeout(2000) });
      const body = await res.json().catch(() => null);
      if (res.ok && body?.ok) {
        return {
          base,
          wsBase: `ws://127.0.0.1:${port}`,
          port,
          child,
          log: () => log,
        };
      }
    } catch {
      /* 服务还没起来 */
    }
    const elapsed = Date.now() - startedAt;
    if (elapsed - lastHeartbeat >= 5000) {
      lastHeartbeat = elapsed;
      console.log(`[wrangler:${port}] 等待就绪 ${Math.round(elapsed / 1000)}s ...`);
    }
    await sleep(400);
  }
  await stopWorker({ child });
  throw new Error(`wrangler 未在 ${timeoutMs}ms 内就绪:\n${log.slice(-4000)}`);
}

/** 结束 worker (npx 会派生 workerd, 需要杀进程组) */
export async function stopWorker(worker) {
  if (!worker?.child) return;
  const { child } = worker;
  if (child.exitCode !== null) return;
  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch {
    child.kill('SIGTERM');
  }
  await Promise.race([once(child, 'exit'), sleep(8000)]);
  if (child.exitCode === null) {
    try {
      process.kill(-child.pid, 'SIGKILL');
    } catch {
      /* 已退出 */
    }
  }
}

/** 单个信令连接: 收消息队列 + 关闭状态 */
class PeerSocket {
  constructor({ ws, peerId, inbox, state }) {
    this.ws = ws;
    this.peerId = peerId;
    this.inbox = inbox;
    this.state = state;
  }

  send(payload) {
    this.ws.send(JSON.stringify(payload));
  }

  /** 等待匹配消息, 超时返回 null */
  async waitFor(predicate, timeoutMs = 5000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const hit = this.inbox.find(predicate);
      if (hit) return hit;
      await sleep(40);
    }
    return null;
  }

  /** 清空已收消息, 便于断言"接下来"的事件 */
  clear() {
    this.inbox.length = 0;
  }

  types() {
    return this.inbox.map((m) => m.type);
  }

  /**
   * 等待连接关闭事件。
   * 注意: Node 内置 WebSocket 收到对端 close 帧后可能延后触发 close 事件
   * (本地 workerd 下从数百毫秒到数十秒不等), 断言"服务端已发起关闭"时优先用 waitClosing。
   */
  async waitClosed(timeoutMs = 15000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.state.closed) return this.state;
      await sleep(40);
    }
    return null;
  }

  /** 等待连接进入 CLOSING/CLOSED (对端已发 close 帧), 不依赖 close 事件触发时机 */
  async waitClosing(timeoutMs = 6000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.state.closed || this.ws.readyState >= 2) return true;
      await sleep(40);
    }
    return false;
  }

  close(code = 1000, reason = 'test') {
    try {
      this.ws.close(code, reason);
    } catch {
      /* 已关闭 */
    }
  }
}

export class WorkerClient {
  constructor(worker) {
    this.worker = worker;
    this.base = worker.base;
    this.wsBase = worker.wsBase;
  }

  async createRoom({
    hostPeerId,
    hostName = 'Host',
    password = '',
    requireApproval = false,
    maxPeers = 8,
    isPublic = false,
    title = '',
    canvasWidth = 200,
    canvasHeight = 120,
  } = {}) {
    const res = await fetch(`${this.base}/c/v1/rooms`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        hostPeerId,
        hostName,
        password,
        requireApproval,
        maxPeers,
        isPublic,
        ...(title ? { title } : {}),
        canvasMeta: { width: canvasWidth, height: canvasHeight },
      }),
    });
    const body = await res.json();
    return { status: res.status, body, ok: res.ok && body.ok === true };
  }

  async roomInfo(roomCode) {
    const res = await fetch(`${this.base}/c/v1/rooms/${roomCode}/info`);
    return { status: res.status, body: await res.json().catch(() => null) };
  }

  async listRooms(limit = 100) {
    const res = await fetch(`${this.base}/c/v1/rooms?limit=${limit}`);
    return { status: res.status, body: await res.json().catch(() => null) };
  }

  /** 建立信令连接; 握手被拒 (4xx) 时抛错 */
  connect({ roomCode, peerId, name, role = 'peer', token = '', password = '', timeoutMs = 10000 }) {
    const q = new URLSearchParams({ peerId, role, name });
    if (token) q.set('token', token);
    if (password) q.set('pw', password);
    const url = `${this.wsBase}/c/v1/rooms/${roomCode}/ws?${q}`;

    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url);
      const inbox = [];
      const state = { code: null, reason: '', closed: false };
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        try { ws.close(); } catch { /* noop */ }
        reject(new Error(`ws 连接超时: ${peerId}`));
      }, timeoutMs);

      ws.addEventListener('message', (event) => {
        if (event.data === 'pong') return;
        try {
          inbox.push(JSON.parse(event.data));
        } catch {
          /* 非 JSON 忽略 */
        }
      });
      ws.addEventListener('close', (event) => {
        state.code = event.code;
        state.reason = String(event.reason || '');
        state.closed = true;
      });
      ws.addEventListener('open', () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(new PeerSocket({ ws, peerId, inbox, state }));
      });
      ws.addEventListener('error', () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(new Error(`ws 握手被拒: ${peerId}`));
      });
    });
  }

  /** 期望握手被拒, 返回是否真的被拒 */
  async connectExpectReject(options) {
    try {
      const sock = await this.connect(options);
      sock.close();
      return false;
    } catch {
      return true;
    }
  }
}

/** 便捷: 建一个房间并让房主连上 */
export async function openRoom(client, options = {}) {
  const hostPeerId = options.hostPeerId || 'p_host0001';
  const created = await client.createRoom({ hostPeerId, ...options });
  if (!created.ok) throw new Error(`建房失败: ${JSON.stringify(created.body)}`);
  const host = await client.connect({
    roomCode: created.body.roomCode,
    peerId: hostPeerId,
    name: options.hostName || 'Host',
    role: 'host',
    token: created.body.hostToken,
    password: options.password || '',
  });
  await host.waitFor((m) => m.type === 'welcome');
  await sleep(120);
  host.clear();
  return { roomCode: created.body.roomCode, hostToken: created.body.hostToken, host };
}
