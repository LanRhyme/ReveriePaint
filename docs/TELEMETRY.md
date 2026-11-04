# 崩溃遥测 (Crash Telemetry) 设计与数据契约

> 状态: v1 代码完成(端点未部署) · 最后更新 2026-10-30
> 相关文档: `docs/PRIVACY.md`(面向用户的隐私说明) · `AGENTS.md` §4(架构铁律) §12(安全与机密)
>
> 已完成: 客户端 `core/telemetry/` 全链路、设置开关、首启告知条、崩溃弹窗手动上报、
> Worker + D1 + 本地 dev-server、native 帧偏移改造、20 项单测、契约端到端验证;
> v1.6.5 起端点已上线, 并补上主线程卡顿看门狗(见 §5.4)。
> 待办: 部署 Worker 与 D1 并注入 `TELEMETRY_ENDPOINT`/`TELEMETRY_KEY`; `-PbuildNative` 真机回归 C++ 帧格式;
> 符号化流水线(见 §11)。

## 1. 目标与非目标

**目标**: 让"用户崩了"这件事在维护者这边有信息。具体只回答三个问题:

1. 崩在哪个功能上(native 崩溃尤其需要,因为信号段错误在 Kotlin 侧完全不可见)
2. 崩在什么设备/系统/版本/ABI 组合上(机型矩阵是当前最缺的一块)
3. 同一类崩溃有多少人在撞(去重签名 + 聚合计数,决定修复优先级)

**非目标**:

- 不做行为统计、漏斗、留存、DAU。这些一律不采(见 §4 禁止清单)
- 不做 A/B、不做远程配置、不做灰度开关下发
- 不上传任何画作内容、图像、缩略图、工程文件

## 2. 现状: 采集层已经存在,缺的只是"出口"

| 已有能力 | 位置 |
|---|---|
| Java 未捕获异常 → 完整报告(设备/内存/画布状态/面包屑/堆栈/logcat) | `core/CrashHandler.kt` `buildCrashReport` |
| Native `SIGSEGV/SIGABRT/SIGBUS` → 备用栈 + 寄存器 + `dladdr` 回溯 | `cpp/ReverieCrashHandler.cpp` |
| 面包屑环形缓冲(Java 100 条 / Native 静态数组,双写) | `core/Breadcrumbs.kt` + `addNativeBreadcrumb` |
| logcat 凭据脱敏 | `core/DiagnosticsManager.kt` `sanitize` |
| 日志轮转、复制/导出/分享、下次启动弹窗 | `core/CrashHandler.kt` + `ui/dialog/CrashReportDialog.kt` |
| 崩溃后抢救草稿、前缓冲按次安装隔离 | `emergencySaveOnCrash` / `evaluateFrontBufferQuarantine` |

所以 v1 **不重造捕获层**,只加一条从 `crash_logs/` 到一个 HTTP 端点的单向通路。

### 2.1 必须先补的两个缺口

**(a) native 帧当前不可符号化**

`ReverieCrashHandler.cpp` 输出的是 `pc` 绝对地址 + `dladdr` 的 `dli_sname`。stripped `.so` 里绝大多数帧没有符号,只剩一个被 ASLR 随机化的绝对地址,同一份代码每次崩溃都不一样,事后无法 `addr2line`。修复:每一帧都额外附带 `模块基址相对偏移`(`pc - dli_fbase`),符号名退化成可选尾注:

```
#03 pc 0x7f8a1c2b3c  +0x1a2b3c  /data/app/…/lib/arm64/libreverie_jni.so
#04 pc 0x7f8a9c2b90  +0x2f19c  /data/app/…/lib/arm64/libQt6Core_arm64-v8a.so  (QString::fromLatin1+20)
```

解析侧对新旧两种格式都兼容(`TelemetryPayload.NATIVE_FRAME_NEW/NATIVE_FRAME_OLD`),老日志里 `(符号+偏移)` 这种符号相对偏移不会被误当成模块偏移。

**验证方式(不需要真机)**:`ReverieCrashHandler.cpp` 只有 `<android/log.h>` 一个 Android 依赖,给它一个空桩就能编成本地可执行文件,在宿主机上制造真实段错误:

