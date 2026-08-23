# 非破坏性调整图层与蒙版系统 — 设计文档

日期: 2026-08-23
状态: 已批准（用户授权自主执行）
前置调研: docs/FILTER-LAYER-RESEARCH.md（回滚实验踩坑记录）

## 1. 目标与范围

将"滤镜/调整图层"从伪实现（overlay 混合的 PaintLayer）升级为 Krita 原生非破坏性体系，并补全蒙版、填充层、持久化与录制回放：

- **阶段 1**: 全量滤镜（预览管线 19 种 + 曲线 LUT + 渐变映射 = 21 种）包装为 KisFilter 注册进 KisFilterRegistry
- **阶段 2**: 真 KisAdjustmentLayer 创建/参数编辑/增量合成
- **阶段 5**: 兼容 KRA 语义的持久化（layers.xml 节点树重建）
- **阶段 6**: 录制回放覆盖全部新操作
- **阶段 3**: 四种蒙版（透明度/滤镜/变换/选区）
- **阶段 4**: KisFillLayer 填充层真实现

明确不做: 矢量层（KisShapeLayer 需要 flake/KoShape 全家桶，成本高收益低）；完整 KisDocument 进程框架接入（无 GUI 运行的主要风险源）。

分期顺序（用户确认）: 1 → 2 → 5 → 6 → 3 → 4。

## 2. 架构原则

1. **全链路 Krita 原生节点类型**: KisAdjustmentLayer / KisTransparencyMask / KisFilterMask / KisTransformMask / KisSelectionMask / KisFillLayer；合成完全交给 KisAsyncMerger 原生驱动，不写自定义合成循环。
   - 上次回滚根因即 merger 只认注册表滤镜、未注册 ID 输出透明——本设计以"21 个滤镜全部注册"正面解决。
2. **性能铁律不变**: 运笔期不做全图同步重投影；依赖原生 dirty-region 增量合并 + `image->isIdle()` 跳帧兜底；结构变更才 `refreshGraphAsync+waitForDone` 且仅在引擎 HandlerThread。
3. **扩而不改**: 预览管线行为保持不变（内部改为查注册表调用）；旧录制文件向后兼容。
4. **JNI 单边界**: 新 JNI 全部加到 ReverieCoreBridge.kt + 对应 reverie_jni_*.cpp。

## 3. 阶段 1 — 全量滤镜注册

新文件 `app/src/main/cpp/ReverieCoreFilterRegistry.cpp`:

- 将 ReverieCoreFilterPreview.cpp 各 case 的像素算法抽取为统一签名核心函数 `(src, dst, w, h, p1..p4)`（src/dst 为 KisPaintDevice 或原始缓冲，视算法而定）。
- `ReverieFilterBase : public KisFilter` 包装基类:
  - `processImpl` 调对应核心函数；
  - `neededRect/changedRect`: 卷积类（gaussian/motion/sharpen/emboss/findEdges/bloom/glitch）按半径扩张，其余同矩形；
  - 配置经 KisFilterConfiguration 的 setProperty/getProperty 存取 p1-p4（f64）；LUT 类额外存 ByteArray（曲线 RGB 768B / 渐变映射 1024B，属性名固定，序列化由 config 自带机制承担）。
- 21 个实例 ID: `reverie-hsbc` `reverie-color-balance` `reverie-gaussian` `reverie-motion` `reverie-sharpen` `reverie-mosaic` `reverie-invert` `reverie-lineart` `reverie-edges` `reverie-emboss` `reverie-noise` `reverie-glitch` `reverie-desaturate` `reverie-curves-scalar` `reverie-levels` `reverie-temp-tint` `reverie-threshold` `reverie-posterize` `reverie-bloom` `reverie-curves-lut` `reverie-gradient-map`；均 `setSupportsAdjustmentLayers(true)`。
- 与既有 3 个插件（blur/gaussian blur/unsharp，ReverieCoreFilterPlugins.cpp）并存不冲突。
- 预览管线 `applyFilterPreview` 改为: 组装 KisFilterConfiguration → 从注册表取 filter → 直接调 process 设备级应用（保留三步备份语义），对外签名与行为不变。

## 4. 阶段 2 — 调整图层核心

### C++
- `addLayerWithType(type=3)` 重写: `new KisAdjustmentLayer(image, name, filterConfig)`；config 由 UI 流程先选定滤镜再创建（JNI 另提供 `createAdjustmentLayer(name, filterId)` 直建入口），替换伪实现；Clone 分支不动。
- 新 JNI:
  - `setAdjustmentLayerConfig(index, filterId: String, p1..p4: Double, lut: ByteArray?)` — 组装 config → `layer->setFilter(config)` → `refreshGraphAsync+waitForDone`；仅引擎 HandlerThread。
  - `getAdjustmentLayerConfig(index): filterId/p1..p4/lut` — 参数面板回显。
  - 层类型查询: 现有 syncLayersFromImage 扩展 LayerEntry.nodeType 字段（paint/group/fill/adjustment/vector/clone/transparency-mask/filter-mask/transform-mask/selection-mask）。
