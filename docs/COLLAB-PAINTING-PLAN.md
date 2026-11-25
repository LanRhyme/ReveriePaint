# ReveriePaint P2P 茶绘功能技术方案 (Magma 协同架构)

> 状态: 方案设计中, 待确认原型切入点
> 日期: 2026-11-25
> 定位: 基于 P2P 与 Cloudflare 零月租信令的多人实时协同绘画 (不含语音)

## 1. 结论摘要

1. **零服务器公网互联架构**: 利用用户自有域名托管至 Cloudflare DNS, 采用 **Cloudflare Workers + SQLite Durable Objects** (启用 WebSocket Hibernation API) 承担房间码信令与短期凭证签发, P2P 建连完成后断开信令连接, 不经服务端转发任何画布像素或笔画流量
2. **传输层选用 C++ `libdatachannel` (`NO_MEDIA=ON`)**: 直接编译进 `reverie_core` 原生层避免 JNI 热路径字节拷贝, 利用国内 4G/5G/家宽原生 IPv6 直连 + 国内免费 STUN 优先打洞, 对称 NAT 由 Cloudflare Realtime TURN (每月 1TB 免费) 或用户自定义 TURN 兜底, 局域网保留 Android NSD 直连旁路
3. **Magma 式权限与图层隔离**: 基于现有 `reverie::NodeExtra` 侧表 (`ReverieCoreLayerExtras.h`) 为节点绑定全局 `layerUuid`, `ownerPeerId` 与 `collabPermission` (`OwnerOnly` / `Shared` / `Locked`), 默认一人一层实现无冲突的个人独立撤销
4. **C++ 引擎多路并发重构**: 剥离 `ReverieCore` 单实例笔画槽位为独立 `StrokeSession`, 远端正在绘制的笔触先渲染至各自临时 `KisPaintDeviceSP`, 并在 `compositeLayersRange` 合成期实时叠加显示, 待抬笔拿到全局序号 `globalSeq` 后正式写入目标图层
5. **大小房间自适应分发**: 2~10 人默认走房主权威星型 P2P 定序, 超过阈值自动切换为树形二跳 P2P 转发, 同一套二进制协议零侵入适配不同人数规模

---

## 2. 公网 P2P 与零成本信令架构

### 2.1 Cloudflare 免费层配额边界与防爆设计

| 组件 | 免费层额度 (Free Plan) | 茶绘中的真实消耗与硬约束 |
|---|---|---|
| **Workers** | 100,000 次请求/天, 10 ms CPU/次 | 仅做 HTTP 入口路由与 TURN 凭证签发, 消耗极微 |
| **Durable Objects (SQLite)** | 100,000 次请求/天, 13,000 GB-s/天, 5 GB 存储 | WebSocket 入站按 **20:1** 折算请求, 出站与 ping 免费; **必须开启 Hibernation API**, 且 P2P 握手完成后主动关闭信令 WebSocket |
| **Realtime TURN** | 1,000 GB/月出站免费 (无需 Workers 付费套餐, 仅需账户绑卡) | Worker 侧限制单房间每日签发次数; 不便绑卡时自动退化为 `IPv6 + STUN + 自定义 TURN` |

> [!WARNING]
> **红线约束**: 严禁把 Worker / Durable Objects 当作画布坐标转发中继, 否则 60Hz 笔触流会迅速击穿每日 10 万等效入站请求上限

### 2.2 信令与房间生命周期

```
房主端                         Cloudflare Worker + DO                     加入端
  │                                      │                                  │
  ├── POST /room/create (房间配置) ─────▶│ 分配 6 位房间码, 激活 DO         │
  │◀─ roomCode + hostToken ──────────────┤                                  │
  │══ WSS /room/:code?role=host ════════▶│ (Hibernation 挂起, 零算力消耗)    │
  │                                      │◀── WSS /room/:code?role=peer ────┤
  │◀─ PEER_JOIN_REQ (peerId, 昵称/密码) ─┤                                  │
  ├── PEER_APPROVED ────────────────────▶├── PEER_ACCEPTED + TURN 临时凭证 ─▶│
  │◀═══════ SDP Offer / Answer & ICE Candidates 交换 ══════════════════════▶│
  │                                      │                                  │
  │◀═══════════════ libdatachannel 端到端 P2P 握手成功 ═════════════════════▶│
  │                                      │                                  │
  └── 按需断开/仅房主保活短心跳 ─────────┴── 加入端断开信令 WS ─────────────┘
```

