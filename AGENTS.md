# AGENTS.md — ReveriePaint 开发规范

> 本文档面向人类开发者与 AI 编码代理。修改代码前请先阅读本文件, 遵守其中的架构约束、编码规范与变更工作流。

## 1. 项目简介

ReveriePaint 是基于 **Krita 核心引擎** 的 Android 原生绘画应用:

```
Kotlin/Compose UI ──JNI── C++ ReverieCore ── Krita libs (KisImage/KisPainter/KisPaintOp)
```

| 项 | 值 |
|---|---|
| 包名 | `com.reverie.paint` |
| 语言 | Kotlin 2.4 + C++17 (NDK 25.2) |
| UI | Jetpack Compose (Material3, BOM 2026.05.00), 无 XML 布局 |
| 构建 | Gradle 9.5 + AGP 9.x (内置 Kotlin 支持, 无需 kotlin-android 插件) |
| 工具链 | JDK 17, compileSdk 36, targetSdk 33, minSdk 23 |
| ABI | 仅 arm64-v8a |
| 许可 | GPL-3.0 (C++ 文件必须带 SPDX 头) |

## 2. 构建与验证命令

### 构建模式

- **预编译模式 (默认)**: 使用 `third_party/android-native-libs` 内置动态库, 不编译 C++。克隆后先执行 `./scripts/copy_jni_libs.sh`, 然后 `./gradlew assembleDebug` 即可。
- **buildNative 模式 (开发者)**: 重新编译 C++, 需要本地 Qt for Android 6.6.3 + Krita 源码 + KF6。执行 `./scripts/build_native.sh` 或 `./gradlew assembleDebug -PbuildNative`。

### 常用命令

```bash
./gradlew :app:compileDebugKotlin    # 只编译 Kotlin (最快验证, 改代码后必跑)
./gradlew :app:testDebugUnitTest     # 运行 JVM 单元测试
./gradlew :app:lintDebug             # Android Lint
./gradlew assembleDebug              # 完整 Debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**改动 Kotlin 代码后至少运行 `compileDebugKotlin`; 改动 `model/` 或纯逻辑代码后运行 `testDebugUnitTest`。**

## 3. 目录结构

```
app/src/main/java/com/reverie/paint/
├── MainActivity.kt          # 应用入口 + 页面路由 (Page.HOME/CREATE/PAINTING/REPLAY)
├── core/                    # 引擎桥接层 (不含 Compose UI)
│   ├── ReverieCoreBridge.kt #   JNI 外部函数声明 (唯一 JNI 边界)
│   ├── PaintViewModel.kt    #   主 ViewModel (UI 状态 + 引擎调用)
│   ├── PaintViewModel{Brush,Layers,Tools,Filters,Projects,Shortcuts}.kt
│   │                        #   ViewModel 扩展文件 (同包同类的 partial 模式)
│   ├── PaintRecorder.kt     #   绘制过程录制 (紧凑二进制事件流)
│   ├── PlaybackEngine.kt    #   回放会话与渲染线程
│   └── RecordingEvents.kt   #   事件编解码 (零分配热路径)
├── model/                   # 纯 Kotlin 数据模型 (无 Android 依赖, 可单测)
└── ui/
    ├── theme/               # 莫兰迪主题 (Morandi 色板 + 语义色)
    ├── home/                # 主页: 最近项目/新建入口/设置子页
    ├── create/              # 创建画布页 (尺寸预设/背景色)
    ├── components/          # 跨页面复用的通用组件
    ├── dialog/              # 全局对话框 (赞助/贡献者)
    ├── replay/              # 回放页
    └── painting/            # 绘画页 (按职责分子包)
        ├── PaintingPage.kt  #   页面编排根 (唯一由 MainActivity 直接引用的入口)
        ├── TopBar.kt / PaintingDialogs.kt / ToolbarCustomizeDialog.kt
        ├── ExportTabPage.kt / ReferenceWindow.kt
        ├── canvas/          #   CanvasView 手势绘画 / CanvasOverlay / 变换状态
        ├── layers/          #   图层树/图层详情/滤镜页/曲线图/渐变编辑器
        ├── brush/           #   笔刷面板 + 笔刷工坊 (BrushStudio)
        └── panels/          #   工具栏/工具属性面板/取色器/设置面板/选区浮窗

app/src/main/cpp/            # C++ 引擎 (按域拆分, 与 Kotlin 一一对应)
├── ReverieCore.h            #   引擎主头文件 (SPDX GPL-3.0 必需)
├── ReverieCoreInternal.h    #   内部共享声明
├── ReverieCore{Stroke,Brush,Layers,LayerOps,Filters,FilterPreview,
│   Selection,SelectionTools,SelectionLasso,Transform,Render,IO,
│   Document,MiscTools}.cpp  #   按域拆分的实现
├── reverie_jni_common.h     #   JNI 公共工具 (异常/位图/字符串转换)
├── reverie_jni_{core,brush,layers,filters,io,selection,tools}.cpp
└── CMakeLists.txt           #   链接 Krita/Qt/KF6

