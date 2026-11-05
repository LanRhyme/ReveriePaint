// SPDX-License-Identifier: GPL-3.0-or-later
//
// ReveriePaint 崩溃遥测接收端 (Cloudflare Worker + D1)。
// 契约见 docs/TELEMETRY.md §7。设计要点:
//   1. 不读 CF-Connecting-IP,不落任何 IP 或 IP 哈希
//   2. 配额按 install 维度(5 条/天)与签名维度(3 条/天),超限仍回 200(客户端不重试)
//   3. DISABLED=1 时全局 410,客户端收到后永久停用(kill switch,不必发版)

import { APP_ICON_SVG } from './icon.js';
import { deviceTitle, deviceBody, deviceLabel, aliasOf } from './devnames.js';

const CONTRACT_VERSION = 1;
const MAX_BODY_BYTES = 128 * 1024;
const DAILY_PER_INSTALL = 5;
const DAILY_PER_SIG = 3;
const MAX_STACK = 16 * 1024;
const MAX_LOGCAT = 32 * 1024;
const MAX_CRUMBS = 32;
const MAX_FRAMES = 24;
const RETENTION_DAYS = 180;
const KINDS = new Set(['java_crash', 'native_crash', 'anr']);

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    try {
      if (path === '/t/v1/health') {
        return json({ ok: true, version: CONTRACT_VERSION, disabled: isDisabled(env) });
      }
      if (path === '/t/v1/crash' && request.method === 'POST') {
        return await handleIngest(request, env);
      }
      if (path === '/t/v1/stats' && request.method === 'GET') {
        return await handleStats(request, url, env);
      }
      if (path === '/t/v1/stats' && request.method === 'POST') {
        return await handleLogin(request, url, env);
      }
      if (path.startsWith('/t/v1/symbols/')) {
        return await handleSymbols(request, url, env);
      }
      if (path === '/t/v1/alerts/test') {
        await requireStatsAuth(request, url, env);
        if (url.searchParams.get('mode') === 'scan') {
          const result = await runAlerts(env);
          return json({ ok: true, ...result });
        }
        const sent = await notify(env, 'ReveriePaint 告警通道自测', '如果你收到这条, 说明告警通道已接通。');
        return json({ ok: true, sent });
      }
      if (path === '/t/v1/logout') {
        return new Response(null, {
          status: 303,
          headers: {
            location: '/t/v1/stats',
            'set-cookie': cookie('rp_stats', '', 0),
            'cache-control': 'no-store',
          },
        });
      }
      return json({ ok: false, error: 'not_found' }, 404);
    } catch (err) {
      if (err instanceof HttpError) {
        return json({ ok: false, error: err.message }, err.status);
      }
      return json({ ok: false, error: 'internal' }, 500);
    }
  },

  /**
   * 每日定时清理。保留期写在 docs/PRIVACY.md 里, 这里是它的执行体 ——
   * 声明了"存 180 天"就必须真的有东西在删。
   */
  async scheduled(event, env) {
    if (!env.DB) return;
    // 两个 cron 共用一个入口: 每小时跑告警, 每天跑保留期清理
    if (event && event.cron && event.cron.startsWith('17 3')) {
      const cutoff = Math.floor(Date.now() / 1000) - RETENTION_DAYS * 86400;
      await env.DB.prepare('DELETE FROM crashes WHERE received < ?').bind(cutoff).run();
      return;
    }
    await runAlerts(env);
  },
};

function isDisabled(env) {
  return String(env.DISABLED ?? '0') === '1';
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}

function checkKey(request, env, url) {
  const expected = env.INGEST_KEY ?? '';
  if (!expected) return; // 未配置 key 时放行(仅本地开发)
  const given = request.headers.get('x-rp-key') ?? url.searchParams.get('key') ?? '';
  if (given !== expected) throw new HttpError(401, 'bad_key');
}

// ---------------------------------------------------------------- 看板鉴权

/**
 * 看板是"固定公开链接 + 登录门"的形态:
 *   - 链接本身不含任何秘密, 任何人拿到都能打开, 但看到的是登录页
 *   - 登录密码用 STATS_PASSWORD(未配置则退回 STATS_KEY / INGEST_KEY)
 *   - 登录成功后种一年期的 HttpOnly Cookie, 之后直接进看板
 *   - 脚本仍可用 ?key=STATS_KEY 或 x-stats-key 头直接取数据(免登录)
 *
 * 与上报的 INGEST_KEY 分开的原因: 看板凭据会进浏览器/书签, 不能是写入凭据。
 */
function statsPassword(env) {
  return env.STATS_PASSWORD || env.STATS_KEY || env.INGEST_KEY || '';
}

function statsKey(env) {
  return env.STATS_KEY || env.INGEST_KEY || '';
}

/** Cookie 里放的不是密码本身, 而是 sha256("revp-stats-v1|"+密码), 泄了也拿不到密码 */
async function statsToken(env) {
  const secret = statsPassword(env);
  if (!secret) return '';
  const data = new TextEncoder().encode('revp-stats-v1|' + secret);
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', data));
  let out = '';
  for (const b of digest) out += b.toString(16).padStart(2, '0');
  return out;
}

function safeEqual(a, b) {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

/** 是否已通过鉴权; 不做异常, 供"要不要显示登录页"判断 */
async function isStatsAuthed(request, env, url) {
  const secret = statsPassword(env);
  if (!secret) return true; // 未配置时放行(仅本地开发)

  const cookieVal = cookieValue(request, 'rp_stats') ?? '';
  if (cookieVal) {
    const token = await statsToken(env);
    if (token && safeEqual(cookieVal, token)) return true;
  }

  const qKey = url.searchParams.get('key') ?? request.headers.get('x-stats-key') ?? '';
  if (qKey && safeEqual(qKey, secret)) return true;
  const k = statsKey(env);
  if (qKey && k && safeEqual(qKey, k)) return true;
  return false;
}

/** 浏览器给登录页, 程序和 curl 给 401(别把 HTML 当 JSON 解析) */
function wantsHtml(request) {
  return (request.headers.get('accept') ?? '').includes('text/html');
}

const THEMES = ['auto', 'light', 'dark'];

function cookie(name, value, maxAge = 31536000) {
  return `${name}=${value}; Path=/t/v1; HttpOnly; Secure; SameSite=Lax; Max-Age=${maxAge}`;
}

function themeOf(request, url) {
  const q = url.searchParams.get('theme');
  if (q && THEMES.includes(q)) return q;
  const c = cookieValue(request, 'rp_theme');
  return c && THEMES.includes(c) ? c : 'auto';
}

function cookieValue(request, name) {
  const raw = request.headers.get('cookie') ?? '';
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return null;
}

async function readBody(request) {
  const ab = await request.arrayBuffer();
  if (ab.byteLength > MAX_BODY_BYTES) throw new HttpError(413, 'too_large');
  const bytes = new Uint8Array(ab);
  const enc = (request.headers.get('content-encoding') ?? '').toLowerCase();
  // 只有真的以 gzip magic(1f 8b) 开头才解压: 某些边缘/运行时会在交给 Worker 前
  // 透明解掉 body, 那时再尝试解压会 500 —— 宁可容忍明文
  const isGzip = bytes.length > 1 && bytes[0] === 0x1f && bytes[1] === 0x8b;
  if (!enc.includes('gzip') || !isGzip) return new TextDecoder().decode(bytes);
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  return await new Response(stream).text();
}

function clampText(value, max) {
  if (typeof value !== 'string') return '';
  return value.length > max ? value.slice(0, max) : value;
}

function clampInt(value, fallback = 0) {
  // 注意 Number(null) === 0: 参数缺省时必须走 fallback, 否则 ?days 缺省会变成 1 天
  if (value === null || value === undefined || value === '') return fallback;
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : fallback;
}

function dayStartUtc(nowMs) {
  const d = new Date(nowMs);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) / 1000;
}