```bash
# 见 app/src/test/resources/telemetry/crash_native_host.log 这份真实产物
mkdir -p /tmp/ch/inc/android && printf '#pragma once\n#define ANDROID_LOG_FATAL 7\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_ERROR 6\n#define ANDROID_LOG_DEBUG 3\nstatic inline int __android_log_print(int,const char*,const char*,...){return 0;}\n' > /tmp/ch/inc/android/log.h
g++ -std=c++17 -g -O0 -I/tmp/ch/inc app/src/main/cpp/ReverieCrashHandler.cpp 你的harness.cpp -o /tmp/ch/crashhost -ldl
/tmp/ch/crashhost            # 会 SIGSEGV 并写日志
addr2line -f -C -e /tmp/ch/crashhost 0x<帧偏移>
```

`addr2line` 能还出 `render_frame(int)` 即证明"模块+偏移"真的可符号化;两次运行绝对 `pc` 不同而偏移一致,即证明 ASLR 不再是障碍。

**(c) native 面包屑补上时间戳**

原先 native 侧写的是 `- Brush: Load preset [3]: <笔刷名>`,没有时间,无法与 logcat / Java 面包屑对齐时间线。现在写成 `- [14:40:54] Brush: …`(UTC,信号处理器里不做时区换算)。解析侧三种格式都吃:Java 的 `[HH:MM:SS.SSS] [Cat] msg`、新的 `[HH:MM:SS] Cat: msg`、旧的裸 `Cat: msg`。

**(b) 面包屑里混着用户内容**

现有 15 个调用点中 5 个插值了用户数据(笔刷预设名、画作名)。本地留存无妨(是用户自己点分享的),但**上传路径不得复用 `dump()` 的原文**,必须走 §5.3 的白名单规约。

**(d) native 快照里的"在干什么"**

原生崩溃处理器读的是一块静态缓冲区,原本只在 `CrashHandler.init` 里灌一次设备信息,
所以 `native_crash` 上报的画布尺寸/图层数永远是 0。现在:

- `NativeCrashContext.attach()` 启动时灌一次基线
- `Breadcrumbs.record()` 里顺带触发 `requestRefresh()` —— 因为现有的 15 个埋点恰好覆盖了
  切工具、加删图层、打开工程、切页面这些关键操作
- 1.5 秒限流 + 内容不变不重复写;刷新统一切到主线程做(避免跨线程读 Compose 状态)
- `MainActivity` 在 ViewModel 创建后补一次刷新,覆盖"冷启动几秒内就崩"的情况

## 3. 数据流

```
崩溃发生
  ├─ Java 崩溃   → CrashHandler 写 crash_java_<ts>.log
  └─ Native 信号 → ReverieCrashHandler 写 crash_native_<ts>.log
                        ↓ (下次启动 30s 后自动; 关掉自动上报时弹窗才有"仅上报这一条")
TelemetryReporter.scan()  扫描 crash_logs/,跳过已上报水位
                        ↓
TelemetryPayload.build()  解析 → 白名单规约 → 脱敏 → 组装 JSON
                        ↓                        └─ CrashSignature.sign()
gzip → POST {TELEMETRY_ENDPOINT}/t/v1/crash
                        ↓
Cloudflare Worker         校验 key / 配额 / 去重 → D1
                        ↓
GET /t/v1/stats?days=7    按签名聚合的简表
```

ANR 走另一条支路:启动时读 `ActivityManager.getHistoricalProcessExitReasons()`(API 30+),取 `REASON_ANR` 且晚于上次水位的条目,`getTraceInputStream()` 作为堆栈。

## 4. 禁止清单(永不采集)

以下字段一旦出现在上报体里即为缺陷,应在代码评审中直接打回:

- 任何文件路径与文件名(`.revp` / `.png` / `.kra` / 导出目录),日志中一律规约为 `*.<ext>`
- 任何用户自命名的字符串: 笔刷预设名、图层名、画集名、工程名
- 参考图、缩略图、画布像素、选区、图层像素
- WebDAV 服务器地址、用户名、密码、Token、Cookie、`Authorization` 头
- 爱发电 / Mirror酱 / GitHub 相关凭据
- `ANDROID_ID`、IMEI、序列号、MAC、手机号、邮箱
- 精确位置(不请求任何定位权限,也不从系统服务读)
- 剪贴板内容、输入法内容、已安装应用列表
- 客户端 IP(服务端不落库;见 §7.3)

