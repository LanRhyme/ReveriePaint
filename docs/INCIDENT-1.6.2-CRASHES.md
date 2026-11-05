# 1.6.2 线上崩溃复盘与修复

> 数据来源: `https://telemetry.lanrhyme.top/t/v1/stats?days=1` (发版后 24h) 与
> 按 `ver=1.6.2` 下钻的 5 条上报 (2026-10-29 ~ 10-31)。对应版本 `versionCode 49`。
> 本文档记录根因、代码改动与新增门禁，避免同类事故复发。

## 1. 现象汇总

| 签名 | 类型 | 机型 | 现象 |
|---|---|---|---|
| `005834ae99` | java | Sony XQ-AT52 (SDK 36, `/lib/arm/`) | 启动即 `UnsatisfiedLinkError`，**32 位包 100% 不可用** |
| `c2635dd1ac` | java | vivo V2072A | 万花筒对称落笔 → `ArrayIndexOutOfBoundsException: length=8; index=8` |
| `51f7f06d17` | native SIGSEGV | 三星 SM-P615 (3.5GB) | 画笔工作室涂抹预览 → `KisOverlayPaintDeviceWrapper::readRects` 空指针 |
| `34af6991ae` | native SIGABRT | vivo V2072A | 渲染 tile 准备 → 空 `KisSharedPtr` 触发 Krita `Q_ASSERT(d)` |
| `ffdde58c3a` | ANR | 三星 SM-T733 | 导入 3D 模型时主线程 `Pattern.compile`，输入超时 10s |

## 2. 根因与修复

### 2.1 32 位包启动即崩 — 预编译闭包混入了已回退实验的二进制

- `third_party/android-native-libs-armeabi-v7a/libkritacolorsmudgepaintop.so` 引用
  `ReveriePigmentMix::isEnabled()` / `mixQColor()` / `reveriePigmentMixBlendConstBgra8`，
  **闭包里没有任何库定义它们**；该库是 `libreverie_core.so` 的 `DT_NEEDED`，`System.loadLibrary` 必然失败。
- 这些库来自维护机上一次 pigment-mix 实验的构建产物（源码已回退，仓库里没有实现）；
  同时 `collect_armv7_libs.sh` 只覆盖不清理 `DEST`，某个源库缺失时只打一行"跳过"，
  导致新旧库混在同一个闭包里（`libkritaui.so` 是新的、13 个 paintop 插件还是旧版，
  于是插件引用 `KisPaintOpSettingsWidget::setImage` 这类新版本已不存在的符号）。
- 修复：
  - 从当前源码**重建** armv7 Krita 闭包（不放宽任何门禁）；
  - `scripts/collect_armv7_libs.sh` 改为「先收进暂存目录 → 缺源库整体失败 → 清空 DEST 再换入 → 自动跑符号门禁」，
    并且只收 arm64 闭包里也有的同名库，32/64 位保持同一套模块；
  - `scripts/copy_jni_libs.sh` 复制前先清空 `jniLibs/<abi>`：否则闭包里已删除的库会残留在 APK 里；
  - 删除闭包里上游已移除的 `libkritacolor.so`（`libs/color` 早已不在当前 Krita 源码里，
    构建目录里那份是旧版本残留），CMake 也不再链接它。

### 2.2 万花筒对称落笔必崩 — 分支上限与新特性不匹配

- `DrawingGuideConfig.computeSymmetricSamples()` 的镜像分支数：旋转 `rays-1`，万花筒 `2*rays-1`，
  `rays ∈ 2..36`（默认 8 轴万花筒 = 15 支，36 轴 = 71 支）。
- `CanvasTouchView.mirroredSizes` 是固定 `IntArray(8)`，`resetMirrorBranches()` 按
  `mirroredSamples.size` 循环写入 → 第 9 支起必然越界。单测只覆盖了 4 轴万花筒（7 支），刚好在上限之下。
