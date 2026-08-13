<div align="center">

# ReveriePaint

<p>
  <a href="https://qm.qq.com/q/mtg1yNCi1q"><img alt="QQ" src="https://img.shields.io/badge/QQ-729283213-12B7F5?style=for-the-badge&logo=qq&logoColor=white"></a>
  <a href="https://afdian.com/a/LanRhyme" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/afdian-@LanRhyme-946ce6?style=for-the-badge&logo=afdian&logoColor=white" alt="afdian"></a>
</p>

基于 Krita 核心引擎的 Android 原生绘画应用, 纯 Compose UI + Krita C++ 引擎, 画世界 Pro 风格界面

</div>

## 功能特性

- **完整 Krita 笔刷引擎**: 内置 248 个 Krita 官方预设（圆头/铅笔/墨线/水彩/喷枪/纹理等）, 真实压力/间距/纹理渲染
- **完整工具集**: 笔刷/橡皮/混合/吸管/填充/渐变/套索/魔棒/矩形/椭圆/多边形/文字/液化/移动/裁剪 等 20+ 工具
- **完整图层系统**: 图层组/锁定/透明度/25 种混合模式/剪贴蒙版/独显/翻转/合并/重命名/颜色标记
- **选区系统**: 套索/矩形/椭圆/多边形/连续/相似色, 替换/加/减/交模式, 羽化/扩展/收缩/平滑
- **图层树拖拽**: 长按拖拽排序与归组, 滑动展开复制/独显/删除
- **项目保存加载**: PNG 导出, 主页最近项目缩略图
- **画布手势**: 单指压感绘画, 双指缩放/旋转/平移
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

```bash
# 1 准备 Krita 原生库 (二选一)
#    已有本地交叉编译: 直接执行
#    没有: 使用仓库内置的预编译库 (third_party/krita-android-libs, 免编译 Krita)
./scripts/copy_jni_libs.sh
# 2 构建 APK
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

预编译 Krita 动态库已提交到仓库 third_party/krita-android-libs (57 个核心库 + 13 个笔刷引擎插件, arm64-v8a, 已 strip)

## 许可

- 应用本体 GPL-3.0
- 绘画核心复用 Krita (GPL-3.0), 见 [kde/krita](https://invent.kde.org/graphics/krita)
- 图标来自 [Tabler Icons](https://tabler.io/icons) (MIT)
