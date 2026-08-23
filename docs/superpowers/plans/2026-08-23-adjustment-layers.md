# 调整图层与蒙版系统 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将滤镜/调整图层升级为 Krita 原生非破坏体系：35 个滤镜注册为 KisFilter、真 KisAdjustmentLayer、兼容 KRA 语义持久化、录制回放全覆盖、四种蒙版、原生 generator 填充层。

**Architecture:** 全部走 Krita 原生节点（KisAdjustmentLayer/KisGeneratorLayer/四种 Mask），由 KisAsyncMerger 原生驱动合成；滤镜内核从预览管线抽取为可复用函数，经参数化包装类注册进 KisFilterRegistry；既有单层滤镜预览管线保持不动。

**Tech Stack:** Kotlin 2.4 + Compose / JNI / C++17 (NDK) / Krita libs (libkritaimage, KoStore) / Qt core (无 GUI)

**Spec:** docs/superpowers/specs/2026-08-23-adjustment-layers-design.md

## Global Constraints

- C++ 文件顶部必须 `SPDX-License-Identifier: GPL-3.0-or-later`；`-fno-operator-names` 已全局开启（禁用 and/or/xor 关键字）。
- 引擎调用禁止上 UI 线程：所有新 JNI 调用点必须经 `runCore(...)`（HandlerThread）。
- 每个 commit 至少跑 `./gradlew :app:compileDebugKotlin`；触 C++ 的任务加跑 `./scripts/build_native.sh`。
- Conventional Commits 中文；scope 用 `filter`/`layer(s)`/`replay`/`io` 等。
- 既有单层滤镜预览管线（ReverieCoreFilterPreview.cpp 三步流程）行为零改动。
- 旧 .revp 档案与旧录制文件必须向后兼容。
- UI 文案直接中文字面量；主题色引用 ui/theme 语义色。

## 关键锚点（执行者必读）

| 事实 | 位置 |
|---|---|
| 预览管线 case 内核 | ReverieCoreFilterPreview.cpp L28-611（case0-18），FX case19-34 在 ReverieCoreFilterPreviewFx.cpp |
| filterParallelFor / boxBlurH/V | ReverieCoreInternal.h L515-560 |
| registerCoreFilters() 幂等样板 | ReverieCoreFilterPlugins.cpp L273-289，调用点 ReverieCoreBrush.cpp:34 |
| KisFilter 子类样板 | ReverieBlurFilter 同文件 L26-103（processImpl(device,rect,config,updater)/neededRect/changedRect/createConfigurationWidget→nullptr） |
| addLayerWithType Adjustment 伪实现 | ReverieCoreLayers.cpp L192-194 |
| LayerEntry 结构 | ReverieCore.h L216-228；syncLayersFromImage mask 分支 Document.cpp L248-260 |
| KisAdjustmentLayer(image,name,kfc,selection)/setFilter(config) | ~/Projects/krita-source/libs/image/kis_adjustment_layer.h L40/L77 |
| KisTransformMask(image,name)；KisSelectionMask 存在；kis_generator.h generate() | libs/image 同目录 |
| saveRevp/saveKra/loadRevp | ReverieCoreIO.cpp L114-241/L412-602/L264-410 |
| RecordingEvents L_ 域最大=33；T_UNDO=36/T_REDO=37 | RecordingEvents.kt L61-95 |
| PlaybackEngine dispatchLayerOpLocked | PlaybackEngine.kt L538-693；L_ADD_LAYER_TYPE case L676 |
| VM 滤镜三步封装 | PaintViewModelFilters.kt L31-129 |
| 滤镜清单硬编码 35 项(id0-34) | LayerFiltersPage.kt L152-232 |
| "+"菜单 DropdownMenu 锚点 | LayerListView.kt L355-409（滤镜图层 disabled 项 L375-393，插入蒙版项在 L408 后） |
| syncLayersFromNative 构造 LayerUiState | PaintViewModel.kt L2024-2052 |

---

### Task 1: 滤镜内核抽取（ReverieCoreFilterKernels）