/** 归一化上报体: 只保留契约里出现的字段, 其余丢弃(纵深防御: 客户端错了也不该落库奇怪字段) */
function normalize(raw, nowSec) {
  if (!raw || typeof raw !== 'object') throw new HttpError(400, 'bad_body');
  const kind = String(raw.kind ?? '');
  if (!KINDS.has(kind)) throw new HttpError(400, 'bad_kind');
  const install = clampText(raw.install, 64);
  const sig = clampText(raw.sig, 128);
  if (!install || !sig) throw new HttpError(400, 'missing_install_or_sig');

  const app = raw.app ?? {};
  const dev = raw.dev ?? {};
  const doc = raw.doc ?? {};

  let at = clampInt(raw.at, 0);
  if (at <= 0 || at > nowSec + 300) at = nowSec; // 容忍设备时钟漂移

  const crumbs = Array.isArray(raw.crumbs) ? raw.crumbs.slice(-MAX_CRUMBS) : [];
  const native = raw.native ?? null;

  return {
    v: CONTRACT_VERSION,
    kind,
    install,
    sig,
    at,
    app: {
      ver: clampText(app.ver, 32),
      code: clampInt(app.code, 0),
      abi: clampText(app.abi, 32),
    },
    dev: {
      mfr: clampText(dev.mfr, 48),
      model: clampText(dev.model, 64),
      dev: clampText(dev.dev, 64),
      sdk: clampInt(dev.sdk, 0),
      rel: clampText(dev.rel, 32),
      fp: clampText(dev.fp, 160),
      ram_mb: clampInt(dev.ram_mb, 0),
      harmony: clampText(dev.harmony, 160),
    },
    doc: {
      w: clampInt(doc.w, 0),
      h: clampInt(doc.h, 0),
      layers: clampInt(doc.layers, 0),
      page: clampText(doc.page, 32),
      tool: clampText(doc.tool, 48),
      stylus: clampText(doc.stylus, 48),
    },
    crumbs: crumbs
      .filter((c) => c && typeof c === 'object')
      .map((c) => ({
        t: clampInt(c.t, 0),
        c: clampText(c.c, 24),
        // 32 会把 stall:2939ms:Class.method 这类标签截半个词, 放宽到 64
        e: clampText(c.e, 64),
      })),
    stack: clampText(raw.stack, MAX_STACK),
    logcat: clampText(raw.logcat, MAX_LOGCAT),
    native: native
      ? {
          signal: clampText(native.signal, 24),
          code: clampInt(native.code, 0),
          fault: clampText(native.fault, 24),
          frames: (Array.isArray(native.frames) ? native.frames : [])
            .slice(0, MAX_FRAMES)
            .map((f) => clampText(f, 120)),
        }
      : null,
  };
}

