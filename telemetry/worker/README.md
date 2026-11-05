# ReveriePaint 崩溃遥测接收端

Cloudflare Worker + D1。数据契约见 [docs/TELEMETRY.md](../../docs/TELEMETRY.md) §7。

## 本地联调(不需要 Cloudflare 账号)

```bash
node telemetry/worker/dev-server.mjs
curl -s http://127.0.0.1:8787/t/v1/health
curl -s 'http://127.0.0.1:8787/t/v1/stats?days=7' | head
```

数据库落在 `telemetry/worker/.dev.sqlite`(已 gitignore),删掉即重置。

## 部署

```bash
cd telemetry/worker
npx wrangler d1 create reveriepaint_telemetry      # 把返回的 database_id 填进 wrangler.toml
npx wrangler d1 execute reveriepaint_telemetry --file=./schema.sql --remote
npx wrangler secret put INGEST_KEY                  # 上报写入凭据, 生成的随机串写进 CI/客户端
npx wrangler secret put STATS_KEY                   # 脚本只读凭据, 只给自己用, 不要进 APK
npx wrangler secret put STATS_PASSWORD               # 看板登录页的密码(短、可随时改)
# 可选: 告警出口(二选一或都配)
npx wrangler secret put ALERT_WEBHOOK_URL            # 飞书/钉钉群机器人 或 任意 JSON 地址
npx wrangler secret put ALERT_GITHUB_TOKEN           # 配合 ALERT_GITHUB_REPO 开 issue
npx wrangler deploy
```

绑定自定义域(或直接用 `*.workers.dev`):

```
Route: telemetry.<你的域名>/t/*
```

客户端构建时注入端点与 key:

```bash
./gradlew assembleRelease -PtelemetryEndpoint=https://telemetry.example.com -PtelemetryKey=$INGEST_KEY
```

不注入 → `BuildConfig.TELEMETRY_ENDPOINT` 为空 → 客户端整条链路 no-op。

## 全局停用(kill switch)

```bash
npx wrangler deploy --var DISABLED:1
```

客户端收到 `410` 后会在本机永久停用上报(标记 `server_disabled`),恢复到 `0` 也不会自动打开 ——
防止服务端抖动时反复横跳,重启上报需要用户重新打开开关。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/t/v1/crash` | 上报入口。`Content-Encoding: gzip` 可选,`x-rp-key` 校验 |
| `GET` | `/t/v1/stats?days=7` | 看板。固定公开链接 + 登录门(未登录给登录页);脚本用 `?key=` / `x-stats-key` 免登录 |
| `POST` | `/t/v1/stats` | 登录表单(`password` + `next`),成功后 303 + 一年期 Cookie |
| `GET` | `/t/v1/logout` | 清 Cookie 并跳回看板 |
| `GET` | `/t/v1/alerts/test` | 发测试通知;`?mode=scan` 立刻跑一次告警扫描 |
| `GET` | `/t/v1/symbols/<versionCode>/<file>` | 未 strip 的 `.so`(gzip 透传)与 manifest,需登录;由 `scripts/archive_symbols.sh --upload` 上传 |
| `GET` | `/t/v1/health` | 存活探针,回显 `disabled` 状态 |

## 看板

`/t/v1/stats` 是服务端渲染的只读页面(无 JS、无外部资源):每日趋势条形、按签名聚合(次数/设备数/末见版本)、
版本分布、机型分布;点机型或版本下钻筛选,点签名看最近 20 条原始样本。
功能:时间窗 7/14/30/90 天、类型筛选(Native/Java/ANR)、关键词搜索、机型/版本下钻、
NEW 徽章(全量首次出现 < 24h)、结构化现场(设备/上下文/操作轨迹时间线/帧 + addr2line 命令),
以及 `?format=csv` 与 `?format=md` 导出(沿用当前筛选,同样需要登录)。

### 设备名显示

遥测只存 `Build.MANUFACTURER / MODEL / DEVICE` 三个原始字段,国产 ROM 的 `MODEL` 常年只是型号代码
(`SM-X700` / `OPD2413` / `V2190A`),单看没法辨认。看板展示统一走 `src/devnames.js`:

- 厂商名中文化(`samsung` → 三星、`LENOVO` → 联想、`Microsoft Corporation` → Windows 上的安卓 WSA);
- **别名表**把型号/产品代号翻译成市场名(`SM-X700`/`gts8wifi` → 三星 Galaxy Tab S8);
- 机型分布与现场卡都渲染两行: 主标题(人话名字) + 副标题(`原始型号 · 产品代号 · 安卓版本`),
  下钻与导出仍用原始型号,语义不变;`?format=csv` 额外给一列 `device`(单行可读名)。

新增别名只写**核对过**的条目(model ↔ dev ↔ 市场名三者对得上, 来源是上报里的 `Build.FINGERPRINT`
产品代号 + 厂商公开型号), 猜错比只显示型号更糟。文件里的 `ALIASES` 同时接受产品代号和型号两种键。

主题默认跟随系统,页头可手动切换「自动 / 浅色 / 深色」(写进 `rp_theme` Cookie)。
图标直接内联仓库里的 `art/icon.svg`(白色星芒配 `#0e0f16` 底色,与应用图标一致)。

链接本身公开固定、不含密钥,访问者需要输入 `STATS_PASSWORD` 才能看数据(登录一次管一年,页头可退出)。
`STATS_KEY` / `INGEST_KEY` / `STATS_PASSWORD` 三者必须分开 —— 看板 key 会进浏览器历史与 Referer, 不能是能写入的凭据。
未配置 `STATS_KEY` 时会退回 `INGEST_KEY`(仅为兼容旧部署)。

## 告警

每小时 cron 判断:出现全新签名 → 推一次;老签名 1 小时 ≥5 次 → 推一次(同签名 6 小时冷却)。
出口通过 `ALERT_WEBHOOK_URL`(飞书/钉钉/通用 JSON)或 `ALERT_GITHUB_REPO`+`ALERT_GITHUB_TOKEN`(开 issue)配置。
没配出口时只维护 `known_sigs` 基线,不发通知。

## 配额

| 维度 | 上限 | 超限行为 |
|---|---|---|
| 单条体积 | 128 KB(gzip 前) | `413` |
| 每设备每日 | 5 条 | 回 `200 {dropped:"install_daily_cap"}`,客户端记成功不再重试 |
| 每设备每签名每日 | 3 条 | 回 `200 {dropped:"sig_daily_cap"}` |

## 隐私

不读 `CF-Connecting-IP`,不写 IP 或 IP 哈希;`install` 字段只用于配额与去重,不出现在 `stats` 页面上
(页面只展示聚合数与机型分布)。Zone 默认分析建议保持关闭。