**Files:**
- Create: `app/src/main/cpp/ReverieCoreFilterKernels.h`、`app/src/main/cpp/ReverieCoreFilterKernels.cpp`
- Modify: `app/src/main/CMakeLists.txt`（源列表加新 .cpp）、`ReverieCoreFilterPreview.cpp`（case 体改为调 kernel 函数）、`ReverieCoreFilterPreviewFx.cpp`（同）

**Interfaces:**
- Produces: `void reverieApplyScalarKernel(QImage &img, int filterType, double p1, double p2, double p3, double p4)`（img 尺寸即 w/h；case0-18 与 FX19-34 的像素体原样搬入 switch）；`void reverieApplyCurvesLutKernel(QImage&, const quint8* r256, const quint8* g256, const quint8* b256)`；`void reverieApplyGradientMapKernel(QImage&, const qint32* lut256)`；`int reverieFilterMargin(int filterType)`（卷积类返回半径上限，逐像素类返回 0）
- Consumes: 无（纯搬迁，逻辑零改动）

- [ ] **Step1**: 新建两文件，把 FilterPreview/Fx 各 case 的像素循环体搬入 `reverieApplyScalarKernel`（保留全部中文注释与 qBound 边界处理；boxBlurH/V 调用改用 Internal.h 既有函数）
- [ ] **Step2**: Preview/Fx 两文件的 case 改为一行调用 kernel 函数；`applyCurvesLUTPreview/applyGradientMapPreview` 内核同样替换
- [ ] **Step3**: `./scripts/build_native.sh && ./gradlew :app:assembleDebug` 通过
- [ ] **Step4**: commit `refactor(filter): 抽取滤镜像素内核为可复用函数(行为零改动)`

### Task 2: 35 滤镜注册进 KisFilterRegistry

**Files:**
- Create: `app/src/main/cpp/ReverieCoreFilterRegistry.cpp`
- Modify: `CMakeLists.txt`、`ReverieCoreInternal.h`（声明 registerReverieRegistryFilters()）、`ReverieCoreBrush.cpp:34` 注册块追加调用

**Interfaces:**
- Produces: ID `reverie-f<0..34>` 共 35 个 KisFilter；config 属性约定 `"reverieType"(int)` `"p1".."p4"(double)` `"lut"(QByteArray, 仅 13/30 用)`
- Consumes: Task1 的三个 kernel 函数

- [ ] **Step1**: 单一包装类 `ReverieKernelFilter : KisFilter`，构造存 m_type；`processImpl`: 读 config p1-p4 → 计算 margin=reverieFilterMargin(type) 的 workRect=rect.adjusted(-m,-m,m,m) ∩ 全画布 → 从 device readBytes(workRect) 建 QImage → kernel(img,type,p...) → 只写回 rect 区域 writeBytes；13/30 分支读 "lut" 属性走对应 kernel
- [ ] **Step2**: neededRect 返回 rect.adjusted(±margin)，changedRect 返回 rect；createConfigurationWidget 返回 nullptr；setSupportsAdjustmentLayers(true)
- [ ] **Step3**: registerReverieRegistryFilters() 幂等循环注册 35 个（照抄 FilterPlugins.cpp L273-289 模式），在 Brush.cpp:34 处调用
- [ ] **Step4**: build_native + assembleDebug 通过；commit `feat(filter): 35个滤镜以 reverie-fN 注册进 KisFilterRegistry`

### Task 3: nodeType 贯穿（C++→JNI→LayerUiState）

**Files:**
- Modify: `ReverieCore.h`(LayerEntry 加 `int nodeType = 0`)、`ReverieCoreDocument.cpp`(syncLayersFromImage 各分支赋 nodeType)、`ReverieCoreLayers.cpp`(layerNodeType getter)、`reverie_jni_layers.cpp`、`ReverieCoreBridge.kt`、`PaintViewModel.kt`(LayerUiState 加 nodeType; syncLayersFromNative L2032-2048 传入)