async function handleIngest(request, env) {
  const url = new URL(request.url);
  checkKey(request, env, url);
  if (isDisabled(env)) throw new HttpError(410, 'gone');

  const nowMs = Date.now();
  const nowSec = Math.floor(nowMs / 1000);
  const body = await readBody(request);

  let raw;
  try {
    raw = JSON.parse(body);
  } catch {
    throw new HttpError(400, 'bad_json');
  }
  if (clampInt(raw?.v, 0) !== CONTRACT_VERSION) throw new HttpError(400, 'bad_version');

  const payload = normalize(raw, nowSec);
  const db = env.DB;

  const since = dayStartUtc(nowMs);
  const byInstall = await db
    .prepare('SELECT COUNT(*) AS n FROM crashes WHERE install = ? AND received >= ?')
    .bind(payload.install, since)
    .first();
  if ((byInstall?.n ?? 0) >= DAILY_PER_INSTALL) {
    return json({ ok: true, dropped: 'install_daily_cap' });
  }

  const bySig = await db
    .prepare('SELECT COUNT(*) AS n FROM crashes WHERE install = ? AND sig = ? AND received >= ?')
    .bind(payload.install, payload.sig, since)
    .first();
  if ((bySig?.n ?? 0) >= DAILY_PER_SIG) {
    return json({ ok: true, dropped: 'sig_daily_cap' });
  }

  await db
    .prepare(
      `INSERT INTO crashes (sig, kind, at, received, app_code, app_ver, abi, sdk, model, install, payload)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      payload.sig,
      payload.kind,
      payload.at,
      nowSec,
      payload.app.code,
      payload.app.ver,
      payload.app.abi,
      payload.dev.sdk,
      payload.dev.model,
      payload.install,
      JSON.stringify(payload),
    )
    .run();

  return json({ ok: true });
}

function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function fmtTime(sec) {
  if (!sec) return '-';
  return new Date(sec * 1000).toISOString().replace('T', ' ').slice(0, 16) + 'Z';
}

const MORANDI = {
  native_crash: { color: '#C0705F', label: 'Native' },
  java_crash: { color: '#B99A5B', label: 'Java' },
  anr: { color: '#8B7FA8', label: 'ANR' },
};

async function handleLogin(request, url, env) {
  const secret = statsPassword(env);
  if (!secret) return redirect('/t/v1/stats');

  const form = await readForm(request, 8 * 1024);
  const password = (form.get('password') ?? '').slice(0, 256);
  const next = safeNext(form.get('next'));
  const qKey = url.searchParams.get('key') ?? '';

  const ok = (password && safeEqual(password, secret)) || (qKey && safeEqual(qKey, secret));
  if (!ok) {
    // 失败不区分"没填/填错", 也不回显; 稍作延迟抬高爆破成本
    await sleep(600);
    return new Response(loginPage({ next, error: true }), {
      status: 401,
      headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' },
    });
  }

  return new Response(null, {
    status: 303,
    headers: {
      location: next,
      'set-cookie': cookie('rp_stats', await statsToken(env)),
      'cache-control': 'no-store',
    },
  });
}

function redirect(location) {
  return new Response(null, { status: 303, headers: { location, 'cache-control': 'no-store' } });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 只允许跳回本站看板路径, 防止开放重定向 */
function safeNext(raw) {
  const next = String(raw ?? '');
  if (next.startsWith('/t/v1/stats') && !next.startsWith('//')) return next;
  return '/t/v1/stats';
}

async function readForm(request, limit) {
  const text = await request.text();
  if (text.length > limit) throw new HttpError(413, 'too_large');
  return new URLSearchParams(text);
}

function loginPage({ next = '/t/v1/stats', error = false } = {}) {
  const theme = 'auto';
  return `<!doctype html><html lang="zh-CN" data-theme="${theme}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>ReveriePaint 崩溃看板 · 登录</title>
<style>
:root{--bg:#F2F2F7;--panel:#F7F7F9;--panel-hi:#EAEAEE;--text:#242426;--sub:#8A8A8E;
      --accent:#5A6E8A;--line:rgba(0,0,0,.07);
      --shadow:0 1px 2px rgba(0,0,0,.04), 0 24px 48px -32px rgba(20,20,30,.5)}
@media (prefers-color-scheme: dark){
  :root{--bg:#141416;--panel:#1E1E22;--panel-hi:#2A2A2E;--text:#F5F5F7;--sub:#8E8E93;
        --accent:#8FA1B8;--line:rgba(255,255,255,.08);
        --shadow:0 1px 2px rgba(0,0,0,.4), 0 24px 48px -30px rgba(0,0,0,.9)}
}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
     background:var(--bg);color:var(--text);padding:24px;
     font:14px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Noto Sans CJK SC",sans-serif}
.card{width:100%;max-width:360px;background:var(--panel);border-radius:22px;padding:28px 24px;
      box-shadow:var(--shadow)}
.logo{display:flex;align-items:center;justify-content:center;width:52px;height:52px;margin:0 auto 16px;
      border-radius:15px;background:#0e0f16;padding:9px;box-shadow:inset 0 0 0 1px rgba(255,255,255,.07)}
.logo svg{width:100%;height:100%;display:block}
h1{margin:0;font-size:17px;font-weight:650;text-align:center;letter-spacing:.2px}
p.sub{margin:6px 0 22px;text-align:center;color:var(--sub);font-size:12.5px}
label{display:block;font-size:12px;color:var(--sub);margin-bottom:6px;letter-spacing:.3px}
input[type=password]{width:100%;padding:11px 13px;border-radius:12px;border:1px solid var(--line);
      background:var(--bg);color:var(--text);font-size:15px;outline:none;letter-spacing:.06em}
input[type=password]:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(90,110,138,.16)}
button{width:100%;margin-top:16px;padding:11px 13px;border:none;border-radius:12px;cursor:pointer;
       background:var(--accent);color:#fff;font-size:14px;font-weight:600;letter-spacing:.3px}
button:hover{filter:brightness(1.05)}
.err{margin:0 0 14px;padding:9px 12px;border-radius:10px;background:rgba(208,68,68,.1);
     color:#C05555;font-size:12.5px;text-align:center}
.foot{margin-top:18px;text-align:center;color:var(--sub);font-size:11.5px}
</style></head><body>
<form class="card" method="post" action="/t/v1/stats">
  <div class="logo">${APP_ICON_SVG}</div>
  <h1>崩溃看板</h1>
  <p class="sub">ReveriePaint · 需要密码才能查看</p>
  ${error ? '<p class="err">密码不对, 再试一次</p>' : ''}
  <label for="pw">访问密码</label>
  <input id="pw" name="password" type="password" autocomplete="current-password"
         autofocus autocapitalize="off" spellcheck="false" required>
  <input type="hidden" name="next" value="${escapeHtml(next)}">
  <button type="submit">进入看板</button>
  <p class="foot">密码由上架时设置 · Cookie 保留一年</p>
</form>
</body></html>`;
}

// ---------------------------------------------------------------- 符号归档

const SYMBOL_FILES = new Set(['libreverie_core.so', 'libreverie_jni.so', 'manifest.txt']);

/**
 * GET /t/v1/symbols/<versionCode>/<file>
 * 返回未 strip 的库(以 gzip 存、gzip 传), 供本机符号化时取用。
 * 与看板同一套鉴权: 符号文件也算内部资料。
 */
async function handleSymbols(request, url, env) {
  await requireStatsAuth(request, url, env);
  const rest = url.pathname.replace('/t/v1/symbols/', '');
  const slash = rest.indexOf('/');
  const version = slash > 0 ? rest.slice(0, slash).replace(/\D/g, '').slice(0, 8) : '';
  const file = slash > 0 ? rest.slice(slash + 1) : 'manifest.txt';
  if (!version || !SYMBOL_FILES.has(file)) throw new HttpError(404, 'not_found');
  if (!env.SYMBOLS) throw new HttpError(503, 'kv_not_bound');

  const key = `${version}/${file}`;
  if (file === 'manifest.txt') {
    const text = await env.SYMBOLS.get(key, 'text');
    if (!text) throw new HttpError(404, 'not_found');
    return new Response(text, {
      headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'private, max-age=300' },
    });
  }

  // 大文件走流式透传: KV 里存的就是 gzip 字节, 这里不解压
  const stream = await env.SYMBOLS.get(key, 'stream');
  if (!stream) throw new HttpError(404, 'not_found');
  return new Response(stream, {
    headers: {
      'content-type': 'application/gzip',
      'content-disposition': `attachment; filename="${file}.gz"`,
      'cache-control': 'private, max-age=300',
    },
  });
}

// ---------------------------------------------------------------- 告警

const ALERT_WINDOW_SEC = 3600;      // 每小时看过去 1 小时
const SPIKE_THRESHOLD = 5;          // 同一签名 1 小时内 ≥5 次算突增
const ALERT_COOLDOWN_SEC = 6 * 3600; // 同一签名 6 小时内不重复提醒

async function requireStatsAuth(request, url, env) {
  if (await isStatsAuthed(request, env, url)) return;
  throw new HttpError(401, 'need_key');
}

/**
 * 每小时跑一次:
 *  - 从没见过的签名 → 立刻提醒(这才是需要人介入的)
 *  - 老签名 1 小时内次数超过阈值 → 提醒一次, 之后 6 小时冷却
 * 提醒完把基线写回 known_sigs, 避免重启后重复轰炸。
 */
async function runAlerts(env) {
  if (isDisabled(env) || !env.DB) return { new: 0, spike: 0 };
  const now = Math.floor(Date.now() / 1000);

  const recent = await env.DB
    .prepare(
      `SELECT sig, kind, COUNT(*) AS n, COUNT(DISTINCT install) AS people,
              MAX(app_ver) AS ver, MAX(model) AS model
         FROM crashes WHERE at >= ? GROUP BY sig, kind`,
    )
    .bind(now - ALERT_WINDOW_SEC)
    .all();
  const rows = recent.results ?? [];
  if (rows.length === 0) return { new: 0, spike: 0 };

  const known = await env.DB.prepare('SELECT sig, last_alert FROM known_sigs').all();
  const baseline = new Map((known.results ?? []).map((r) => [r.sig, r.last_alert ?? 0]));

  let newCount = 0;
  let spikeCount = 0;
  for (const r of rows) {
    const lastAlert = baseline.get(r.sig);
    const isNew = lastAlert === undefined;
    const isSpike = !isNew && r.n >= SPIKE_THRESHOLD && now - lastAlert > ALERT_COOLDOWN_SEC;
    if (!isNew && !isSpike) continue;

    const title = isNew ? '新的崩溃签名' : '崩溃突增';
    const body = [
      `**${isNew ? '首次出现' : '突增'}**：${r.kind} · \`${String(r.sig).slice(0, 10)}\``,
      `次数：${r.n}（近 1 小时）· 设备：${r.people}`,
      `版本：${r.ver ?? '-'} · 机型：${r.model ?? '-'}`,
      `时间：${new Date(now * 1000).toISOString().replace('T', ' ').slice(0, 16)}Z`,
    ].join('\n');

    await notify(env, title, body);
    const stamp = now;
    await env.DB
      .prepare(
        `INSERT INTO known_sigs (sig, kind, first_seen, last_alert) VALUES (?, ?, ?, ?)
         ON CONFLICT(sig) DO UPDATE SET last_alert = excluded.last_alert`,
      )
      .bind(r.sig, r.kind, isNew ? now : (baseline.get(r.sig) ? now : now), stamp)
      .run();
    if (isNew) newCount++;
    else spikeCount++;
  }
  return { new: newCount, spike: spikeCount };
}

/**
 * 通知出口。按配置的 secret 自动选择, 可同时配多个:
 *   ALERT_WEBHOOK_URL   飞书 / 钉钉 群机器人, 或任意接受 JSON 的地址
 *   ALERT_GITHUB_REPO   形如 LanRhyme/ReveriePaint, 配合 ALERT_GITHUB_TOKEN 开 issue
 */
async function notify(env, title, body) {
  const sent = [];

  const hook = (env.ALERT_WEBHOOK_URL ?? '').trim();
  if (hook) {
    let payload;
    if (hook.includes('open.feishu.cn') || hook.includes('larksuite.com')) {
      payload = { msg_type: 'text', content: { text: `${title}\n${stripMd(body)}` } };
    } else if (hook.includes('oapi.dingtalk.com')) {
      payload = { msgtype: 'text', text: { content: `${title}\n\n${stripMd(body)}` } };
    } else {
      payload = { title, text: `${title}\n\n${stripMd(body)}`, markdown: `### ${title}\n${body}` };
    }
    const r = await fetch(hook, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    }).catch(() => null);
    sent.push(`webhook:${r ? r.status : 'error'}`);
  }

  const repo = (env.ALERT_GITHUB_REPO ?? '').trim();
  const token = (env.ALERT_GITHUB_TOKEN ?? '').trim();
  if (repo && token) {
    const r = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${token}`,
        accept: 'application/vnd.github+json',
        'content-type': 'application/json',
        'user-agent': 'reveriepaint-telemetry',
      },
      body: JSON.stringify({ title: `[crash] ${title}`, body }),
    }).catch(() => null);
    sent.push(`github:${r ? r.status : 'error'}`);
  }

  return sent;
}

/** 群机器人不解析 Markdown, 去掉标记避免原样显示 */
function stripMd(text) {
  return String(text)
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/`(.+?)`/g, '$1');
}

async function handleStats(request, url, env) {
  // 未登录: 浏览器给登录页(链接本身是公开固定的), 其它客户端给 401
  if (!(await isStatsAuthed(request, env, url))) {
    if (!wantsHtml(request)) throw new HttpError(401, 'need_key');
    return new Response(loginPage({ next: url.pathname + url.search.replace(/([?&])key=[^&]*&?/, '$1') }), {
      status: 200,
      headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' },
    });
  }

  const headers = new Headers({ 'cache-control': 'no-store' });
  const token = await statsToken(env);
  if (token) headers.append('set-cookie', cookie('rp_stats', token));

  // ?key= / ?theme= 都先落 Cookie, 再跳回不带这些参数的同一条 URL
  const qTheme = url.searchParams.get('theme');
  const qKey = url.searchParams.get('key');
  if (qKey || qTheme) {
    if (qTheme && THEMES.includes(qTheme)) headers.append('set-cookie', cookie('rp_theme', qTheme));
    const clean = new URL(url);
    clean.searchParams.delete('key');
    clean.searchParams.delete('theme');
    headers.set('location', clean.pathname + clean.search);
    return new Response(null, { status: 302, headers });
  }

  const days = Math.min(Math.max(clampInt(url.searchParams.get('days'), 7), 1), 90);
  const kind = clampText(url.searchParams.get('kind'), 24);
  const ctx = {
    db: env.DB,
    days,
    since: Math.floor(Date.now() / 1000) - days * 86400,
    model: clampText(url.searchParams.get('model'), 64),
    ver: clampText(url.searchParams.get('ver'), 32),
    kind: KINDS.has(kind) ? kind : '',
    q: clampText(url.searchParams.get('q'), 48),
    sig: clampText(url.searchParams.get('sig'), 128),
    theme: themeOf(request, url),
  };

  const format = url.searchParams.get('format');
  if (format === 'csv') return await exportCsv(ctx);
  if (format === 'md') return await exportMarkdown(ctx);

  const html = ctx.sig ? await renderSignature(ctx, ctx.sig) : await renderOverview(ctx);
  headers.set('content-type', 'text/html; charset=utf-8');
  return new Response(html, { status: 200, headers });
}

/** 时间窗 + 机型/版本/类型/关键词筛选, 所有查询共用 */
function whereClause(ctx, extra = []) {
  const clauses = ['at >= ?'];
  const params = [ctx.since];
  if (ctx.model) {
    clauses.push('model = ?');
    params.push(ctx.model);
  }
  if (ctx.ver) {
    clauses.push('app_ver = ?');
    params.push(ctx.ver);
  }
  if (ctx.kind) {
    clauses.push('kind = ?');
    params.push(ctx.kind);
  }
  if (ctx.q) {
    clauses.push('(sig LIKE ? OR model LIKE ? OR app_ver LIKE ?)');
    const like = `%${ctx.q}%`;
    params.push(like, like, like);
  }
  for (const e of extra) clauses.push(e);
  return { sql: ' WHERE ' + clauses.join(' AND '), params };
}

async function q(ctx, sql, params) {
  const res = await ctx.db.prepare(sql).bind(...params).all();
  return res.results ?? [];
}

async function q1(ctx, sql, params) {
  return (await ctx.db.prepare(sql).bind(...params).first()) ?? {};
}

function link(ctx, extra = {}) {
  const params = {
    days: ctx.days,
    model: ctx.model,
    ver: ctx.ver,
    kind: ctx.kind,
    q: ctx.q,
    sig: ctx.sig,
    ...extra,
  };
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === '' || v === null || v === undefined) continue;
    qs.set(k, String(v));
  }
  return '?' + qs.toString();
}

function prettyPayload(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return String(text ?? '');
  }
}

function kindChip(kind) {
  const m = MORANDI[kind] ?? { color: '#8A8A8E', label: kind };
  return `<span class="chip"><i style="background:${m.color}"></i>${escapeHtml(m.label)}</span>`;
}

/** 相对时间: 扫读"多久以前"比读绝对时间快 */
function fmtRel(sec) {
  if (!sec) return '-';
  const diff = Math.floor(Date.now() / 1000) - sec;
  if (diff < 60) return '刚刚';
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
  if (diff < 86400 * 30) return `${Math.floor(diff / 86400)} 天前`;
  return fmtTime(sec).slice(0, 10);
}

function fillTrend(points, days) {
  const map = new Map(points.map((p) => [p.d, p.n]));
  const now = Date.now();
  const out = [];
  for (let i = days - 1; i >= 0; i--) {
    const key = new Date(now - i * 86400000).toISOString().slice(0, 10);
    out.push({ d: key, n: map.get(key) ?? 0 });
  }
  return out;
}

function trendChart(points, compact = false) {
  const max = Math.max(1, ...points.map((p) => p.n));
  const every = Math.ceil(points.length / 7);
  const cols = points
    .map((p, i) => {
      const h = p.n === 0 ? 2 : Math.max(6, Math.round((p.n / max) * 100));
      const label = compact ? '' : i % every === 0 || i === points.length - 1 ? p.d.slice(5) : '';
      return `<div class="col" title="${escapeHtml(p.d)}: ${p.n} 条">
        <div class="track"><i style="height:${h}%" class="${p.n ? '' : 'zero'}"></i></div>
        <span>${label}</span>
      </div>`;
    })
    .join('');
  const total = points.reduce((a, p) => a + p.n, 0);
  return `<div class="trend${compact ? ' compact' : ''}">${cols}</div>
    <p class="foot">峰值 ${max} · 合计 ${total} 条 · ${escapeHtml(points[0]?.d ?? '')} → ${escapeHtml(
      points[points.length - 1]?.d ?? '',
    )}</p>`;
}

/**
 * 机型分布: 除型号外还取厂商/产品代号/安卓版本(都存在 payload JSON 里), 用于显示可读设备名。
 * D1 的 JSON1 万一不可用就退回只按型号聚合 —— 看板不能因为一个富化字段整页报错。
 */
async function modelBreakdown(ctx, w) {
  // 按**型号**聚合(不再按 model,sdk 拆行): 同一台机器跨大版本升级不该在看板上裂成两条,
  // 安卓版本用区间表示(Android 14–16), 正好也说明这台机器被用过两个系统版本。
  try {
    return await q(
      ctx,
      `SELECT model,
              MAX(json_extract(payload,'$.dev.mfr')) AS mfr,
              MAX(json_extract(payload,'$.dev.dev')) AS dev,
              MIN(json_extract(payload,'$.dev.rel')) AS rel_min,
              MAX(json_extract(payload,'$.dev.rel')) AS rel_max,
              MIN(sdk) AS sdk,
              COUNT(*) AS n
         FROM crashes${w.sql} GROUP BY model ORDER BY n DESC LIMIT 10`,
      w.params,
    );
  } catch (e) {
    console.error('modelBreakdown fallback:', e?.message ?? e);
    return await q(
      ctx,
      `SELECT model, MIN(sdk) AS sdk, COUNT(*) AS n FROM crashes${w.sql} GROUP BY model ORDER BY n DESC LIMIT 10`,
      w.params,
    );
  }
}

/** 安卓版本展示: 同一型号跨版本时给区间 */
function relOf(row) {
  const lo = String(row.rel_min ?? '').trim();
  const hi = String(row.rel_max ?? '').trim();
  if (!lo) return hi;
  if (!hi || lo === hi) return lo;
  return `${lo}–${hi}`;
}

function barRows(ctx, items, keyLabel, valueOf, filterKey) {
  if (items.length === 0) return '<p class="empty">暂无数据</p>';
  const max = Math.max(1, ...items.map(valueOf));
  return items
    .map((it) => {
      const v = valueOf(it);
      const pct = Math.max(3, Math.round((v / max) * 100));
      const label = keyLabel(it);
      const href = filterKey ? link(ctx, { [filterKey]: label }) : null;
      const name = href ? `<a href="${href}">${escapeHtml(label || '(未知)')}</a>` : escapeHtml(label);
      return `<div class="bar-row"><span class="k">${name}</span>
        <span class="track"><i style="width:${pct}%"></i></span>
        <span class="v">${v}</span></div>`;
    })
    .join('');
}

/**
 * 机型分布的两行渲染: 主标题(市场名/厂商型号) + 副标题(原始型号 · 代号 · 安卓版本)。
 * 下钻仍然按**原始型号**过滤, 所以 CSV/接口语义不变。
 */
function deviceBars(ctx, items) {
  if (items.length === 0) return '<p class="empty">暂无数据</p>';
  const max = Math.max(1, ...items.map((it) => it.n));
  return items
    .map((it) => {
      const pct = Math.max(3, Math.round((it.n / max) * 100));
      const dev = { mfr: it.mfr, model: it.model, dev: it.dev, rel: relOf(it), sdk: it.sdk };
      const href = it.model ? link(ctx, { model: it.model }) : null;
      const body = deviceBody(dev);
      const name = href ? `<a href="${href}" title="${escapeHtml(deviceLabel(dev))}">${body}</a>` : body;
      return `<div class="bar-row"><span class="k">${name}</span>
        <span class="track"><i style="width:${pct}%"></i></span>
        <span class="v">${it.n}</span></div>`;
    })
    .join('');
}

function versionSpan(head) {
  const a = escapeHtml(head.first_ver ?? '-');
  const b = escapeHtml(head.last_ver ?? '-');
  return a === b ? a : `${a} → ${b}`;
}

function metrics(items) {
  return `<div class="metrics m${items.length}">${items
    .map(
      (m) =>
        `<div class="metric"><span class="n${m.small ? ' small' : ''}">${m.value}</span><span class="l">${escapeHtml(
          m.label,
        )}</span></div>`,
    )
    .join('')}</div>`;
}

function filterChips(ctx) {
  const parts = [];
  if (ctx.kind) parts.push(`类型 <b>${escapeHtml(ctx.kind)}</b>`);
  if (ctx.q) parts.push(`搜索 <b>${escapeHtml(ctx.q)}</b>`);
  if (ctx.model) parts.push(`机型 <b>${escapeHtml(aliasOf({ model: ctx.model }) ? `${aliasOf({ model: ctx.model })} (${ctx.model})` : ctx.model)}</b>`);
  if (ctx.ver) parts.push(`版本 <b>${escapeHtml(ctx.ver)}</b>`);
  if (parts.length === 0) return '';
  return `<div class="chips">${parts.join(' · ')} <a href="${link(ctx, {
    model: '',
    ver: '',
    kind: '',
    q: '',
  })}">清除全部</a></div>`;
}

