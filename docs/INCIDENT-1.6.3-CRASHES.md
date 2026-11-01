# INCIDENT — 1.6.3 线上崩溃复盘（2026-11-01）

数据来源: `https://telemetry.lanrhyme.top/t/v1/stats?days=1`（`ver=1.6.3`），
tag `v1.6.3` = `e689c704`（2026-11-01 02:12 +0800）。

窗口内: **17 条上报 / 11 个签名 / 7 台设备**。

## 0. 1.6.2 修复的验证

1.6.2 的四个签名（`005834ae99` 32 位启动、`c2635dd1ac` 对称越界、`51f7f06d17`
涂抹预览、`34af6991ae` 渲染空指针）在 1.6.3 数据里**一条都没有再出现**。

## 1. 笔刷 ABR 导入 OOM（4 条）

```
java.lang.OutOfMemoryError: Failed to allocate a 343521128 byte allocation
  at ByteArrayOutputStream.toByteArray
  at kotlin.io.ByteStreamsKt.readBytes(IOStreams.kt:137)
  at AbrParser.parse(AbrParser.kt:285)
  at importSingleBrushInternal(PaintViewModelBrush.kt:945)
```

- 根因: `AbrParser.parse` 用 `input.readBytes()` 把整包读进内存（`ByteArrayOutputStream`
  还会再复制一份），大 ABR 单次申请 130MB / 343MB，512MB Java 堆直接崩；导入前没有任何体积上限。
- 修复:
  - `AbrParser.MAX_IMPORT_BYTES = 64MB`，新增 `readImportBytes` 有上限读取
    （已知长度一次分配、未知长度倍增到上限即放弃）；
  - 导入前从 `ContentResolver` 读 `OpenableColumns.SIZE`，超限直接提示
    `brush_import_too_large`，不再进解析；
  - `AbrParseResult.tooLarge` 把"包太大"和"空包"区分开，UI 给明确提示；
  - `.kpp` 单文件导入同样走有上限读取。

## 2. 3D 网格：ANR + OOM（2 条，同一台荣耀平板）

ANR 主线程栈：

```
at Mesh3D$Companion.computeEdgeTriangles(Mesh3D.kt:89)
at Mesh3D.<init> → parseObj → buildStaticMesh → deformState ← getInteractiveDeformed
at CanvasTouchView.drawCanvas
```

OOM 栈在 `Mesh3D.extractWireframeIndices`。三个叠加问题：

1. `computeEdgeTriangles` 是 O(边 × 面)：每条线框边重扫全部三角面。
2. `buildStaticMesh` 的缓存键是 `"modelType#pose#angles"`，而导入时写的是
   `meshCache["file:/path"]` —— 导入模型永远命中不了，首帧在 `onDraw` 里重新解析文件。
3. `parseObj` / `extractWireframeIndices` 全程装箱（`ArrayList<Float>`、
   `HashMap<String, Int>`、`HashSet<Long>`），大模型峰值内存爆掉。

修复：

- `computeEdgeTriangles` 改为单遍扫描三角面 + 开放寻址 `LongLongMap`
  （边 → 前两个三角面，语义与旧实现一致，有单测对拍）。
- `extractWireframeIndices`、`parseObj` 改用不装箱的 primitive 动态数组；
  顶点去重键从 `"1/2/3"` 字符串换成 `(vIdx, vnIdx)` 打成的 long。
- `buildStaticMesh` 对 `file:` / `uri:` 模型直接复用导入缓存；缓存超限时优先淘汰
  姿态变体，保留导入模型（`trimMeshCache`）。
- `prewarm3DModel` 补上非蒙皮静态网格（原实现只暖 skinned 缓存），
  工程载入 / 新建 3D 图层都在 IO 线程把首帧要用的网格备好。
- 规模上限 20 万顶点 / 40 万三角面，超限抛 `MeshTooLargeException`，
  导入路径提示 `toast_3d_model_too_large`（绘制路径兜底成方块，不崩）。

## 3. redo / 历史回放崩溃（3 条，1.6.3 新增路径在栈上）

```
KisMementoManager::commit ← rollforward ← KisTransactionData::redo
← KUndo2QStack::redo ← ReverieCore::replayHistoryCommand ← ReverieCore::redo
```

- 新增的 `replayHistoryCommand`（#160"撤销重做回放期间锁住图像"）直接出现在栈上。
- 加固:
  - 重放前先 `waitForDone()` 排空合并器，再上 `barrierLock`（含已被加锁的情况，
    用 `lockCount` 计数并保持只读语义），RAII 解锁，未锁时再排空一次；
  - `RPC_LOG("RPC historyReplay redo=%d locked=%d count=%d index=%d redoCount=%d")`
    留现场，便于下一轮遥测判断是历史被 purge 还是并发。
- 仍需真机复现: 连续 20+ 笔 → undo 到底 → 连续 redo，观察上述日志与
  `dataManager()->historySize()`。

## 4. 变换预览取消时间断言（2 条）

```
kis_assert_common ← KisTransactionData::endTransaction ← ReverieCore::cancelTransformPreview
```

- 根因: `KisTransaction` 在创建时记录 `transactionTime`，结束时要与
  `defaultBounds()->currentTime()` 相等；变换预览事务跨用户交互长期打开，
  中途动画时间变化（切帧 / 播放）就会命中断言。
- 修复: `setAnimationCurrentTime` 在真正切帧前先收尾长事务
  （`cancelTransformPreview()` / `revertOpenRestorePatch()`），
  保证事务不跨帧存活。

## 5. 图层快照 UAF（1 条）

```
KisAnimatedOpacityProperty::get() ← ReverieCore::layerOpacity ← rp_doc_snapshot
← nativeSnapshotFill
```

- 根因: 快照在主线程读 `m_layers`，核心线程同时在删层；`m_layers` 存的是裸
  `KisNode*`，节点被释放后快照仍去读 `node->opacity()`。
- 修复:
  - `m_layerMirrorRefs`（`QVector<KisNodeSP>`）在 `syncLayersFromImage` 重建时
    与 `m_layers` 同步刷新，保证"已移出图层树、尚未重建"的节点在快照期间仍然存活；
  - `m_layerMirrorMutex` 串行化"重建 vs 快照读取"（快照额外持有原有 `g_snapshotMutex`）。

## 6. 平台图形栈崩溃（5 条，非应用代码）

- `d1c02e2849` / `ef01aeec77`（TCL T951K）: `abort ← libhwui ← libpng/libandroidfw`，
  RenderThread 解码/绘制阶段终止，同机当天另有 ABR 导入 OOM。
- `f047d7d9a2`（小米 2312DRA50C）: `libgui.so SurfaceComposerClient` SIGSEGV。
- 三条栈上只有我们自己的信号处理器，其余全是系统库；暂列不可直接修复，
  需要在对应机型复现后再定位是否由异常资源触发。

## 验证

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest          # 新增 Mesh3DWireframeTest
./scripts/build_native.sh --abi armeabi-v7a
./scripts/build_native.sh                 # arm64 + assembleDebug
./scripts/refresh-prebuilt-jni.sh
./scripts/copy_jni_libs.sh
./scripts/check_jni_symbols.sh
./scripts/check_native_symbols.sh
```

真机回归重点：大 ABR（>64MB 与正常包）、导入大 OBJ、工程载入含 OBJ 的 3D 图层、
连续 undo/redo、动画项目里切帧时取消变换预览、图层面板快速增删。