**Interfaces:**
- Produces: nodeType 值域 0=paint 1=group 2=fill(generator) 3=adjustment 5=clone 10=transparency-mask 11=filter-mask 12=transform-mask 13=selection-mask；JNI `external fun layerNodeType(index: Int): Int`
- Consumes: 无

- [ ] **Step1**: C++ 三处 dynamic_cast 判定（KisAdjustmentLayer/KisGeneratorLayer/KisCloneLayer/四 mask 类）写入 entry.nodeType
- [ ] **Step2**: JNI+Bridge+LayerUiState 三层贯穿；syncLayersFromNative 填充
- [ ] **Step3**: compileDebugKotlin + build_native；commit `feat(layer): 图层节点类型字段贯穿引擎与UI镜像`

### Task 4: 真 KisAdjustmentLayer 创建与配置编辑

**Files:**
- Create: `ReverieCoreAdjustment.cpp`
- Modify: `ReverieCoreLayers.cpp`(L192-194 Adjustment 分支)、`ReverieCoreInternal.h`/`ReverieCore.h`(声明)、`reverie_jni_layers.cpp`、`ReverieCoreBridge.kt`

**Interfaces:**
- Produces:
  - C++ `bool createAdjustmentLayer(const QString &name, int filterType, double p1..p4)`
  - C++ `bool setAdjustmentLayerConfig(int index, int filterType, double p1..p4, const QByteArray &lut)`
  - C++ `QString getAdjustmentLayerConfig(int index)`（JSON: {"type":n,"p1":..,"p2":..,"p3":..,"p4":..,"lut":"base64"}；非调整层返回 ""）
  - JNI 同名三函数（lut 经 jbyteArray，get 返回 jstring）
  - undo: `class ReverieAdjustmentConfigCommand : KUndo2Command`（持 old/new config+index，undo/redo 各自 setFilter+refreshGraphAsync+waitForDone）
- Consumes: Task2 的 config 属性约定；addLayerWithType 插入模式（KisImageLayerAddCommand+currentInsertPosition）

- [ ] **Step1**: config 组装助手 `KisFilterConfigurationSP reverieMakeConfig(int type,p1..p4,QByteArray lut)`（`filter->factoryConfiguration(KisResourcesInterface::instance())` 或按 kis_filter_configuration.h 可用构造；属性 setProperty）
- [ ] **Step2**: create/set/get 三函数 + undo command；addLayerWithType type==3 分支改为 `new KisAdjustmentLayer(image, finalName, defaultConfig, nullptr)`（defaultConfig 由新增可选参数 filterType 提供，默认 0）
- [ ] **Step3**: build_native + compileDebugKotlin；commit `feat(layer): KisAdjustmentLayer 真实现(创建/配置编辑/撤销)`

### Task 5: 调整层 UI（创建入口+参数面板复用）

**Files:**
- Modify: `ui/painting/layers/LayerListView.kt`(L375-393 启用菜单项)、`ui/painting/layers/LayerPanel.kt`、`core/PaintViewModelLayers.kt`(新 addAdjustmentLayer(filterId,filterName))、`core/PaintViewModelFilters.kt`(commitFilter 分支：nodeType==3 时改走 setAdjustmentLayerConfig 并跳过盖印语义)、`ui/painting/layers/LayerFilterAdjust.kt`(进入时若调整层先 getAdjustmentLayerConfig 回显)

**Interfaces:**
- Consumes: Task4 JNI；滤镜清单 LayerFiltersPage.kt L152-232；dispatchFilterPreview(LayerFilterPreview.kt L239-280) 的 id→(type,p1..p4) 映射
- Produces: 用户路径 "+→滤镜图层→选滤镜→创建→滑条实时刷新→✓提交"