/** 类型筛选 + 搜索框 + 导出, 三者都保留当前其余筛选 */
function toolbar(ctx) {
  const kinds = [
    ['', '全部'],
    ['native_crash', 'Native'],
    ['java_crash', 'Java'],
    ['anr', 'ANR'],
  ];
  const kindLinks = kinds
    .map(
      ([v, label]) =>
        `<a class="pill${ctx.kind === v ? ' on' : ''}" href="${link(ctx, { kind: v, sig: '' })}">${label}</a>`,
    )
    .join('');

  const hidden = ['days', 'model', 'ver', 'kind']
    .filter((k) => ctx[k])
    .map((k) => `<input type="hidden" name="${k}" value="${escapeHtml(String(ctx[k]))}">`)
    .join('');

  return `<div class="toolbar">
    <nav class="range">${kindLinks}</nav>
    <form class="search" method="get" action="/t/v1/stats">
      ${hidden}
      <input type="search" name="q" placeholder="搜签名 / 机型 / 版本" value="${escapeHtml(ctx.q)}"
             autocapitalize="off" spellcheck="false">
      <button type="submit">搜索</button>
    </form>
    <span class="exports">
      <a href="${link(ctx, { format: 'csv' })}">CSV</a>
      <a href="${link(ctx, { format: 'md' })}">Markdown</a>
    </span>
  </div>`;
}