- 修复：
  - `mirroredSizes` 改为随 `mirroredSamples` 动态扩容（`ensureMirrorBranches`），写入前双向越界检查；
  - 实时镜像（每分支一套原生 paintop）仍然只支持 8 支：分支数超出时不再入队，
    整笔改走抬笔 `replaySymmetricBranches`，避免"前 8 支出墨、其余永久缺失"；
  - `GuideModelsTest` 增加 2..36 轴全组合与默认万花筒的回归用例。

### 2.3 涂抹预览 SIGSEGV — 预览设备缺少 interstroke data

- colorsmudge 的 Lightness 策略要求画笔设备上挂着 `KisColorSmudgeInterstrokeData`。
  真笔画里它由 `KisTransaction` 通过 interstroke factory 注入；画笔工作室的取样预览没有事务，
  策略会走 `KIS_SAFE_ASSERT_RECOVER` 兜底分支，而该分支没有给 layer overlay 指针赋值，
  第一次 `readRects` 就解引用空指针。
- 修复：`scratchpadStrokeStart` 按当前预设的 factory 手工挂一份 interstroke data
  （`ReverieCoreBrush.cpp: attachScratchpadInterstrokeData`），预览结束/下一次开始前摘掉。
  非涂抹预设 factory 返回空，是 no-op。

### 2.4 渲染 SIGABRT — 渲染线程读到被并发清空的共享指针

- 崩溃点在内联到 `bakeStaticUnderlay` 的 `m_document->width()` / `m_staticUnderlay->fill()`
  （`kis_shared_ptr.h:171` 的 `Q_ASSERT(d)`）。入口处 `m_document` 判过空，
  说明它在函数执行期间被另一线程换掉/清空（渲染线程与核心线程共享 `m_document`、
  `m_staticUnderlay`，图层增删/换文档会调用 `invalidateStaticUnderlay()`）。
- 修复：渲染路径全程持本地快照（`KisImageSP image = m_document;`、
  `KisPaintDeviceSP underlay = m_staticUnderlay;`），`strokeMergeScratch*` /
  `strokeOutScratch` 补文档空判（原来的 `!m_strokeOutScratch || !m_document` 反而会在文档为空时解引用它）。
  竞态窗口本身仍在，但最坏情况从"整进程 abort"降级为"少画一帧"。

### 2.5 3D 模型导入 ANR

- `Mesh3D.parseObj()` 每读一行就 `l.split("\\s+".toRegex())`（逐行编译 ICU 正则），
  且整个解析跑在 UI 线程（`create3DLayerFromFile` 直接在 ActivityResult 回调里执行）。
- 修复：正则提升为文件级常量；导入改为 `viewModelScope.launch(Dispatchers.IO)`，
  只有最后的 UI 状态更新回到主线程。

## 3. 新增门禁

`scripts/check_native_symbols.sh`：对每个 ABI 的预编译闭包求
「强未定义符号 − 闭包内已定义 − Android 平台库导出」，非空即失败，并反查是哪个 `.so` 引用的。

- 已接入 `.github/workflows/release.yml`（JNI 符号门禁之后、打包之前）；
- `collect_armv7_libs.sh` 收尾自动跑；
- 用 1.6.2 的坏闭包验证过：能准确报出 5 个不可解析符号（3 个 pigment + `KoToolManager::instance`
  + `KisPaintOpSettingsWidget::setImage`），修复后的闭包全绿。
- 冒烟记录：`docs/TELEMETRY.md` 的 32 位端到端验证流程照旧适用。

## 4. 发版前检查清单（新增）

1. `./scripts/check_jni_symbols.sh`（既有）
2. `./scripts/check_native_symbols.sh`（新增，两个 ABI 都要绿）
3. 32 位/64 位各装一次 release 包，确认能进主页（1.6.2 的 32 位包连启动都过不了）
4. 万花筒（8/16/36 轴）与旋转对称各画一笔；画笔工作室里用涂抹预设拖几笔
5. 大画布（A4 300dpi）连续撤销/加删图层后再画，确认渲染不崩
