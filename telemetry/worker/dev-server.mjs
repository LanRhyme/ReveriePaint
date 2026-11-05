// SPDX-License-Identifier: GPL-3.0-or-later
//
// 本地联调服务器: 用 Node 内置 http + node:sqlite 把 Worker 模块原样跑起来。
// 与 Cloudflare 上的代码路径完全一致(同一个 src/index.js), 只是 DB 换成本地 sqlite。
//
//   node telemetry/worker/dev-server.mjs            # 监听 127.0.0.1:8787
//   PORT=9000 node telemetry/worker/dev-server.mjs
//
// 客户端联调: ./gradlew assembleDebug -PtelemetryEndpoint=http://10.0.2.2:8787
//   (真机用电脑局域网 IP; 模拟器用 10.0.2.2)

import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import worker from './src/index.js';

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT ?? 8787);
const dbPath = process.env.DB_PATH ?? join(here, '.dev.sqlite');

const db = new DatabaseSync(dbPath);
db.exec(readFileSync(join(here, 'schema.sql'), 'utf8'));

/** 只实现 Worker 里实际用到的那一小撮 D1 API */
function makeD1(database) {
  return {
    prepare(sql) {
      const params = [];
      const stmt = {
        bind(...args) {
          params.push(...args);
          return stmt;
        },
        async first() {
          return database.prepare(sql).get(...params) ?? null;
        },
        async all() {
          return { results: database.prepare(sql).all(...params) };
        },
        async run() {
          const info = database.prepare(sql).run(...params);
          return { success: true, meta: { changes: Number(info.changes ?? 0) } };
        },
      };
      return stmt;
    },
  };
}

const env = {
  DB: makeD1(db),
  INGEST_KEY: process.env.INGEST_KEY ?? '',
  STATS_KEY: process.env.STATS_KEY ?? '',
  STATS_PASSWORD: process.env.STATS_PASSWORD ?? '',
  DISABLED: process.env.DISABLED ?? '0',
  ALERT_WEBHOOK_URL: process.env.ALERT_WEBHOOK_URL ?? '',
  ALERT_GITHUB_REPO: process.env.ALERT_GITHUB_REPO ?? '',
  ALERT_GITHUB_TOKEN: process.env.ALERT_GITHUB_TOKEN ?? '',
};

async function readRequestBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks);
}

const server = createServer(async (req, res) => {
  try {
    const body = await readRequestBody(req);
    const request = new Request(new URL(req.url, `http://${req.headers.host ?? '127.0.0.1'}`), {
      method: req.method,
      headers: req.headers,
      body: body.length > 0 && req.method !== 'GET' && req.method !== 'HEAD' ? body : undefined,
    });
    const response = await worker.fetch(request, env, {});
    const payload = Buffer.from(await response.arrayBuffer());
    // Set-Cookie 必须逐条发: 直接 Object.fromEntries 会把多条合并成一个头, 浏览器解析不了
    const headers = {};
    for (const [k, v] of response.headers) {
      if (k.toLowerCase() !== 'set-cookie') headers[k] = v;
    }
    const cookies = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
    if (cookies.length > 0) headers['set-cookie'] = cookies;
    res.writeHead(response.status, headers);
    res.end(payload);
    const kind = response.headers.get('content-type') ?? '';
    console.log(`${req.method} ${req.url} -> ${response.status} ${kind.includes('html') ? '[html]' : ''}`);
  } catch (err) {
    console.error('dev-server error:', err);
    res.writeHead(500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: false, error: 'dev_server_error' }));
  }
});

server.listen(port, '0.0.0.0', () => {
  console.log(`telemetry dev server: http://127.0.0.1:${port}/t/v1/health  (db=${dbPath})`);
  if (!env.INGEST_KEY) console.log('INGEST_KEY 未设置: 不校验 x-rp-key(仅本地联调建议如此)');
});