async function renderOverview(ctx) {
  const w = whereClause(ctx);
  const now = Math.floor(Date.now() / 1000);
  const total = await q1(ctx, `SELECT COUNT(*) AS n, COUNT(DISTINCT install) AS i FROM crashes${w.sql}`, w.params);
  const sigRows = await q(
    ctx,
    `SELECT sig, kind, COUNT(*) AS n, COUNT(DISTINCT install) AS people,
            MIN(at) AS first_at, MAX(at) AS last_at, MAX(app_ver) AS last_ver
       FROM crashes${w.sql} GROUP BY sig, kind ORDER BY people DESC, n DESC LIMIT 100`,
    w.params,
  );
  const models = await modelBreakdown(ctx, w);
  const vers = await q(
    ctx,
    `SELECT app_ver, COUNT(*) AS n FROM crashes${w.sql} GROUP BY app_ver ORDER BY n DESC LIMIT 8`,
    w.params,
  );
  const trendRows = await q(
    ctx,
    `SELECT date(received,'unixepoch') AS d, COUNT(*) AS n FROM crashes${w.sql} GROUP BY d ORDER BY d`,
    w.params,
  );

  // NEW 必须看"史上最早一次", 否则上周就崩过的签名进了 7 天窗口也会被标成新问题
  const firstEver = new Map();
  if (sigRows.length > 0) {
    const sigs = [...new Set(sigRows.map((r) => r.sig))];
    const marks = sigs.map(() => '?').join(',');
    const rows = await q(
      ctx,
      `SELECT sig, MIN(at) AS first_ever FROM crashes WHERE sig IN (${marks}) GROUP BY sig`,
      sigs,
    );
    for (const r of rows) firstEver.set(r.sig, r.first_ever);
  }
  const fresh = (r) => now - (firstEver.get(r.sig) ?? r.first_at) < 86400;
  const sigTable = sigRows
    .map(
      (r) => `<tr>
        <td><a class="sig" href="${link(ctx, { sig: r.sig })}">${kindChip(r.kind)}<code>${escapeHtml(
          r.sig.slice(0, 10),
        )}</code>${fresh(r) ? '<span class="badge new">NEW</span>' : ''}</a></td>
        <td class="num">${r.n}</td>
        <td class="num">${r.people}</td>
        <td class="mono">${escapeHtml(r.last_ver)}</td>
        <td class="dim" title="${fmtTime(r.first_at)}">${fmtRel(r.last_at)}</td>
      </tr>`,
    )
    .join('');

  const body = `
    ${toolbar(ctx)}
    ${filterChips(ctx)}
    ${metrics([
      { value: total.n ?? 0, label: `上报 · 近 ${ctx.days} 天` },
      { value: total.i ?? 0, label: '受影响设备' },
      { value: sigRows.length, label: '崩溃签名' },
    ])}
    <section class="card">
      <h2>每日上报</h2>
      ${trendChart(fillTrend(trendRows, ctx.days))}
    </section>
    <section class="card">
      <h2>按签名聚合</h2>
      <div class="scroll"><table>
        <thead><tr><th>签名</th><th class="num">次数</th><th class="num">设备</th><th>末见版本</th><th>最近发生</th></tr></thead>
        <tbody>${sigTable || `<tr><td colspan="5"><p class="empty">这段时间没有符合条件的崩溃</p></td></tr>`}</tbody>
      </table></div>
    </section>
    <div class="split">
      <section class="card"><h2>版本分布</h2>${barRows(ctx, vers, (v) => v.app_ver, (v) => v.n, 'ver')}</section>
      <section class="card"><h2>机型分布</h2>${deviceBars(ctx, models)}</section>
    </div>
    <p class="foot">点机型/版本/类型可下钻 · 点签名看现场 · NEW = 近 24h 首次出现 · 保留 180 天</p>`;
  return layout(`近 ${ctx.days} 天`, ctx, body);
}