## 5. 数据契约 v1

### 5.1 外层信封

```jsonc
{
  "v": 1,                                  // 契约版本, 不兼容时 +1
  "kind": "java_crash",                    // java_crash | native_crash | anr
  "install": "9f2c1a3e-…",                 // 随机 UUID, 首次启动生成, 存 prefs
  "at": 1761700000,                        // 崩溃发生时间(秒)
  "sig": "b41c…",                          // 去重签名, 见 §6
  "app": { "ver": "1.6.1", "code": 48, "abi": "arm64-v8a" },
  "dev": { … },                            // §5.2
  "doc": { … },                            // §5.3
  "crumbs": [ … ],                         // §5.4
  "stack": "…",                            // 崩溃线程堆栈(已脱敏)
  "logcat": "…",                           // ≤100 行, 已脱敏
  "native": { … }                          // 仅 native_crash
}
```

### 5.2 `dev` 字段

| 字段 | 来源 | 用途 |
|---|---|---|
| `mfr` / `model` / `dev` | `Build.MANUFACTURER/MODEL/DEVICE` | 机型矩阵,判断某 ROM/机型是否共性问题 |
| `sdk` / `rel` | `Build.VERSION.SDK_INT/RELEASE` | 版本差异 |
| `fp` | `Build.FINGERPRINT` | 区分同型号不同固件 |
| `ram_mb` | `ActivityManager.MemoryInfo.totalMem` | 低内存机型崩溃归因 |
| `harmony` | 现有 `detectHarmonyOsVersion()` | 鸿蒙/EMUI 兼容性 |

### 5.3 `doc` 字段(应用状态快照)

| 字段 | 来源 | 用途 |
|---|---|---|
| `w` / `h` | 当前文档宽高 | 大画布内存压力 |
| `layers` | 图层数 | 复杂度归因 |
| `page` | `Page` 枚举名 | 崩在哪个页面 |
| `tool` | 当前工具 id | 崩在哪个工具 |
| `stylus` | 手写笔型号标识 | 手写笔适配问题归因 |

> 这几项是"数值型上下文",不含任何用户内容。取舍记录: 曾考虑完全不上报数值以求最保守,但那会让绝大多数崩溃退化为"不知道在什么规模上崩的",归因效率减半,故保留并在 `docs/PRIVACY.md` 中逐条列明。
>
> `native_crash` 的 `doc` 现在也会带上:原生侧的状态快照不再只在启动时灌一次,而是由
> `NativeCrashContext` 在用户操作后刷新(见 §2.1(d))。冷启动后还没打开文档就崩溃时,
> 只有页面/工具,没有画布尺寸与图层数 —— 这是预期行为。

### 5.4 `crumbs`(面包屑白名单)

最多保留**末 32 条**,每条只有 `{ "t": 相对毫秒偏移, "c": 类别, "e": 动作标签 }`,**不含消息尾巴**。

规约方式: 取 `category` 与人可读消息的"动词片段"做映射,命中下表才通过,未命中的调用点一律记 `{"c": <category>, "e": "other"}`。

| 原调用 | 上报的 `c/e` |
|---|---|
| `Brush` / `Load preset [..]: <名>` | `Brush` / `load_preset` |
| `Brush` / `Restore preset [..]: <名>` | `Brush` / `restore_preset` |
| `Save` / `saveProject ..` | `Save` / `save_project` |
| `IO` / `loadProject ..` | `IO` / `load_project` |
| `Layer` / `Add layer: <名>` | `Layer` / `add` |
| `Layer` / `Remove layer: <i>` | `Layer` / `remove` |
| `Layer` / `Set current layer: <i>` | `Layer` / `select` |
| `Tool` / `Switch tool to: <id>` | `Tool` / `switch` |
| `History` / `Undo` / `Redo` | `History` / `undo` `redo` |
| `Navigation` / `Current page: <page>` | `Navigation` / `page` |
| `FrontBuffer` / `quarantined …` | `FrontBuffer` / `quarantined` |
| `MainThread` / `stall <ms>ms <类.方法>` (看门狗) | `MainThread` / `stall:<ms>ms:<类.方法>` |

