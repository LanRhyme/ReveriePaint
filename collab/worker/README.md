# ReveriePaint P2P 茶绘信令端

基于 Cloudflare Workers + SQLite Durable Objects (WebSocket Hibernation API), 仅负责房间码匹配, 入房校验与 WebRTC SDP/ICE 交换, 不转发任何画布像素或笔画流量

设计文档见 [COLLAB-PAINTING-PLAN.md](../../docs/COLLAB-PAINTING-PLAN.md)

## 部署

```bash
cd collab/worker
npx wrangler deploy
```

配置中已绑定自定义域名 `reverieteapaint.lanrhyme.top`, 首次部署将自动在对应 Cloudflare Zone 下创建 proxied DNS 记录与 TLS 证书

## 本地联调

```bash
cd collab/worker
npx wrangler dev
curl -s http://127.0.0.1:8787/c/v1/health
```

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/c/v1/health` | 存活与版本探针, 返回 `disabled` 与 `turnConfigured` 状态 |
| `GET` | `/c/v1/ice` | 下发当前可用的 STUN / 可选 TURN 服务器列表 |
| `GET` | `/c/v1/rooms` | 查询当前在线的公开茶绘房间列表 |
| `POST` | `/c/v1/rooms` | 创建房间, 分配 6 位防混淆房间码并签发 `hostToken` (`isPublic` 为真时 `title` 必填) |
| `GET` | `/c/v1/rooms/:code/info` | 查询房间状态, 密码/审批门禁与画布信息 |
| `GET` | `/c/v1/rooms/:code/ws` | WebSocket 信令连接 (`peerId`, `role=host\|peer`, `token`, `name`, `pw`) |

## 可选 TURN 注入

未绑卡时默认仅下发免费公共 STUN 列表, 如后续自建 `coturn` 或开启 Cloudflare TURN, 可通过变量或 Secret 热注入而无需改客户端:

```bash
npx wrangler secret put TURN_SHARED_SECRET
npx wrangler deploy --var TURN_URLS:"turn:turn.lanrhyme.top:3478?transport=udp"
```