- [ ] **Step1**: vm.addAdjustmentLayer(name,filterType)：录制 L_ADD_LAYER_TYPE("名称|3|…")+L_ADJ_CONFIG(Task8 码，先留常量占位本 task 不录) → runCore{ Bridge.createAdjustmentLayer }
- [ ] **Step2**: 菜单项启用：点击弹滤镜选择页（复用 FiltersPage，onSelectFilter 转 addAdjustmentLayer）
- [ ] **Step3**: FilterAdjustPage 对调整层（nodeType==3）：begin/apply/cancel 预览仍可用（作用于其 original 设备做所见即所得），但 ✓提交 改为 `vm.commitAdjustmentConfig(index,filterId,st)`（内部 setAdjustmentLayerConfig+录制 L_ADJ_CONFIG），不落像素不盖印
- [ ] **Step4**: 真机冒烟：创建高斯调整层于两颜料层之间→下层面已有内容被模糊→运笔新笔画实时带模糊；commit `feat(ui): 滤镜图层菜单启用并接入真调整层编辑流`

### Task 6: layers.xml 写入（saveRevp/saveKra）

**Files:**
- Create: `ReverieCoreLayerIO.cpp`（writeLayersXml/readLayersXml 及树重建）
- Modify: `ReverieCoreIO.cpp`（saveRevp 在 meta.json 后写 `layers.xml` 条目；saveKra 同）、`CMakeLists.txt`

**Interfaces:**
- Produces: XML 形如 `<layers><paintlayer name=".." filename="layer_000.png" visible="1" opacity="255" compositeop="normal" x="0" y="0"><transparencymask .../></paintlayer><group ...>…</group><adjustmentlayer filter="reverie-f2" p1=".." lut_b64=".."/><generatorlayer generator="reverie-solid-color" color="#RRGGBBAA"/></layers>`；节点顺序=树先序（m_layers 已是先序，按 depth 栈重组嵌套）
- Consumes: Task3 nodeType；Task4 getAdjustmentLayerConfig

- [ ] **Step1**: writeLayersXml(QString *out)：遍历 m_layers 按 depth 生成嵌套 XML（QXmlStreamWriter），每非 group 节点 filename 对应既有 layer_%03d.png 序号（沿用现有循环序号规则，group 无数据文件）
- [ ] **Step2**: saveRevp/saveKra 接入写入；build；commit `feat(io): revp/kra 写入兼容 KRA 语义的 layers.xml 节点树`

### Task 7: layers.xml 加载重建

**Files:**
- Modify: `ReverieCoreLayerIO.cpp`(readLayersXml)、`ReverieCoreIO.cpp`(loadRevp L336-394 分支：存在 layers.xml 走新路径)

**Interfaces:**
- Consumes: Task6 格式；Task4 create 类 API（内部复用其建节点代码，绕过 UI 层 currentLayer 副作用——提供 `buildNodeFromXml` 内部函数）

- [ ] **Step1**: QXmlStreamReader 解析→递归建节点（paint/group/adjustment/generator/mask 挂载），PNG 数据按 filename 从 store 读入 paintDevice（convertFromQImage）；opacity/compositeop/visible/x/y 还原
- [ ] **Step2**: 无 layers.xml → 原平铺路径不动；损坏 XML → 回退平铺路径并在 meta 标记
- [ ] **Step3**: 真机往返：建含调整层的文档→保存→重启加载→结构一致；commit `feat(io): 加载 layers.xml 重建非破坏节点树(旧档兼容)`

### Task 8: 录制回放覆盖

**Files:**
- Modify: `RecordingEvents.kt`(L_ADJ_CONFIG=34, L_ADD_MASK_TYPE=35)、`PaintRecorder.kt`(layerOp 已够用则仅常量)、`PlaybackEngine.kt`(dispatchLayerOpLocked 新 case)、`PaintViewModelLayers.kt`/`Filters.kt`(录制钩子补齐)、`app/src/test/java/...RecordingEventsCodecTest.kt`(arg 编解码纯函数测试)

**Interfaces:**
- Produces: `L_ADJ_CONFIG` arg=`"<type>|<p1>|<p2>|<p3>|<p4>|<b64lut>"` index=层号；`L_ADD_MASK_TYPE` arg=`"<maskType>"` index=父层号；回放 case 直调 bridge.setAdjustmentLayerConfig/addMaskWithType
- Consumes: Task4/9 的 bridge 函数（本 task 先接 Task4 部分，mask case 随 Task9 补）