> 这张表是**白名单**:新增 `Breadcrumbs.record` 若没在表里登记,上报侧只会得到 `other`,不会静默带出用户数据。

**ANR 的面包屑来自下一次启动 —— 主线程卡点由看门狗补齐**

ANR 是系统 kill 整个进程,命中后本进程的面包屑随进程一起消失;`TelemetryReporter.uploadAnrs()`
能读到的 `Breadcrumbs.dump()` 全都是**重启之后**新会话的记录,对定位没有价值。

为此加了 `core/telemetry/MainThreadWatchdog.kt`:主线程每 500ms 打卡,后台线程发现打卡停了
≥2.5s,就把主线程栈里**第一条应用代码帧**记成一条面包屑,并**同时落盘**(`revp_anr_diag` prefs):

```
[HH:MM:SS.mmm] [MainThread] stall 4200ms canvas.CanvasTouchView.onTouchEvent
```

上报 ANR 时按「ANR 时刻 −10min ~ +1min」的时间窗把它取出来拼进 `crumbs`。这样即使系统
tombstone 转储失败(线上常见 `failed to read status response from tombstoned`),报告里仍然
能看出主线程最后卡在哪个函数。帧串只含应用自己的类名/方法名,不含文件路径、行号与任何用户内容。

### 5.5 `native` 字段

```jsonc
{ "signal": "SIGSEGV", "code": 1, "fault": "0x0",
  "frames": ["libreverie_jni.so+0x1a2b3c", "libQt6Core_arm64-v8a.so+0x2f19c"] }
```

- 只保留 `模块名+偏移`,不保留绝对 `pc` 与寄存器(绝对地址无诊断价值且是随机数)
- 最多 24 帧(48 帧截半,体积优先)

## 6. 签名与去重

```
sig = sha256(kind + "|" + app.code + "|" + 异常类名 + "|" + 前 3 个帧的 "模块+偏移")
```

- Java: 异常类名 + 首个 app 帧(`com.reverie.*`)
- Native: signal + 前 3 个非系统库帧
- 不含时间、不含地址、不含设备 → 同一处代码同一个错,签名稳定

服务端按 `sig` 聚合:"这一类崩了多少次 / 多少人 / 首末见到版本",这就是修复优先级的唯一依据。

## 7. 服务端(Cloudflare Worker + D1)

### 7.1 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/t/v1/crash` | 上报入口,`Content-Type: application/json`,`Content-Encoding: gzip` |
| `GET` | `/t/v1/stats?days=7` | 看板:固定公开链接 + 登录门(`STATS_PASSWORD`);未登录给登录页,脚本用 `?key=` 或 `x-stats-key` 免登录 |
| `GET` | `/t/v1/stats?format=csv` / `format=md` | 按当前筛选导出逐条 CSV 或按签名汇总的 Markdown |
| `GET` | `/t/v1/alerts/test` | 发一条测试通知到已配置的出口(`?mode=scan` 则立刻跑一次扫描并返回 new/spike 计数) |
| `GET` | `/t/v1/health` | 存活探针,回显 `disabled` 状态 |

保留期 180 天,由 `scheduled` 定时任务每日删除(`wrangler.toml` 的 `[triggers]`)。

**完全固定的链接**:`https://telemetry.lanrhyme.top/t/v1/stats` —— 不含任何密钥,谁拿到都能打开,
但看到的是登录页(输入 `STATS_PASSWORD`),登录后种一年期 `rp_stats` Cookie 直接进看板,页头有「退出」。

- Cookie 里放的是 `sha256("revp-stats-v1|"+密码)` 而不是密码本身(HttpOnly / Secure / SameSite=Lax),每次访问滑动续期一年
- 登录失败统一 `401` + 同一条错误提示,并延迟 600 ms 抬高爆破成本;a 登录后 `next` 参数只允许跳回本站看板路径(防开放重定向)
- 脚本/CI 仍可用 `?key=<STATS_KEY>` 或 `x-stats-key` 头直接取数据,不必走登录
- 浏览器(带 `Accept: text/html`)未登录给登录页;其它客户端给 `401 JSON`,避免把 HTML 当 JSON 解析