/** 单条样本的结构化渲染: 设备 / 上下文 / 面包屑时间线 / 帧 / 原始 JSON */
function renderSample(r, i) {
  let p = {};
  try {
    p = JSON.parse(r.payload);
  } catch {
    p = {};
  }
  const dev = p.dev ?? {};
  const doc = p.doc ?? {};
  const native = p.native ?? null;

  const kv = (pairs) =>
    `<div class="kv">${pairs
      .filter(([, v]) => v !== '' && v !== 0 && v !== undefined && v !== null)
      .map(([k, v]) => `<div><span>${escapeHtml(k)}</span><b>${escapeHtml(String(v))}</b></div>`)
      .join('')}</div>`;

  const crumbs = Array.isArray(p.crumbs) ? p.crumbs : [];
  const timeline = crumbs.length
    ? `<div class="timeline">${crumbs
        .map(
          (c) =>
            `<span class="crumb" title="+${(c.t / 1000).toFixed(1)}s"><em>${escapeHtml(c.c)}</em>${escapeHtml(
              c.e,
            )}</span>`,
        )
        .join('')}</div>`
    : '<p class="empty">没有面包屑</p>';

  const frames = native && Array.isArray(native.frames) ? native.frames : [];
  const frameList = frames.length
    ? `<ol class="frames">${frames
        .map((f) => {
          const [mod, off] = String(f).split('+');
          // 只给能真正跑的命令: 偏移已是 0x 开头, 别再加前缀; [vdso] 这类伪模块没有文件
          const cmd =
            off && off.startsWith('0x') && !mod.startsWith('[') ? `llvm-addr2line -f -C -e ${mod} ${off}` : '';
          return `<li><code>${escapeHtml(mod)}</code><span>+${escapeHtml(off ?? '?')}</span>
            ${cmd ? `<code class="cmd">${escapeHtml(cmd)}</code>` : ''}</li>`;
        })
        .join('')}</ol>`
    : '';

  const head = `${fmtTime(r.received)} · ${escapeHtml(r.app_ver)} · ${escapeHtml(deviceTitle({ mfr: dev.mfr, model: r.model ?? dev.model, dev: dev.dev, rel: dev.rel, sdk: dev.sdk }))} · SDK ${r.sdk}`;
  return `<details class="sample" ${i === 0 ? 'open' : ''}>
    <summary>${head}</summary>
    <div class="sample-body">
      <h3>设备</h3>
      ${kv([
        ['机型', deviceTitle({ mfr: dev.mfr, model: dev.model ?? r.model, dev: dev.dev, rel: dev.rel, sdk: dev.sdk })],
        ['厂商 / 型号', `${dev.mfr ?? ''} ${dev.model ?? r.model ?? ''}`.trim()],
        ['设备代号', dev.dev],
        ['系统', [dev.rel ? `Android ${dev.rel}` : '', dev.sdk ? `SDK ${dev.sdk}` : '', p.app?.abi].filter(Boolean).join(' · ')],
        ['内存', dev.ram_mb ? `${dev.ram_mb} MB` : ''],
        ['鸿蒙/EMUI', dev.harmony],
        ['系统指纹', dev.fp],
      ])}
      <h3>崩溃时上下文</h3>
      ${kv([
        ['页面', doc.page],
        ['工具', doc.tool],
        ['画布', doc.w ? `${doc.w} × ${doc.h}` : ''],
        ['图层数', doc.layers],
        ['手写笔', doc.stylus],
      ]) || '<p class="empty">无上下文</p>'}
      <h3>操作轨迹（崩溃前 ${crumbs.length} 条）</h3>
      ${timeline}
      ${native ? `<h3>原生信号</h3>${kv([
        ['信号', native.signal],
        ['code', native.code],
        ['故障地址', native.fault],
      ])}${frames.length ? `<h3>调用栈（模块相对偏移，可直接 addr2line）</h3>${frameList}` : ''}` : ''}
      ${!native && p.stack ? `<h3>调用栈</h3><pre class="stack">${escapeHtml(shortStack(p.stack))}</pre>` : ''}
      <details class="raw"><summary>原始 JSON</summary><pre>${escapeHtml(prettyPayload(r.payload))}</pre></details>
    </div>
  </details>`;
}

/** Java 堆栈只保留前若干帧, 详情页别被第三方框架刷屏 */
function shortStack(stack) {
  const lines = String(stack).split('\n');
  const head = [];
  let frames = 0;
  for (const line of lines) {
    if (/^\s*at\s/.test(line)) {
      frames++;
      if (frames > 24) continue;
    }
    head.push(line);
  }
  if (frames > 24) head.push(`… 另外 ${frames - 24} 帧`);
  return head.join('\n');
}

async function renderSignature(ctx, sig) {
  const w = whereClause(ctx, ['sig = ?']);
  w.params.push(sig);
  const now = Math.floor(Date.now() / 1000);
  const head = await q1(
    ctx,
    `SELECT COUNT(*) AS n, COUNT(DISTINCT install) AS people, MIN(at) AS first_at, MAX(at) AS last_at,
            MIN(app_ver) AS first_ver, MAX(app_ver) AS last_ver, MAX(kind) AS kind
       FROM crashes${w.sql}`,
    w.params,
  );
  const ever = await q1(ctx, 'SELECT MIN(at) AS first_ever FROM crashes WHERE sig = ?', [sig]);
  const firstEver = ever.first_ever ?? head.first_at ?? 0;
  const models = await modelBreakdown(ctx, w);
  const vers = await q(
    ctx,
    `SELECT app_ver, COUNT(*) AS n FROM crashes${w.sql} GROUP BY app_ver ORDER BY n DESC LIMIT 8`,
    w.params,
  );
  const trendRows = await q(
    ctx,
    `SELECT date(received,'unixepoch') AS d, COUNT(*) AS n FROM crashes${w.sql} GROUP BY d ORDER BY d`,
    w.params,
  );
  const samples = await q(
    ctx,
    `SELECT received, app_ver, model, sdk, payload FROM crashes${w.sql} ORDER BY at DESC LIMIT 20`,
    w.params,
  );

  const body = `
    <a class="back" href="${link(ctx, { sig: '' })}">← 返回聚合</a>
    <div class="hero">
      ${kindChip(head.kind ?? '')}
      <code class="full">${escapeHtml(sig)}</code>
      ${now - firstEver < 86400 ? '<span class="badge new">NEW</span>' : ''}
    </div>
    ${metrics([
      { value: head.n ?? 0, label: `上报 · 近 ${ctx.days} 天` },
      { value: head.people ?? 0, label: '受影响设备' },
      { value: versionSpan(head), label: '版本跨度', small: true },
      { value: fmtRel(firstEver), label: '首次见到（全量）', small: true },
    ])}
    <section class="card"><h2>每日上报</h2>${trendChart(fillTrend(trendRows, ctx.days), true)}</section>
    <div class="split">
      <section class="card"><h2>版本分布</h2>${barRows(ctx, vers, (v) => v.app_ver, (v) => v.n, null)}</section>
      <section class="card"><h2>机型分布</h2>${deviceBars(ctx, models)}</section>
    </div>
    <section class="card">
      <h2>最近 ${samples.length} 条现场</h2>
      ${samples.map((r, i) => renderSample(r, i)).join('') || '<p class="empty">无记录</p>'}
    </section>`;
  return layout(`签名 ${sig.slice(0, 10)}`, ctx, body);
}

