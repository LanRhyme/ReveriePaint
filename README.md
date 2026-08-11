# ReveriePaint 幻境绘

基于 Krita 核心引擎的安卓绘画应用, UI 使用 Jetpack Compose

## 架构

```
Kotlin/Compose UI ── JNI ── C++ ReverieCore ── Krita libs (KisImage/KisPainter)
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
      - `ToolRail.kt` 左侧工具栏 (工具+滑杆)
      - `LayerPanel.kt` 图层面板
      - `BrushPanel.kt` 笔刷面板
      - `ColorPanel.kt` 取色面板
      - `SettingsPanel.kt` 设置面板
- `app/src/main/cpp/`
  - `ReverieCore.h/.cpp` 绘画引擎 (无 QWidget 依赖)
  - `reverie_jni.cpp` JNI 桥
  - `CMakeLists.txt` 链接 Krita/Qt 库

## 构建

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 功能

- 主页 / 创建画布 / 绘画三页导航
- 画世界 Pro 风格绘画界面: 左侧工具栏, 顶部操作栏, 网格工作区
- 单指绘画 (压感), 双指缩放/旋转/平移
- 图层: 新建/删除/切换/显隐 (Krita KisPaintLayer)
- 笔刷: 预设+大小+不透明度
- 颜色: 预设色板+取色器
- 工具: 画笔/手/橡皮/取色/填充/套索/魔棒/直线/矩形/椭圆/文字/涂抹/液化

## 技术要点

- 绘画引擎复用 Krita 核心库 (kritaimage/kritapigment 等), 绕过 KisDocument/KisPart 直接使用 KisImage 公开构造
- Qt 库仅作为引擎运行时 (QCoreApplication 无界面初始化)
- 画布渲染: C++ 合成文档 → Android Bitmap (零拷贝) → Compose 显示

## 许可

GPL-3.0-or-later (Krita 核心)