### 7.2 D1 schema

```sql
CREATE TABLE crashes (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  sig       TEXT    NOT NULL,
  kind      TEXT    NOT NULL,
  at        INTEGER NOT NULL,   -- 崩溃时刻
  received  INTEGER NOT NULL,   -- 收件时刻
  app_code  INTEGER NOT NULL,
  app_ver   TEXT    NOT NULL,
  abi       TEXT,
  sdk       INTEGER,
  model     TEXT,
  install   TEXT,               -- 仅用于配额与去重, 不参与展示
  payload   TEXT    NOT NULL    -- 原始 JSON(已脱敏)
);
CREATE INDEX idx_crashes_sig  ON crashes(sig, at);
CREATE INDEX idx_crashes_inst ON crashes(install, received);
```

### 7.3 不记录 IP

Worker 不读 `CF-Connecting-IP`,不写任何 IP 或 IP 哈希。滥用防护靠 `install` 维度的配额(§8)。Zone 侧日志保留策略需单独确认(默认 Cloudflare 分析关闭即不落 IP 日志)。

### 7.4 已部署资源(2026-10-30)

| 项 | 值 |
|---|---|
| Worker | `reveriepaint-telemetry` |
| 对外端点 | `https://telemetry.lanrhyme.top`(**自定义域,必需**) |
| D1 | `reveriepaint_telemetry` · `f183220b-73ca-4f85-945a-4af05c77a8ab`(region APAC) |
| 定时任务 | `0 * * * *` 告警扫描 · `17 3 * * *` 保留期清理 |
| 密钥 | `INGEST_KEY`(写入,客户端用 `-PtelemetryKey` / `TELEMETRY_KEY` 注入) · `STATS_KEY`(脚本只读) · `STATS_PASSWORD`(登录页密码,可随时改) · `ALERT_WEBHOOK_URL` / `ALERT_GITHUB_TOKEN` + `ALERT_GITHUB_REPO`(告警出口,可选) |

> **为什么必须走自定义域**: 真机实测 `*.workers.dev` 在国内移动网络下 DNS 被污染,连接直接吊死成
> `SocketTimeoutException`;换成 `telemetry.lanrhyme.top` 后同一份 APK 立刻 200。
> 一个推论: `wrangler.toml` 的 `routes` 必须写在任何 `[table]` 之前,否则会被解析成上一个 table 的字段而**静默不生效**。

### 7.5 看板

纯服务端渲染(无 JS、无外部资源、无构建步骤),配色与 App 的 Morandi 主题同源(`ui/theme/Theme.kt` 的
`bg/panel/accent/text/subText`)。

- **深色模式**:默认跟随系统(`prefers-color-scheme`),另有「自动 / 浅色 / 深色」手动切换,
  选择写进 `rp_theme` Cookie(一年),显式选择会压过系统偏好
- **图标**:内联 `art/icon.svg`(与应用图标同源),垫一层 `#0e0f16` —— 图标里的白色星芒是给深色底设计的,
  直接放在浅色卡片上会消失
- **筛选**:时间窗 7/14/30/90 天、崩溃类型(Native/Java/ANR)、关键词搜索(签名/机型/版本)、
  机型与版本点选下钻;所有筛选条件在页内链接与导出里保持一致
- **NEW 徽章**:判定用"全量首次出现时间"而不是"窗口内最早一次"——否则上周就崩过的签名
  进到 7 天窗口里也会被误标成新问题
- **结构化现场**:每个签名详情不再只给一段 JSON,而是拆成设备(机型/代号/系统/内存/ABI/指纹)、
  崩溃时上下文(页面/工具/画布/图层/手写笔)、操作轨迹时间线、原生信号与故障地址、
  调用栈(模块相对偏移 + 每帧可直接复制的 `llvm-addr2line` 命令)、原始 JSON 折叠
- **导出**:`?format=csv`(逐条,含页面/工具/画布/图层/信号)与 `?format=md`(按签名汇总,
  适合贴 issue 或周报),都沿用当前筛选;未登录同样被登录门拦住