- [ ] **Step1**: 常量+编解码 helper（纯 Kotlin，放 model/）+ JUnit 测试先行
- [ ] **Step2**: VM 钩子：Task4/Task5 创建与提交处补录制（若未带）；PlaybackEngine case
- [ ] **Step3**: testDebugUnitTest + compileDebugKotlin；commit `feat(replay): 调整层配置入录制事件流并可回放`

### Task 9: 四种蒙版

**Files:**
- Create/Modify: `ReverieCoreMasks.cpp`（addMaskWithType/removeNode 通用化）、`reverie_jni_layers.cpp`、`Bridge.kt`、`PaintViewModelLayers.kt`、`LayerListView.kt`(L408 后插四个 DropdownMenuItem)、`PlaybackEngine.kt`(L_ADD_MASK_TYPE case)

**Interfaces:**
- Produces: JNI `addMaskWithType(parentIndex, maskType)`（0透明度/1滤镜/2变换/3选区）；滤镜蒙版默认挂 reverie-f0 中性配置，选中蒙版行时参数面板复用 Task5 流程（target=mask 的 setFilter）
- Consumes: addMaskToLayer 既有模式（LayerOps.cpp L337-402）；KisTransparencyMask/KisTransformMask/KisSelectionMask 构造签名以 krita-source 头为准

- [ ] **Step1**: C++ 四分支创建+KisImageLayerAddCommand 入栈+sync；变换蒙版恒等初始
- [ ] **Step2**: UI 菜单四项+vm 方法（录制 L_ADD_MASK_TYPE）；回放 case
- [ ] **Step3**: 透明度蒙版手绘验证：stroke 目标设备选择处支持 mask paintDevice（若 stroke 管线强绑 KisPaintLayer 则记录限制，本 task 只做添加/删除/显隐）
- [ ] **Step4**: build+compile+真机；commit `feat(layer): 透明度/滤镜/变换/选区四种蒙版`

### Task 10: 原生填充层（generator）

**Files:**
- Modify: `ReverieCoreFilterRegistry.cpp`(或新 ReverieCoreGenerators.cpp：ReverieSolidColorGenerator:KisGenerator "reverie-solid-color")、`ReverieCoreLayers.cpp`(Fill 分支改 KisGeneratorLayer)、新 JNI `setFillLayerColor(index,color)`、UI 色板入口沿用 addFillLayer

**Interfaces:**
- Produces: 填充层换色即时生效（setGenerator+refresh），config 属性 "color"(QColor)
- Consumes: kis_generator_registry.h；KisGeneratorLayer ctor 以头文件为准

- [ ] **Step1**: generator 注册（generate() 内 fill dst dev rect）
- [ ] **Step2**: Fill 分支替换 + setFillLayerColor + addFillLayer 改调用（录制沿用 L_ADD_LAYER_TYPE type=2 + 追加 L_ADJ_CONFIG 变体或专用 arg，回放端对 type=2 解析颜色）
- [ ] **Step3**: build+真机换色即时生效；commit `feat(layer): 填充图层升级为原生 generator 非破坏层`

### Task 11: 收尾验证与交付

- [ ] assembleDebug + adb install -r（设备 ed3fdd92）
- [ ] 真机回归清单执行（spec §10 各阶段条目），结果记入 PROGRESS/docs
- [ ] docs/PROGRESS.md 勾选里程碑；commit `docs: 调整图层里程碑推进记录`

## Self-Review 记录

- Spec 覆盖：§3→T1/T2，§4→T3/T4/T5，§5→T6/T7，§6→T8，§7→T9，§8→T10，§10→各任务 Step 真机项+T11 ✓
- 类型一致性：nodeType 值域/事件码/config 属性名已全局统一 ✓
- 占位符扫描：无 TBD；API 细节以 krita-source 头文件为准的表述均给出具体文件名 ✓