// ---------------------------------------------------------------- 导出

function csvCell(v) {
  const s = String(v ?? '');
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

async function exportCsv(ctx) {
  const w = whereClause(ctx);
  const rows = await q(
    ctx,
    `SELECT received, at, kind, sig, app_ver, app_code, abi, model, sdk, payload
       FROM crashes${w.sql} ORDER BY at DESC LIMIT 5000`,
    w.params,
  );
  const header = ['received', 'crash_at', 'kind', 'sig', 'app_ver', 'app_code', 'abi', 'device', 'model', 'sdk', 'page', 'tool', 'canvas', 'layers', 'signal'];
  const lines = [header.join(',')];
  for (const r of rows) {
    let p = {};
    try {
      p = JSON.parse(r.payload);
    } catch {}
    lines.push(
      [
        r.received,
        r.at,
        r.kind,
        r.sig,
        r.app_ver,
        r.app_code,
        r.abi,
        deviceLabel({ mfr: p.dev?.mfr, model: p.dev?.model ?? r.model, dev: p.dev?.dev, rel: p.dev?.rel, sdk: r.sdk }),
        r.model,
        r.sdk,
        p.doc?.page ?? '',
        p.doc?.tool ?? '',
        p.doc?.w ? `${p.doc.w}x${p.doc.h}` : '',
        p.doc?.layers ?? '',
        p.native?.signal ?? '',
      ]
        .map(csvCell)
        .join(','),
    );
  }
  return new Response('\uFEFF' + lines.join('\n'), {
    headers: {
      'content-type': 'text/csv; charset=utf-8',
      'content-disposition': `attachment; filename="revp-crashes-${ctx.days}d.csv"`,
      'cache-control': 'no-store',
    },
  });
}

async function exportMarkdown(ctx) {
  const w = whereClause(ctx);
  const rows = await q(
    ctx,
    `SELECT sig, kind, COUNT(*) AS n, COUNT(DISTINCT install) AS people,
            MIN(at) AS first_at, MAX(at) AS last_at, MAX(app_ver) AS last_ver
       FROM crashes${w.sql} GROUP BY sig, kind ORDER BY people DESC, n DESC LIMIT 100`,
    w.params,
  );
  const scope = [`近 ${ctx.days} 天`];
  if (ctx.kind) scope.push(`类型 ${ctx.kind}`);
  if (ctx.model) scope.push(`机型 ${ctx.model}`);
  if (ctx.ver) scope.push(`版本 ${ctx.ver}`);
  const lines = [
    `# ReveriePaint 崩溃汇总（${scope.join(' · ')}）`,
    '',
    '| 类型 | 签名 | 次数 | 设备 | 末见版本 | 首次 | 最近 |',
    '|---|---|---|---|---|---|---|',
  ];
  for (const r of rows) {
    lines.push(
      `| ${r.kind} | \`${r.sig.slice(0, 10)}\` | ${r.n} | ${r.people} | ${r.last_ver} | ${fmtTime(
        r.first_at,
      )} | ${fmtTime(r.last_at)} |`,
    );
  }
  if (rows.length === 0) lines.push('| - | 无 | 0 | 0 | - | - | - |');
  return new Response(lines.join('\n') + '\n', {
    headers: {
      'content-type': 'text/markdown; charset=utf-8',
      'content-disposition': `attachment; filename="revp-crashes-${ctx.days}d.md"`,
      'cache-control': 'no-store',
    },
  });
}

// ---------------------------------------------------------------- 页面骨架

function rangeNav(ctx) {
  return [7, 14, 30, 90]
    .map((d) => `<a class="pill${ctx.days === d ? ' on' : ''}" href="${link(ctx, { days: d })}">${d} 天</a>`)
    .join('');
}

function themeNav(ctx) {
  const opts = [
    ['auto', '自动'],
    ['light', '浅色'],
    ['dark', '深色'],
  ];
  return opts
    .map(
      ([v, label]) =>
        `<a class="pill${ctx.theme === v ? ' on' : ''}" href="${link(ctx, { theme: v })}" title="主题">${label}</a>`,
    )
    .join('');
}

function layout(title, ctx, body) {
  return `<!doctype html><html lang="zh-CN" data-theme="${ctx.theme}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>ReveriePaint 崩溃看板 · ${escapeHtml(title)}</title>
<style>
:root{
  --bg:#F2F2F7; --panel:#F7F7F9; --panel-hi:#EAEAEE; --text:#242426; --sub:#8A8A8E;
  --accent:#5A6E8A; --icon:#636366; --line:rgba(0,0,0,.07);
  --shadow:0 1px 2px rgba(0,0,0,.04), 0 12px 28px -18px rgba(20,20,30,.35);
}
/* 深色: 系统偏好自动生效, 但显式选浅色时压过系统 */
@media (prefers-color-scheme: dark){
  :root:not([data-theme="light"]){
    --bg:#141416; --panel:#1E1E22; --panel-hi:#2A2A2E; --text:#F5F5F7; --sub:#8E8E93;
    --accent:#8FA1B8; --icon:#98989D; --line:rgba(255,255,255,.08);
    --shadow:0 1px 2px rgba(0,0,0,.4), 0 16px 32px -22px rgba(0,0,0,.8);
  }
}
/* 手动选深色: 不看系统偏好 */
:root[data-theme="dark"]{
  --bg:#141416; --panel:#1E1E22; --panel-hi:#2A2A2E; --text:#F5F5F7; --sub:#8E8E93;
  --accent:#8FA1B8; --icon:#98989D; --line:rgba(255,255,255,.08);
  --shadow:0 1px 2px rgba(0,0,0,.4), 0 16px 32px -22px rgba(0,0,0,.8);
}
:root[data-theme="light"]{ color-scheme: light }
:root[data-theme="dark"]{ color-scheme: dark }
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);
     font:14px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Noto Sans CJK SC",sans-serif;
     -webkit-font-smoothing:antialiased}
.wrap{max-width:1000px;margin:0 auto;padding:28px 20px 64px}
.top{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:18px;flex-wrap:wrap}
.brand{display:flex;align-items:center;gap:9px;font-weight:650;letter-spacing:.2px}
/* 图标来自 art/icon.svg: 白色星芒是给深色底设计的, 所以垫一层应用图标的底色 #0e0f16 */
.brand .logo{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;
             flex:none;border-radius:10px;background:#0e0f16;padding:6px;
             box-shadow:inset 0 0 0 1px rgba(255,255,255,.07)}
.brand .logo svg{width:100%;height:100%;display:block}
.brand small{color:var(--sub);font-weight:500;letter-spacing:.4px}
.controls{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.logout{font-size:12px;color:var(--sub);padding:4px 2px}
.logout:hover{color:var(--text);text-decoration:none}
.range{display:flex;gap:4px;background:var(--panel-hi);padding:4px;border-radius:999px}
.pill{padding:5px 12px;border-radius:999px;font-size:12.5px;color:var(--sub);text-decoration:none;white-space:nowrap}
.pill.on{background:var(--panel);color:var(--text);font-weight:600;box-shadow:0 1px 2px rgba(0,0,0,.08)}
.toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px}
.search{display:flex;gap:6px;align-items:center;flex:1;min-width:220px}
.search input{flex:1;min-width:140px;padding:7px 12px;border-radius:999px;border:1px solid var(--line);
              background:var(--panel);color:var(--text);font-size:13px;outline:none}
.search input:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(90,110,138,.14)}
.search button{padding:7px 14px;border-radius:999px;border:none;background:var(--accent);color:#fff;
               font-size:13px;font-weight:600;cursor:pointer}
.exports{display:flex;gap:10px;font-size:12.5px}
.exports a{color:var(--sub)}
.exports a:hover{color:var(--accent)}
.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:16px}
.metrics.m3{grid-template-columns:repeat(3,1fr)}
.metric{background:var(--panel);border-radius:16px;padding:14px 16px;box-shadow:var(--shadow)}
.metric .n.small{font-size:17px;letter-spacing:0}
.metric .n{display:block;font-size:24px;font-weight:650;letter-spacing:-.4px;font-variant-numeric:tabular-nums}
.metric .l{color:var(--sub);font-size:12px}
.card{background:var(--panel);border-radius:18px;padding:16px 18px;box-shadow:var(--shadow);margin-bottom:14px}
.card h2{margin:0 0 12px;font-size:13px;font-weight:650;color:var(--sub);letter-spacing:.6px;text-transform:uppercase}
.split{display:grid;grid-template-columns:1fr 1fr;gap:14px}
.scroll{overflow:auto}
table{width:100%;border-collapse:collapse}
th,td{padding:9px 8px;text-align:left;border-bottom:1px solid var(--line);vertical-align:middle}
th{font-size:11.5px;font-weight:600;color:var(--sub);letter-spacing:.5px;text-transform:uppercase}
tbody tr:last-child td{border-bottom:none}
td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}
td.dim{color:var(--sub);font-size:12.5px;white-space:nowrap}
td.mono{font-variant-numeric:tabular-nums;font-size:12.5px;color:var(--sub)}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
a.sig{display:inline-flex;align-items:center;gap:8px;flex-wrap:wrap}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--sub)}
.chip{display:inline-flex;align-items:center;gap:6px;background:var(--panel-hi);border-radius:999px;
      padding:2px 9px 2px 7px;font-size:11.5px;color:var(--text);font-weight:600;letter-spacing:.3px}
.chip i{width:6px;height:6px;border-radius:50%;display:inline-block}
.badge{font-size:10px;font-weight:700;letter-spacing:.6px;padding:1px 6px;border-radius:6px}
.badge.new{background:rgba(192,112,95,.16);color:#C0705F}
.trend{display:flex;align-items:flex-end;gap:6px;height:130px;padding-top:6px;border-bottom:1px solid var(--line)}
.trend.compact{height:80px}
.trend .col{flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%;gap:6px}
.trend .track{flex:1;display:flex;align-items:flex-end;border-radius:6px 6px 0 0;overflow:hidden}
.trend .track i{display:block;width:100%;background:var(--accent);border-radius:5px 5px 0 0;min-height:2px}
.trend .track i.zero{background:var(--panel-hi)}
.trend .col span{font-size:10.5px;color:var(--sub);text-align:center;height:12px}
.bar-row{display:grid;grid-template-columns:minmax(72px,46%) 1fr auto;gap:10px;align-items:center;padding:5px 0}
.bar-row .k{font-size:13px;min-width:0}
.bar-row .k b{display:block;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bar-row .k em{display:block;font-style:normal;font-size:11px;color:var(--sub);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bar-row .track{height:7px;background:var(--panel-hi);border-radius:999px;overflow:hidden}
.bar-row .track i{display:block;height:100%;background:var(--accent);opacity:.85;border-radius:999px}
.bar-row .v{font-variant-numeric:tabular-nums;font-size:12.5px;color:var(--sub);min-width:2ch;text-align:right}
.foot{color:var(--sub);font-size:12px;margin:10px 0 0}
.empty{color:var(--sub);text-align:center;padding:22px 0;margin:0}
.chips{background:var(--panel);border-radius:14px;padding:9px 14px;margin-bottom:14px;font-size:13px;box-shadow:var(--shadow)}
.chips a{margin-left:8px}
.back{display:inline-block;margin-bottom:14px;font-size:13px}
.hero{display:flex;align-items:center;gap:12px;margin-bottom:14px;flex-wrap:wrap}
.hero .full{font-size:12.5px;background:var(--panel);padding:5px 10px;border-radius:8px;border:1px solid var(--line)}
details.sample{background:var(--panel);border-radius:14px;box-shadow:var(--shadow);margin-bottom:10px;overflow:hidden}
details.sample summary{cursor:pointer;padding:11px 14px;font-size:12.5px;color:var(--sub);list-style:none}
details.sample summary::-webkit-details-marker{display:none}
details.sample summary::before{content:"▸ ";color:var(--icon)}
details.sample[open] summary::before{content:"▾ "}
.sample-body{padding:0 14px 14px}
.sample-body h3{margin:14px 0 6px;font-size:11.5px;font-weight:650;color:var(--sub);letter-spacing:.5px;text-transform:uppercase}
.kv{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:2px 18px}
.kv div{display:flex;gap:8px;justify-content:space-between;border-bottom:1px dashed var(--line);padding:3px 0}
.kv span{color:var(--sub);font-size:12px;white-space:nowrap}
.kv b{font-weight:500;font-size:12px;text-align:right;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.timeline{display:flex;flex-wrap:wrap;gap:6px;padding:2px 0}
.crumb{display:inline-flex;align-items:center;gap:6px;background:var(--panel-hi);border-radius:8px;
       padding:3px 8px;font-size:11.5px;color:var(--text)}
.crumb em{font-style:normal;color:var(--sub);font-size:10.5px;text-transform:uppercase;letter-spacing:.4px}
.frames{margin:0;padding-left:20px;font-size:11.5px}
.frames li{margin:2px 0;display:flex;gap:10px;align-items:baseline;flex-wrap:wrap}
.frames li span{color:var(--sub);font-variant-numeric:tabular-nums}
.frames .cmd{margin-left:auto;color:var(--icon);opacity:.85;font-size:11px}
pre{background:var(--bg);padding:12px;border-radius:10px;overflow:auto;font-size:11.5px;line-height:1.55;
    max-height:46vh;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;margin:6px 0}
.stack{max-height:34vh}
details.raw{margin-top:12px}
details.raw summary{cursor:pointer;font-size:12px;color:var(--sub)}
@media (max-width:820px){
  .metrics{grid-template-columns:1fr 1fr}
}
@media (max-width:760px){
  .wrap{padding:20px 14px 48px}
  .split{grid-template-columns:1fr}
  td.dim{font-size:11.5px}
  .toolbar{gap:8px}
  .frames .cmd{display:none}
}
</style></head><body><div class="wrap">
<header class="top">
  <div class="brand"><span class="logo">${APP_ICON_SVG}</span>ReveriePaint <small>崩溃看板</small></div>
  <div class="controls">
    <nav class="range">${rangeNav(ctx)}</nav>
    <nav class="range">${themeNav(ctx)}</nav>
    <a class="logout" href="/t/v1/logout" title="退出登录">退出</a>
  </div>
</header>
${body}
</div></body></html>`;
}