- 底色注入（坑 #3）: 创建 KisImage 已带背景层；真机验证透明区网格化是否复现，若现则在 newDocument 处对齐 defaultProjectionColor 与背景色。

### 实时性（坑 #2 解法）
- 运笔中: 下方图层笔画增量 setDirty → merger 仅重算脏区穿过滤镜 → isIdle 门控跳帧（现有渲染循环零改动）。
- 结构变更（创建/删除调整层、换滤镜类型）: 显式 recompositeProjection。
- 真机验证点: 8ms 批采样下运笔流畅度、脏区穿滤镜的正确性、极端大半径高斯的表现。

## 5. 阶段 5 — 持久化（兼容 KRA 语义）

- saveRevp/saveKra 在 zip 内新增 `layers.xml`（Krita maindoc.xml 图层语义子集）:
  - 树结构递归 `<layer|group|paintlayer|filllayer|adjustmentlayer|transparencymask|filtermask|transformmask|selectionmask>` 节点: name/visible/opacity/compositeop/x/y/type + adjustmentlayer 携带 `filter="reverie-*"` 与 p1-p4/LUT(base64) 属性；mask 作为父层子元素表达挂载顺序。
  - 每节点关联既有 per-layer PNG 数据文件名（沿用现命名）。
- 加载: 有 layers.xml → 按树重建节点（含 KisAdjustmentLayer/KisFillLayer/mask 挂载）→ PNG 灌回 paintDevice → refreshGraph；无 layers.xml → 走现逐层 PNG 平铺路径（旧档兼容）。
- 桌面 Krita 可打开我们的 .kra（只读验证一次即可，不做双向承诺）。

## 6. 阶段 6 — 录制回放覆盖

RecordingEvents 新码（L_ 域续编）:
- `L_ADJ_CONFIG`: index u16, filterId str, p1-p4 f32×4, lutLen u32, lut bytes —— 创建后每次参数变更一条
- `L_ADD_LAYER_TYPE` type=3 语义升级为真调整层（payload 不变，回放端分支更新）
- `L_ADD_MASK_TYPE`: parentIndex u16, maskType u8 —— 阶段 3 接入
- 回放端 PlaybackEngine 对应 case 直调 bridge; seek 快进路径同样生效。

## 7. 阶段 3 — 蒙版补全

- 新 JNI `addMaskWithType(parentIndex, maskType)`:
  - Transparency: KisTransparencyMask + initSelection(整层)
  - Filter: KisFilterMask + 默认 reverie-hsbc 配置（参数编辑复用调整层面板流程）
  - Transform: KisTransformMask + 恒等 QTransform（变换编辑后续接现有 Transform 域）
  - Selection: KisSelectionMask + 空 selection 占位
- 删除/可见性/透明度走现有 mask API（syncLayersFromImage 已能列出 mask）。
- UI: 图层行菜单"添加蒙版"四项; 蒙版行选中时画笔落在蒙版 paintDevice 上（透明度蒙版可手绘，Krita 行为对齐）。

## 8. 阶段 4 — 填充层

- `addLayerWithType(type=2)` 重写为 KisFillLayer（纯色 generator 路径，图案后续扩展）; fillColor 入 config。
- UI: 现有 addFillLayer 流程不变，弹色板选择后创建。

## 9. Kotlin/UI 总体

- `LayerUiState` 增 `nodeType: Int` 字段（JNI 同步下发）; 调整层/滤镜蒙版行点击打开参数面板（复用 LayerFilterAdjust，提交目标改为 setAdjustmentLayerConfig）。
- LayerListView "滤镜图层"菜单项启用: 点击 → 滤镜选择对话框（复用现有滤镜列表数据）→ vm.addAdjustmentLayer(filterType) → 打开参数面板。
- 所有 VM 方法维持 runCore(after=::notifyLayerChanged) 模式与录制钩子。

## 10. 测试与验收

- 机械验证每 commit: `./gradlew :app:compileDebugKotlin`; C++ 变更加跑 `./scripts/build_native.sh`。
- 真机回归（每阶段收口）:
  - 阶段 1: 既有单层滤镜逐个过一遍（回归不破坏）
  - 阶段 2: 创建调整层→运笔实时生效/流畅→隐藏/删除→撤销重做
  - 阶段 5: 保存→重启加载→结构与参数还原；导出 .kra 桌面 Krita 打开
  - 阶段 6: 录制含调整层操作的会话→回放一致
  - 阶段 3/4: 各蒙版添加/绘制/删除；填充层换色即时生效
- 已知限制记录: 矢量层不支持; 变换蒙版初始为恒等（变换编辑器接入另开任务）。

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| merger 清 original 导致透明输出（历史教训） | 21 滤镜全注册; 每滤镜真机过一遍 |
| 运笔期调整层重算卡顿 | 增量 dirty 合并 + isIdle 跳帧; 大半径滤镜实测降级方案（限制调整层可用滤镜集） |
| LUT 进 config 序列化异常 | base64 属性; 往返单测点列入真机清单 |
| KisFillLayer generator API 版本差异 | 以链接的 krita-source 头文件为准, 编译期暴露 |