其他目录:
docs/       # 中文开发文档; PROGRESS.md 为推进日志, 完成里程碑时更新
scripts/    # build_native.sh (完整构建) / copy_jni_libs.sh (预编译库拷贝)
third_party/android-native-libs/   # 预编译动态库闭包 (arm64-v8a, 已 strip)
art/        # 图标素材
```

### 新文件放置规则

- 新增**工具属性面板** → `ui/painting/panels/`
- 新增**图层相关 UI** → `ui/painting/layers/`
- 新增**画布渲染/手势/覆盖层** → `ui/painting/canvas/`
- 新增**纯数据模型/枚举** → `model/`
- 新增**JNI 方法**: Kotlin 侧加到 `ReverieCoreBridge.kt`, C++ 实现加到对应域的 `reverie_jni_*.cpp` (没有对应域就新建 `reverie_jni_<域>.cpp` 并加入 CMakeLists)

## 4. 架构铁律 (违反会导致卡顿/崩溃回归)

1. **文档真身在 C++**: 所有文档操作 (笔画/图层/撤销/渲染) 通过 JNI 进入 ReverieCore。Kotlin 侧只持有 UI 状态镜像 (`PaintViewModel` 的 `mutableStateOf` 字段), 不要在 Kotlin 侧复制像素数据。
2. **引擎调用禁止上 UI 线程**: Krita 投影重组合在真机上耗时 5ms+, 会阻塞触摸分发导致笔画成段。所有文档操作走专用 HandlerThread (见 `PaintViewModel` 头注释)。
3. **双缓冲渲染**: 写像素的线程与 Compose 读取的 Bitmap 必须是两个对象 (front/back buffer), 新增渲染路径必须维持此模式。
4. **热路径零分配**: 笔画事件批处理、`RecordingEvents` 编解码、JNI 笔画提交路径上禁止每帧创建对象/字符串; 触摸采样以 ~8ms 批量 flush。
5. **手势处理不 key 在视口状态上**: `pointerInput` 不能以 zoom/pan/rotation 为 key, 否则手势第一帧后被取消 (见 `CanvasView` 头注释的历史教训)。
6. **单实例引擎**: `g_core` 全局唯一, UI 单窗口假设; JNI 方法不做重入防护, 调用时序由 ViewModel 保证。
7. **Qt 无 GUI 运行**: 引擎是软件渲染, 首次使用时惰性创建 `QCoreApplication`; 禁止引入任何 QWidget 依赖。

## 5. 解耦与健壮性要求

新增或修改功能时, 把"对其他部分的影响降到最小"当作硬性要求:

- **依赖单向**: `ui/ → core/ → model/`, 禁止反向依赖; JNI 边界 (`ReverieCoreBridge.kt` ↔ `reverie_jni_*.cpp`) 是唯一跨语言契约, 改任何一侧必须同步另一侧并全文检索签名引用。
- **先查波及面再动手**: 修改共享代码 (`PaintViewModel*`、`ui/theme`、`ui/components`、`ReverieCore.h`、`ReverieCoreInternal.h`、`reverie_jni_common.h`) 前, 必须先搜索全部调用点, 列出可能受影响的功能清单, 再决定改动方式。
- **扩而不改**: 给既有能力加可选行为时, 优先新增带默认值的参数/方法或新建扩展文件 (ViewModel partial 模式), 不改变既有签名与调用方; 能加新函数就不要在既有热路径上堆分支。
- **单一职责**: 新功能优先作为独立单元 (独立 Composable / 独立域文件 / 独立扩展文件) 插入, 不塞进已有的大函数; 单文件超限时按 §6 的拆分规则处理。
- **C++ 域隔离**: 跨域复用走 `ReverieCoreInternal.h` 的声明接口, 不直接 include 其他域的实现细节; JNI 层只做参数搬运。
- **健壮性底线**: 新代码必须处理边界输入 (空图层/空选区/0 尺寸/越界坐标), JNI 调用点检查返回值与异常; 失败时保持 UI 状态一致 (回滚状态镜像), 不允许半更新。

## 6. Kotlin / Compose 编码规范

- 缩进 4 空格, 行宽 ≤120 (见 `.editorconfig`)。
- **UI 文案直接写中文字符串字面量** (项目现状, 未接资源国际化); 主题色一律引用 `ui/theme` 的 Morandi 语义色, 禁止散落硬编码颜色。
- 状态管理: ViewModel 用 `mutableStateOf/mutableIntStateOf/...` + `by` 委托; 高频数值 (缩放%/旋转°) 用专用 `mutable*StateOf` 重载避免装箱。
- ViewModel 过大时按域拆分为同包扩展文件 (如 `PaintViewModelBrush.kt`), 保持类名不变, 不要为拆分而引入新接口层。
- Composable 命名 PascalCase, 普通函数 camelCase; 一个功能族一个文件是允许的 (如 `ToolPanels.kt` 收纳多个小面板), 但单文件超过 ~2000 行时应拆分。
- import 禁止保留通配符以外的无用导入; 允许 `import com.reverie.paint.core.*` 这类同模块通配 (现状惯例)。
- 协程: UI 侧用 `viewModelScope`; 引擎线程通信走 Handler (与现有 `HandlerThread` 模式一致), 不要混用两套线程模型。

## 7. C++ 编码规范

- 每个新 `.h/.cpp` 文件顶部必须有 `SPDX-License-Identifier: GPL-3.0-or-later` 注释头。
- 编译选项 `-fno-operator-names` 已全局开启 (Krita 头文件把 `and/or/xor` 当标识符), 不要依赖这些关键字。
- 文件按域拆分: 新功能优先加到既有域文件; 只有独立域才新建 `ReverieCore<域>.cpp` 并同步更新 `ReverieCoreInternal.h`。
- 命名: 类/函数 Krita 风格 (camelCase 方法, `m_` 成员前缀); JNI 函数命名 `Java_com_reverie_paint_core_ReverieCoreBridge_<name>`。
- JNI 边界统一使用 `reverie_jni_common.h` 的工具函数做字符串/位图/异常转换; JNI 层只做参数搬运, 业务逻辑放 ReverieCore。
- 内存: JNI 返回的 Bitmap 由 Kotlin 侧复用 (双缓冲), C++ 侧不长期持有 Android Bitmap 引用。

## 8. 测试规范

- 单元测试位于 `app/src/test/java/`, 仅覆盖**纯 Kotlin 逻辑** (`model/`、编解码、几何计算等), 不引入 Android 框架依赖。
- 测试框架 JUnit4, 用反引号方法名描述行为 (见 `PaintModelsTest`)。
- C++ 引擎逻辑暂无自动化测试; 涉及引擎的修复需在真机手动回归并在 commit message 中注明验证结果。
- 新增可纯测的逻辑 (坐标变换、事件编解码、LUT 计算等) 应补对应单元测试。

## 9. 变更工作流与回归自检

每次新增或修改功能, 必须完整走一遍以下流程:

1. **改动前 — 影响评估**: 定位目标域 → 全文搜索调用点 → 按 §5 列出"可能受影响的功能清单"; 同时核对 §4 铁律是否触及 (线程模型/双缓冲/零分配/手势 key)。
2. **改动中 — 最小 diff**: 只改与目标相关的代码, 不顺手重构无关部分; 保持每个中间步骤可编译。
3. **改动后 — 两轮自检**:
   - **第一轮 (机械验证)**: 按改动范围运行 §2 命令 — 至少 `compileDebugKotlin`; 改 `model/` 或纯逻辑加跑 `testDebugUnitTest`; 大范围改动加跑 `lintDebug` 与 `assembleDebug`。
   - **第二轮 (回归审查)**: 对照第 1 步的影响清单, 逐一重新检查每个调用方行为是否被破坏; 重点确认新代码未违反 §4 铁律。发现问题必须修复后重跑第一轮。
   - **引擎/C++ 改动**: 额外真机手动回归受影响路径 (笔画/图层/撤销/滤镜等), 结果写入 commit body。
4. **收尾**: 按需更新 §11 文档; commit 信息按 §10 规范注明验证方式。

## 10. Git 提交规范

Conventional Commits, **中文描述**, 格式:

```
<type>(<scope>): <subject>
```

- type: `feat` / `fix` / `refactor` / `perf` / `docs` / `build` / `revert`
- scope 用小写域名, 可多域: `brush` `layer(s)` `render` `stroke` `filter` `selection` `ui` `jni` `transform` `roundmarker` 等
- subject 一句话说明行为结果而非过程; 修复类提交在 body 里写清根因与验证方式
- 示例: `fix(roundmarker): 圆头笔刷边缘像素改用 COMPOSITE_OVER 混合替代裸 memcpy, 消除笔画交叉处的白边`

## 11. 文档规范

- `docs/PROGRESS.md`: 完成里程碑/大特性时更新勾选项与日期。
- 研究结论、实验记录写入 `docs/<TOPIC>.md` (如 `FILTER-LAYER-RESEARCH.md`), 回滚实验时必须在文档记录发现。
- README.md 面向用户/构建者, 保持构建说明与脚本行为同步 (改了构建流程必须同步 README)。

## 12. 安全与机密

- `local.properties` 含签名/赞助 API token, 已被 .gitignore 排除, **严禁提交或复制其内容到其他文件**。
- 机密注入顺序: gradle property → 环境变量 → local.properties (见 `app/build.gradle.kts`); CI 通过 GitHub Secrets 写入。
- 发布签名当前复用 debug 签名 (release buildType), 变更前需与维护者确认。
