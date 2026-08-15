<div align="center">

<img src="art/icon.png" width="128" height="128" alt="ReveriePaint Icon" />

# ReveriePaint

<p>
  <a href="https://afdian.com/a/LanRhyme" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/afdian-@LanRhyme-946ce6?style=for-the-badge&logo=afdian&logoColor=white" alt="afdian"></a>
</p>

基于 Krita 核心引擎的 Android 原生绘画应用, 纯 Compose UI + Krita C++ 引擎, 画世界 Pro 风格交互

</div>

## 功能特性

- **完整 Krita 笔刷引擎**: 内置 248 个 Krita 官方预设（圆头/铅笔/墨线/水彩/喷枪/纹理等）, 真实压力/间距/纹理渲染
- **完整工具集**: 笔刷/橡皮/混合/吸管/填充/渐变/套索/魔棒/矩形/椭圆/多边形/文字/液化/移动/裁剪/透视扭曲 等 20+ 工具
- **完整图层系统**: 稀疏瓦片动态分配/图层组/锁定/透明度/25 种混合模式/剪贴蒙版/独显/翻转/合并/重命名/颜色标记
- **选区系统**: 套索/矩形/椭圆/多边形/连续/相似色, 替换/加/减/交模式, 羽化/扩展/收缩/平滑
- **图层树拖拽**: 长按拖拽排序与归组, 滑动展开复制/独显/删除
- **项目保存加载**: PNG 导出, 主页最近项目缩略图
- **画布手势与高刷插值**: 硬件加速 120 FPS 视口变换, 单指压感绘画, 双指缩放/旋转/平移, 高刷屏子帧触控插值
- **HSV 取色器**: 色环 + 亮度滑杆 + 预设色板
- **莫兰迪主题**: 低饱和配色, 全局语义色

## 技术架构

```
Kotlin/Compose UI ── JNI ── C++ ReverieCore ── Krita libs (KisImage/KisPainter/KisBrushOp)
```

- `app/src/main/java/com/reverie/paint/`
  - `MainActivity.kt` 应用入口与页面路由
  - `core/` JNI 桥接与 ViewModel
    - `ReverieCoreBridge.kt` C++ 引擎的 JNI 声明
    - `PaintViewModel.kt` UI 状态 + 引擎调用
  - `model/` 数据模型 (工具/笔刷/画布预设)
  - `ui/` Compose 界面
    - `theme/` 莫兰迪主题
    - `home/` 主页 (最近项目/新建/打开)
    - `create/` 创建画布页 (尺寸预设/背景色)
    - `painting/` 绘画页
      - `PaintingPage.kt` 页面编排
      - `CanvasView.kt` 画布 (触摸绘画+手势)
      - `TopBar.kt` 顶部操作栏
      - `ToolRail.kt` 左侧工具栏
      - `LayerPanel.kt` 图层面板
      - `BrushPanel.kt` 笔刷面板
      - `ColorPanel.kt` 取色面板
      - `SettingsPanel.kt` 设置面板
- `app/src/main/cpp/`
  - `ReverieCore.h/.cpp` 绘画引擎 (无 QWidget 依赖)
  - `reverie_jni.cpp` JNI 桥
  - `CMakeLists.txt` 链接 Krita/Qt 库

## 构建

### 方式一: 预编译模式 (推荐, 无需任何 C++ 环境)

克隆仓库后仅需 Android Studio / Android SDK + NDK 25 + JDK 17, 动态库全部内置:

```bash
# 1 复制仓库内置的 112 个预编译动态库到 jniLibs
#   (Krita 核心库 + 笔刷引擎 + Qt for Android + KF6 + NDK 依赖 + 预编译 libreverie_jni.so)
./scripts/copy_jni_libs.sh
# 2 构建 APK
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

预编译动态库在 third_party/android-native-libs (arm64-v8a, 已 strip), 包含 libreverie_jni.so 与全部 111 个运行时依赖, 闭包已验证完整

### 方式二: 重新编译 C++ (开发者模式)

修改 C++ 代码 (app/src/main/cpp) 时需要本地编译环境:

- Qt for Android 6.6.3 (android_arm64_v8a) + 桌面版 gcc_64 (作 QT_HOST_PATH)
- Krita 源码树 (~/Projects/krita-source) 与交叉编译产物 (build-android)
- KF6 6.6.0 arm64 交叉编译库 + 各依赖 (见 scripts 与构建记录)

```bash
./scripts/build_native.sh
```

构建脚本会先编译 C++ 再用预编译库补齐 AGP 未收集的间接依赖, 产物与预编译模式一致

本机目录与默认约定不同时, 用 CMake 缓存变量覆盖 (CMakeLists.txt):

```bash
./gradlew assembleDebug -PbuildNative -PcmakeArgs="-DQT_ANDROID_DIR=/opt/Qt6/6.6.3/android_arm64_v8a -DKRITA_SRC_DIR=/path/to/krita-source"
```

预编译模式与 buildNative 模式通过 gradle 属性切换 (app/build.gradle.kts)

## 许可

- 应用本体 GPL-3.0
- 绘画核心复用 Krita (GPL-3.0), 见 [kde/krita](https://invent.kde.org/graphics/krita)
- 图标来自 [Tabler Icons](https://tabler.io/icons) (MIT)