- **域名路由**: 绑定二级域名 (例如 `tea.<domain>`) 绕开 `workers.dev` 国内访问受阻问题
- **防撞车安全**: 房间码仅用于索引 DO 实例, 所有进房请求必须经过**房主审批或密码哈希校验**
- **局域网直连模式**: 同时保留 Android NSD (mDNS) 发现, 同一 Wi-Fi / 热点下不经过公网信令直连

### 2.3 大小房间拓扑自适应与房主迁移

- **常规规模 (2 ~ 10 人) — 房主权威星型 P2P**:
  - 单用户绘制采样流量约 `1 ~ 3 KB/s`, 房主总上行通常 `< 30 KB/s`, 移动设备可轻松承担
  - 房主分配全局单调递增 `globalSeq`, 彻底避免多人同时增删图层引发的索引和树结构分叉
- **大型房间 (10 ~ 50+ 人) — 分层二跳树形 P2P**:
  - 当 Peer 数超出房主带宽阈值或检测到发送队列积压时, 房主动态下发拓扑变更指令, 指定 3~4 个网络条件最优的 Wi-Fi 节点作为一级转发代理 (Relay Peers), 其余节点挂载至一级节点接收 `globalSeq` 广播
  - 新人入房 (Late Join) 拉取 `.revp` 基准包时, 优先调度空闲的下游节点分片传输, 避免卡顿房主画笔
- **房主断线迁移 (Host Migration)**:
  - 所有节点均持久化当前完整画布与已确认 `globalSeq`, 一旦房主心跳超时, 按预设顺位 (入房时间最早或 `peerId` 最小) 选举新房主向信令重新注册并接管定序

---

## 3. 双通道二进制同步协议

复用 `libdatachannel` 同一连接下的两条独立 DataChannel, 避免实时预览阻塞状态指令:

### 3.1 不可靠无序通道 (`collab-live`, `unordered=true, maxRetransmits=0`)

| 消息类型 | 负载字段 | 频率与用途 |
|---|---|---|
| `OP_CURSOR` | `peerId`, `x, y`, `radius`, `colorArgb`, `toolMode` | ~30~60Hz 同步队友光标位置与笔刷轮廓环 |
| `OP_STROKE_DELTA` | `peerId`, `strokeId`, `seq`, `samples[(x,y,p,tx,ty,rot,dt)...]` | 实时发送笔触增量点驱动对端临时画布预览, 丢包不重传, 抬笔兜底对齐 |

### 3.2 可靠有序通道 (`collab-ctrl`, `ordered=true`)

| 消息类型 | 负载字段 | 用途说明 |
|---|---|---|
| `OP_SNAPSHOT_META / CHUNK` | `docSeq`, `totalBytes`, `chunkIdx`, `sha256`, `revpBytes` | 新人入房拉取完整 `.revp` 基准包 (分片传输) + 补发 `docSeq` 之后增量队列 |
| `OP_BRUSH_MANIFEST / REQ / BLOB` | `presetMd5`, `kppBytes`, `resourceFiles[]` | 复用 `PlaybackPresetCache` MD5 校验, 对端缺失导入笔刷时按需拉取进隔离临时目录 |
| `OP_STROKE_BEGIN` | `peerId`, `strokeId`, `layerUuid`, `presetMd5`, `seed`, `color`, `size`, `opacity`, `toolMode` | 声明新笔画起点与确定性随机种子 (`setStrokeReplaySeed`) |
| `OP_STROKE_COMMIT` | `peerId`, `strokeId`, `globalSeq`, `finalSamples[]` / `rectHash` | 抬笔确认并附带完整采样校准, 触发正式写入目标 `KisPaintLayer` |
| `OP_STROKE_CANCEL` | `peerId`, `strokeId` | 本地撤回/防误触取消时立即丢弃远端临时画布内容 |
| `OP_TILE_PATCH` | `globalSeq`, `layerUuid`, `rect`, `codec(LZ4/WebP)`, `pixels` | 液化, 滤镜, 自由变换, 填充等重算类工具提交后只同步变动矩形像素补丁 |
| `OP_LAYER_TREE_OP` | `globalSeq`, `opType(add/remove/move/attr/owner)`, `layerUuid`, `parentUuid`, `meta` | 图层树结构变更与 Magma 所有权/锁定状态变更 |
| `OP_USER_UNDO` | `peerId`, `targetStrokeId` / `layerUuid`, `globalSeq` | 个人专属图层撤回/重做指令同步 |

---

## 4. C++ `ReverieCore` 核心改造方案

### 4.1 跨端图层身份与所有权 (`ReverieCoreLayerExtras` & `LayerEntry`)

