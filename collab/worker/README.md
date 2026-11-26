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

## 测试

端到端用例会自行拉起本地 `wrangler dev` (随机固定端口), 用真实 WebSocket 覆盖建房 / 入房门禁 / 审批 / 信令转发 / 房主在线与离线迁移 / 大厅目录 / 过期与占位清理

```bash
cd collab/worker
npm test
```

- Node >= 20 (依赖内置 `fetch` 与 `WebSocket`)
- 首次运行会用 `npx` 拉取 `wrangler`, 无本地 node_modules 也可跑
- 时间相关用例通过 `ROOM_TTL_MS` / `GHOST_PEER_TTL_MS` 变量压到数秒, 全套约 50 秒

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/c/v1/health` | 存活与版本探针, 返回 `disabled` 与 `turnConfigured` 状态 |
| `GET` | `/c/v1/ice` | 下发当前可用的 STUN / 可选 TURN 服务器列表 |
| `GET` | `/c/v1/rooms` | 查询当前在线的公开茶绘房间列表 |
| `POST` | `/c/v1/rooms` | 创建房间, 分配 6 位防混淆房间码并签发 `hostToken` (`isPublic` 为真时 `title` 必填) |
| `GET` | `/c/v1/rooms/:code/info` | 查询房间状态, 密码/审批门禁与画布信息 |
| `GET` | `/c/v1/rooms/:code/ws` | WebSocket 信令连接 (`peerId`, `role=host\|peer`, `token`, `name`, `pw`) |

## 房间生命周期与容错

- 房间 TTL 默认 24 小时, 有活跃连接时随信令活动顺延, 上限 7 天 (`ROOM_TTL_MS` 可调)
- 全员离线超过 `IDLE_TTL_MS` (默认 30 分钟) 或到达 TTL 上限时, 房间关闭并回收存储, 客户端收到 `4004 expired`
- 房主退房时如果接任者恰好掉线, 服务端记录待认证接任者, 该成员以 `peer` 身份重连后自动晋升并补发 `hostToken`
- 异常掉线未发 `leave` 的成员超过 `GHOST_PEER_TTL_MS` (默认 10 分钟) 后惰性清理, 释放昵称与虚拟 IP 占位
- 公开房间大厅按展示字段变化写入, 相同状态下每 5 分钟最多刷新一次, 避免每个信令都触发一次目录写库

## 可选 TURN 注入

未绑卡时默认仅下发免费公共 STUN 列表, 如后续自建 `coturn` 或开启 Cloudflare TURN, 可通过变量或 Secret 热注入而无需改客户端:

```bash
npx wrangler secret put TURN_SHARED_SECRET
npx wrangler deploy --var TURN_URLS:"turn:turn.lanrhyme.top:3478?transport=udp"
```

## 自建与社区 EasyTier 中继节点

如需部署专属或社区贡献的 EasyTier 中继节点并通过 `RELAY_PEERS` 热下发给全部客户端, 请参阅 [docs/COLLAB-RELAY-CONTRIBUTE.md](../../docs/COLLAB-RELAY-CONTRIBUTE.md)
