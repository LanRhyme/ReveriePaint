# ReveriePaint 贡献与开发指南

欢迎参与 ReveriePaint 项目的开发与贡献, 本文档旨在为开发者提供清晰的技术架构概览、本地环境配置说明、构建调试流程以及编码规范

---

## 技术架构

ReveriePaint 采用现代 Android 技术栈与桌面级图像处理内核深度结合的架构设计:

```
Kotlin/Compose UI (UI 呈现与交互状态)
      │
     JNI (ReverieCoreBridge 高效双向桥接)
      │
 C++ ReverieCore (文档调度 / 录制引擎 / 选区滤镜)
      │
 Krita 核心引擎库 (KisImage / KisPainter / KisPaintOp / KisPaintDevice)
```

| 模块 | 说明 |
|---|---|
| 包名 | `com.reverie.paint` |
| UI 框架 | Jetpack Compose (Material3, BOM), 纯声明式 UI 无 XML 布局 |
| 语言 | Kotlin 2.4 + C++17 (NDK 25.2.9519653) |
| 构建系统 | Gradle 9.5 + AGP 9.x |
| 工具链 | JDK 17, compileSdk 36, targetSdk 33, minSdk 23 |
| 目标架构 | 仅针对 64 位 ARM (`arm64-v8a`) 进行深度性能调优 |
| 开源许可 | GPL-3.0 (C++ 文件包含 SPDX 头声明) |

---

## 目录结构

```
app/src/main/java/com/reverie/paint/
├── MainActivity.kt          # 入口与顶层路由 (主页/创建/绘画/回放)
├── core/                    # 引擎桥接与业务逻辑核心
│   ├── ReverieCoreBridge.kt #   JNI 唯一交互边界
│   ├── PaintViewModel.kt    #   主 ViewModel 状态机
│   ├── PaintViewModel*.kt   #   按功能域划分的同包扩展 (笔刷/图层/工具/滤镜/工程)
│   ├── PaintRecorder.kt     #   绘制过程录制器 (紧凑二进制流)
│   ├── PlaybackEngine.kt    #   过程回放会话与调度线程
│   └── RecordingEvents.kt   #   事件高效编解码器 (零分配热路径)
├── model/                   # 纯 Kotlin 数据模型 (独立单测, 无 Android 依赖)
└── ui/
    ├── theme/               # 莫兰迪主题系统与色彩定义
    ├── home/                # 主页界面、最近工程画廊与设置子页
    ├── create/              # 创建画布页
    ├── replay/              # 录制回放播放器
    └── painting/            # 绘画核心交互区 (画布/工具栏/图层树/笔刷面板)

app/src/main/cpp/            # C++ 原生引擎层
├── ReverieCore.h            #   引擎主接口
├── ReverieCoreInternal.h    #   内部共享结构
├── ReverieCore*.cpp         #   按领域拆分的引擎实现 (笔画/图层/选区/变换/渲染/IO)
├── reverie_jni_common.h     #   JNI 公共转换工具 (位图/内存/异常)
├── reverie_jni_*.cpp        #   按功能域拆分的 JNI 实现
└── CMakeLists.txt           #   CMake 构建配置与 Krita/Qt/KF6 依赖链接

third_party/android-native-libs/   # 预编译动态库闭包 (arm64-v8a, 已 strip)
scripts/                           # 构建与动态库同步辅助脚本
```

---

## 环境准备与构建流程

### 方式一: 预编译模式 (推荐, 适用于 UI 与应用层开发)

无需配置复杂的 Qt / Krita C++ 交叉编译工具链, 直接使用仓库内置的预编译闭包动态库即可快速构建:

```bash
# 1 准备依赖库 (将 prebuilt 库拷贝至 jniLibs 目录)
./scripts/copy_jni_libs.sh

# 2 快速验证 Kotlin 代码编译
./gradlew :app:compileDebugKotlin

# 3 运行单元测试
./gradlew :app:testDebugUnitTest

# 4 组装 Debug APK
./gradlew assembleDebug

# 5 安装至连接的 Android 设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 方式二: Native 编译模式 (适用于修改 C++ 引擎代码)

修改 `app/src/main/cpp/` 源码时, 需本地配置交叉编译环境:

- Qt for Android 6.6.3 (`android_arm64_v8a`) 以及对应宿主 `QT_HOST_PATH`
- Krita 交叉编译源码产物与头文件
- KF6 6.6.0 arm64 交叉编译库

```bash
# 执行完整 Native 编译与打包脚本
./scripts/build_native.sh

# 或通过 Gradle 属性直接传递参数编译
./gradlew assembleDebug -PbuildNative
```

---

## 开发规范与核心约束

### 1. 架构原则
- **文档真实状态保留在 C++**: 所有图像数据、图层树、撤销栈与栅格数据由 C++ 引擎持有, Kotlin 侧仅维护轻量 UI 状态镜像, 禁止在 Kotlin 内存中冗余拷贝全幅像素
- **主线程非阻塞调用**: 所有涉及图像重组、滤镜计算或耗时 I/O 操作均通过后台专用工作线程执行, 严禁阻塞主线程触摸事件分发
- **双缓冲渲染机制**: 保持渲染后台缓冲区与 Compose 界面消费的前台 Bitmap 独立分离
- **热路径零分配**: 笔画提交、高频手势采样与录制事件编解码路径禁止高频创建临时对象

### 2. 代码风格
- **Kotlin**: 遵循官方 Kotlin 编码规范, 缩进 4 空格, 单行长度建议 ≤ 120 字符
- **Compose**: 状态管理推荐使用 `mutableStateOf` / `mutableIntStateOf` 等基本类型重载避免拆装箱; UI 界面色系统一引用 `Theme.current` 语义色
- **C++**: 遵循 Krita 代码规范 (`m_` 成员前缀, camelCase 方法名), 新增文件头部须附带 GPL-3.0-or-later SPDX 许可标识声明

### 3. 测试要求
- 纯逻辑、数据解析、几何计算与坐标变换代码应补充 JUnit 单元测试 (`app/src/test/java/`)
- 修改代码后提交前至少运行并通过 `./gradlew :app:testDebugUnitTest`

### 4. Git 提交规范
采用 Conventional Commits 格式, 提交信息使用中文描述:

```
<type>(<scope>): <subject>
```

- `type`: `feat` / `fix` / `refactor` / `perf` / `docs` / `build` / `revert`
- `scope`: `brush` / `layers` / `render` / `stroke` / `filter` / `selection` / `ui` / `io` / `theme` / `native` 等小写功能域
- 示例: `feat(theme): 主题设置中增加画布工作区背景颜色修改支持`