- **相对时间**:列表里用"12 小时前"这类表述,悬停可看绝对时间

主题与「书签固定」共用同一套重定向:带 `?theme=` 或 `?key=` 的请求都会先落 Cookie,再 302 回干净 URL。

### 7.6 告警

每小时 cron 在最近 1 小时的窗口里判断两件事:

| 条件 | 行为 |
|---|---|
| 出现**从未见过的签名** | 立刻推送一次,并把该签名写进 `known_sigs` 基线 |
| 老签名 1 小时内 **≥5 次** | 推送一次,同签名 6 小时内冷却,不重复轰炸 |

出口按 secret 自动启用,可同时配多个:

- `ALERT_WEBHOOK_URL` —— 群机器人。识别 `open.feishu.cn` / `larksuite.com`(飞书)与
  `oapi.dingtalk.com`(钉钉)的格式,其它地址收到 `{title, text, markdown}` 的通用 JSON
- `ALERT_GITHUB_REPO` + `ALERT_GITHUB_TOKEN` —— 直接在仓库开 issue(标题带 `[crash]`)

没配任何出口时**仍然维护基线**:不会发通知,但也不会在之后配好时把历史签名当成"新的"再炸一遍。
代价是出口故障期间的告警会丢,不会补发(宁可少一条,不要一次补几百条)。

## 8. 配额、退避与 kill switch

| 机制 | 规则 |
|---|---|
| 每设备每日 | 最多 5 条,超出的直接丢弃(仍返回 200,客户端不重试) |
| 每设备每签名每日 | 最多 3 条 |
| 单条体积 | 典型 8–20 KB;硬上限 128 KB(gzip 后),超出先砍 logcat 再砍堆栈尾部 |
| 网络失败 | 退避 1h → 6h → 24h,累计 3 次放弃该条(不删除本地日志) |
| 服务端拒绝 | `401/403/410` → 客户端本机永久停用(标记 `server_disabled`) |
| 手动关停 | Worker 返回 `410 Gone` 即全局停用,不必发版 |

## 9. 用户可见的开关与告知

- **默认开启**(崩溃现场是排障必需品,与 Firebase/Crashlytics 的行业惯例一致)
- 首次启动在主页顶部显示**一次性告知条**(非模态、不阻断),点"了解详情"进 `docs/PRIVACY.md` 对应页面
- 设置 → 隐私与诊断 → "崩溃报告自动上报 `SettingSwitchGroupItem`",关闭后: 清空待上报队列、删除 installId、不再请求网络
- 关闭状态持久化在 `SharedPreferences("reverie_telemetry")`
- **纯净构建**: `-Ptelemetry=false` 或环境变量留空 → `BuildConfig.TELEMETRY_ENDPOINT=""`,整条链路编译期短路为 no-op(便于第三方再分发或自建渠道)

## 10. 验证清单

1. `./gradlew :app:compileDebugKotlin`
2. `./gradlew :app:testDebugUnitTest` — 覆盖脱敏、白名单映射、签名稳定性、体积裁剪
3. C++ 无需真机: 用 §2.1 的宿主机 harness 编出可执行文件,制造真实段错误,再用 `addr2line` 反查帧偏移
4. 本地 mock: `PORT=18787 node telemetry/worker/dev-server.mjs`,`-PtelemetryEndpoint=http://<局域网IP>:18787` 构建,`adb logcat -s ReverieTelemetry` 看回执
5. **真机端到端**(实测通过的做法):
   ```bash
   adb -s <serial> shell pm clear com.reverie.paint      # 清掉退避状态与 installId
   adb -s <serial> shell am start -n com.reverie.paint/.MainActivity
   PID=$(adb -s <serial> shell pidof com.reverie.paint | tr -d '\r')
   adb -s <serial> shell run-as com.reverie.paint kill -11 $PID   # 真 native 崩溃
   adb -s <serial> shell am start -n com.reverie.paint/.MainActivity
   adb -s <serial> logcat -d | grep ReverieTelemetry              # 期望 uploaded … (200)
   ```
   然后 `wrangler d1 execute reveriepaint_telemetry --remote --command "SELECT …"` 核对落库字段。