当前 [ReverieCore.h](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/ReverieCore.h#L112-L117) 的 `layerId()` 返回节点裸指针地址且多数接口依赖 `int index`, 多端必乱序, 需作如下扩展:

- **扩展 `reverie::NodeExtra`** ([ReverieCoreLayerExtras.h](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/ReverieCoreLayerExtras.h#L27-L41)):
  - 新增 `QString uuid` (`reverie_uuid`), `QString ownerPeerId` (`reverie_owner`), `int collabPermission` (`0=Shared, 1=OwnerOnly, 2=Locked`)
  - 在 `syncLayersFromImage()` 与 `ReverieCoreLayerIO.cpp` 中自动补充缺失 UUID 并随 `.revp` 的 `layers.xml` 读写持久化
- **扩展 `ReverieCore::LayerEntry`** ([ReverieCore.h](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/ReverieCore.h#L535-L556)):
  - 缓存 `uuid`, `ownerPeerId`, `collabPermission`, 并在 `ReverieCore` 内部建立 `QHash<QString, int> m_uuidToLayerIndex` 快速查找表

### 4.2 抽离并发笔画上下文 `StrokeSession`

当前 [ReverieCore.h](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/ReverieCore.h#L1397-L1449) 仅支持单一进行中笔画 (`m_strokePainter`, `m_strokeOp`, `m_strokeTxn`, `m_strokeSamples`, `m_randomSource`), 需抽象为可多实例共存的 `StrokeSession`:

```cpp
struct StrokeSession {
    QString sessionId;           // "local" 或远端 "peerId:strokeId"
    QString targetLayerUuid;
    bool isRemote = false;

    KisPaintDeviceSP previewDev; // 远端进行中笔画落入独立临时设备, 不污染正式图层
    KisPainter *painter = nullptr;
    KisPaintOpSP op;
    KisDistanceInformation *distance = nullptr;
    KisRandomSourceSP randomSource;
    KisPerStrokeRandomSourceSP perStrokeRandom;
    KisMaskingBrushRenderer *maskRenderer = nullptr;
    KisMaskedFreehandStrokePainter *maskedPainter = nullptr;

    KisPaintOpPresetSP preset;   // 该用户当前使用的预设快照 (隔离 m_brushPreset)
    QColor color;
    qreal opacity = 1.0;
    ToolMode toolMode = ToolBrush;
    QRect dirtyBounds;
    QVector<StrokeSample> samples;
};
```

- **串行引擎线程分片安全执行**:
  - 保持 `PaintViewModel` 单一 `reverie-render` HandlerThread 驱动 `core()` 的架构铁律不变
  - 本地触摸优先消费以保零延迟手感 (`graphics-core` 前缓冲不受影响); 远端网络包入队后按微时间预算 (如每帧 `<= 2ms`) 批量喂入对应 `RemoteStrokeSession`
- **实时预览合成 (`compositeLayersRange` & `StrokePresentFastPath`)**:
  - 在 [ReverieCoreRender.cpp](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/ReverieCoreRender.cpp#L1671) 的 `compositePaintLayer` 内, 检查当前图层是否存在活跃的 `RemoteStrokeSession`: 若有, 按该远端会话的混合模式/橡皮擦语义将 `previewDev` 依次临时叠加至图层合成scratch (流程复用已有的 `hasTemporaryTarget()` 与 `strokeMergeScratch`)
  - 调整 [StrokePresentFastPath.h](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/StrokePresentFastPath.h#L23) 的 `strokeStaticUnderlayStop`: 计算上界 `b` 时同时取本地层与所有活跃远端层祖先起点的最小值, 或对远端脏区局部调 `invalidateStaticUnderlayRect()`, 确保静态底图缓存不错乱

### 4.3 Magma 式个人独立撤销设计

Krita 自带的 [ReverieUndoStore.h](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/ReverieCoreUndoStore.h) 为单文档共享栈 (`KUndo2Stack`), 无法跳过他人操作单独撤回自己的笔, 协同模式下需切换为**协同撤销控制器**:

- **个人专属图层 (`OwnerOnly`, 默认主力场景)**:
  - A 与 B 各自只在自己的图层作画, 像素完全隔离
  - C++ 侧按 `peerId` 维护各自独立的**图层快照/瓦片级撤回栈** (基于 `KisPaintDevice` 脏区 COW 切片或独立 `KUndo2Stack` 实例映射), A 点击撤回仅回滚自己图层的瓦片并广播 `OP_USER_UNDO`, 对 B 的图层零影响
- **共享公共层 (`Shared`)**:
  - 一期约定公共层支持“涂鸦即落盘”或仅允许撤回本人尚未与他人像素交叉的最后一笔; 二期可扩展单层 `Checkpoint + OpLog` 重放实现任意历史笔撤回

### 4.4 笔刷跨端隔离与资源加载

- 在本地应用数据目录开辟独立的 `collab_brush_cache/<roomId>/` 沙盒目录, 防止把队友的临时 `.kpp` 笔刷污染进用户的本地笔刷库
- C++ `ReverieCoreBrush` 新增 `loadCollabPresetFromBytes(sessionId, kppBytes)` 接口, 直接从内存解析 `KisPaintOpPresetSP` 并挂载到对应 `StrokeSession::preset`, 不改动全局 `m_brushPreset`

### 4.5 原生构建与双 ABI 门禁

- 在 [CMakeLists.txt](file:///home/lanrhyme/Projects/ReveriePaint-native/app/src/main/cpp/CMakeLists.txt) 新增 `libdatachannel` (`-DNO_MEDIA=ON`) 与静态 TLS 依赖 (OpenSSL/BoringSSL), 新建隔离域文件 `ReverieCoreCollab.cpp` 与 `reverie_jni_collab.cpp`
- 严格遵循 `AGENTS.md` §7 流程: `arm64-v8a` 与 `armeabi-v7a` 双架构编译, `llvm-strip` 剥离调试符号后同步至 `third_party/android-native-libs{,-armeabi-v7a}/`, 并保证 `./scripts/check_native_symbols.sh` 与 `./scripts/check_jni_symbols.sh` 全绿

---

## 5. Kotlin 协同层与 UI 交互设计

遵循 `ui/ → core/ → model/` 单向依赖约束:

- **`model/CollabModels.kt`**: 纯 Kotlin 数据模型 (`RoomConfig`, `CollabPeer`, `LayerPermission`, `CollabConnectionState`, `RoomTopologyMode`), 可直接覆盖 JUnit4 单测
- **`core/collab/`**:
  - `SignalingClient.kt`: 基于现有 `OkHttp` (`com.squareup.okhttp3:okhttp:4.12.0`) 对接 Cloudflare Worker WebSocket + NSD 局域网发现
  - `CollabSessionManager.kt`: 房间状态机, 协作者列表, 权限仲裁, 心跳检测与房主迁移调度
  - `PaintViewModelCollab.kt`: 按 ViewModel partial 模式扩展, 连接 `ReverieCoreBridge` 原生事件回调与 UI 状态镜像
- **`ui/painting/collab/`**:
  - `CollabLobbyDialog.kt`: 创建房间 (画布预设/人数上限/审批开关/密码), 输入房间码加入, 局域网房间自动列表
  - `RemoteCursorOverlay.kt`: 在画布最上层轻量绘制队友光标, 颜色徽章, 昵称及笔刷半径圈
  - 图层面板扩展: 图层条目右上角展示拥有者颜色标记/头像徽章与锁图标, 长按菜单增加“设为公共层 / 仅自己可画 / 转让给队友”
  - 全部字符串按 `AGENTS.md` §6 同步维护 `values/strings.xml` 与 `values-en/strings.xml`

---

## 6. 分阶段开发里程碑

| 阶段 | 目标与交付物 | 核心验收标准 |
|---|---|---|
| **Phase 0: 信令与公网连通 PoC** | 独立 `signaling-worker/` (Cloudflare Worker + SQLite DO + 域名绑定) | 两台测试设备分别在 Wi-Fi 与 5G 流量下输入 6 位房间码完成 SDP/ICE 交换并测出 IPv6/STUN/TURN 成功率 |
| **Phase 1: C++ 引擎并发化** | `layerUuid` 侧表改造, `StrokeSession` 抽离, 单机双笔并发模拟验证 | 本地正在用复杂湿画笔/蒙版笔绘的同时, 后台模拟远端笔触并发落到同一图层与不同图层不闪退, 不丢本地前缓冲直出帧率 |
| **Phase 2: `libdatachannel` 接入与核心同步** | 双端 P2P 互连, `.revp` 入房快照分片, 笔画流与笔刷 MD5 懒加载 | 队友加入已画到一半的工程, 能在秒级还原全量图层并实时看到彼此运笔, 导入笔刷跨端效果一致 |
| **Phase 3: Magma 权限、独立撤销与大房间拓扑** | 图层所有权拦截, 个人独立撤销栈, >10 人树形转发与房主掉线迁移 | 多人同房画画互点撤销只撤自己的笔; 房主强杀后台后其余成员无感选举新房主继续作画 |
| **Phase 4: UI 打磨与高压回归** | 房间大厅, 远端光标, 图层面板徽章, 多语言文案, 双 ABI 门禁脚本验证 | 连续高强度并发涂鸦 30 分钟无内存泄漏或 OOM, `compileDebugKotlin`, `testDebugUnitTest`, `check_native_symbols.sh`, `check_jni_symbols.sh` 全部通过 |