6. C++ 改动(帧偏移)需 `-PbuildNative` 重新打进 APK 并确认包内 `libreverie_core.so` 与本地构建产物一致
   (`llvm-strip --strip-unneeded` 后比 md5),再按上面第 5 条跑一遍

## 11. 未做(后续版本再议)

- **符号化的自动化**: 归档/上传已经脚本化(§11.1),但还需要发版前手动跑一次;
  官方 Release 用的仍是 `third_party` 里已 strip 的预编译库,所以符号只能来自本地构建
- 功能采用率埋点(需要用户显式 opt-in,与崩溃遥测不是同一个开关)
- 性能画像(帧率/笔画延迟矩阵),设计见 `docs/RENDER-OPTIMIZATION.md`,是否上报单独评审
- 多维度看板(现在只有一张聚合简表)


### 11.1 符号化一个崩溃

看板里的帧是 `模块+偏移`,把任意一条崩溃日志丢给脚本即可还原:

```bash
# 1) 构建出未 strip 的库之后, 按 versionCode 归档(只需做一次)
./scripts/archive_symbols.sh 48
#   -> ~/.cache/revp-symbols/48/{libreverie_core.so, libreverie_jni.so, manifest.txt}

# 2) 符号化: 指定版本就从归档里取, 不指定则用构建目录里最新的那份
./scripts/symbolize_crash.sh --version 48 /path/to/crash_native_20261030_145449.log
# libreverie_core.so +0x3d07c4 -> capture_backtrace(void**, unsigned long)
#                                 app/src/main/cpp/ReverieCrashHandler.cpp:162
```

- 直接在详情页复制 `llvm-addr2line -f -C -e <模块> 0x<偏移>` 也行,脚本只是帮你批量做
- 归档可以推到线上(`archive_symbols.sh 48 --upload`),之后在任何机器上
  `REVP_STATS_KEY=... ./scripts/symbolize_crash.sh --version 48 <log>` 会自动从
  Worker 的按键归档里取回(§7.4 的 `SYMBOLS` KV, 走登录鉴权)
- **前提是有一份未 strip 的 `.so`**,并且它的源码版本与崩溃的 `versionCode` 对得上。
  脚本默认在 `app/build/intermediates/cxx/Release/*/obj/arm64-v8a/` 里找最新的同名库
  (即本地 `-PbuildNative` 的产物)
- 官方 Release 走预编译库, 进程内库已被 strip, 符号化会得到 `??` —— 所以**发版时的产物
  建议按 versionCode 归档一份未 strip 的 `libreverie_core.so`**,否则线上崩溃只能看到偏移
- `const char*` 级别的静态函数可能被内联/优化掉, 看到 `??` 不代表崩溃点不在那一帧

## 12. 踩过的坑

按踩到的顺序记录,避免重犯:

1. **`(?U)` 在 Android 上会炸**。`TelemetryScrubber` 最初用 `(?U)[\w\-.]` 匹配中日韩文件名,JVM 单测全绿,
   真机上抛 `PatternSyntaxException` → `ExceptionInInitializerError` → 整条上报链静默失效。
   Android 的 `java.util.regex` 不支持 JDK 的 `UNICODE_CHARACTER_CLASS` 内联标志,改用 `[\p{L}\p{N}_\-.]`。
   **教训**: 涉及 `java.util.regex` 的代码,JVM 单测通过不等于真机通过。
2. **只打异常类名会掩盖根因**。第一版 `Log.w(TAG, "… failed: ${it.javaClass.simpleName}")` 把 `Caused by`
   吞掉了,真机上完全无法定位。现在统一打全栈。
3. **`wrangler.toml` 的顶层键必须写在任何 `[table]` 之前**。`routes` 写在 `[triggers]` 后面会被解析成
   `triggers.routes`,自定义域静默不创建。
4. **`*.workers.dev` 在国内不可用**。见 §7.4。
5. **退避状态会挡住联调**。失败一次就写 `nextAttemptAt = now + 1h`,重装 APK 也不会清(`-r` 保留数据),
   联调时必须 `pm clear`。
