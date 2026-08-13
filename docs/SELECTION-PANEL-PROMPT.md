# 任务:重做 ReveriePaint 的浮动选区面板(Selection Floating Panel)

## 项目背景

ReveriePaint 是一款 Android 原生绘画应用,UI 用 Kotlin + Jetpack Compose 编写,底层画布/选区/图层逻辑用 Krita 的 C++ 库(libreverie_jni.so)通过 JNI 桥接。你**只能修改 UI 层的 Kotlin 文件**,绝对不能改动:

- `app/src/main/cpp/` 下的所有 C++ 文件(ReverieCore.cpp/h、reverie_jni.cpp)
- `app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt`(JNI 声明,external fun)
- 任何画笔/图层/选区数据的原生逻辑

目标 App 风格参考 画世界 Pro 和 Procreate 的移动绘画 UI,整体是莫兰迪(Morandi)低饱和配色,深色扁平风。

## 相关文件

1. `app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt`
   - 绘画页主编排,包含 `SelectionFloatPanel`、`SelectionActionChip`、`SelectionPropSlider` 三个 @Composable(在文件末尾)
   - 面板的显示条件、位置、动画也在这里
2. `app/src/main/java/com/reverie/paint/ui/painting/CanvasView.kt`
   - 画布 + 选区 overlay 的绘制(overlay 是白色 alpha mask 用 `ColorFilter.tint(Morandi.accent.copy(alpha = 0.35f))` 染色的 `drawImage`)+ 实时选区预览 path(拖动时画 `liveSelectionPath`)
   - **只读参考,不要改手势/绘制逻辑**
3. `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt`
   - 状态与操作入口,可以添加 UI 状态但不要动 JNI 调用链
4. `app/src/main/java/com/reverie/paint/ui/theme/Theme.kt`
   - `Morandi.accent`(主题强调色)、`Morandi.panel`、`Morandi.panelHi`、`Morandi.text`、`Morandi.subText`、`Morandi.border` 等语义色
   - **严禁硬编码颜色**,一律用 Morandi 语义色
5. `app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt`
   - 复用组件:`ReIconButton`、`ReButton`、`ReSlider`(胶囊滑块)、`ReVerticalSlider`、`ReChip`、`RePanel` 等

## PaintViewModel 已提供的选区 API(直接调用,不要改签名)

```kotlin
// 状态
vm.selectionMode: Int          // 0=替换 1=加 2=减 3=相交
vm.hasSelection: Boolean
vm.selectionTolerance: Int     // 容差 0-255,默认 24

// 操作
vm.updateSelectionMode(mode: Int)      // 切换选区合并模式
vm.selectAllAction()                   // 全选
vm.invertSelectionAction()             // 反选
vm.clearSelectionAction()              // 清除选区
vm.featherSelection(radius: Int)       // 羽化
vm.expandSelection(px: Int)            // 扩展
vm.contractSelection(px: Int)          // 收缩
vm.smoothSelection(radius: Int)        // 平滑
vm.updateSelectionTolerance(value: Int) // 容差(仅魔棒/相似色工具)
vm.applyTool(toolId: String)           // 切换工具(如 "brush")
```

## 选区工具清单

```kotlin
Tool.SELECT_RECT        // 矩形选择
Tool.SELECT_ELLIPSE     // 椭圆选择
Tool.SELECT_POLYGON     // 多边形选择
Tool.SELECT_MAGNETIC    // 磁性套索
Tool.LASSO              // 套索
Tool.MAGICWAND          // 连续选择(魔棒)
Tool.SELECT_SIMILAR     // 相似色选择
```

## 当前实现的问题(用户反馈)

1. **浮动面板 UI 奇怪、生硬**,显示/消失逻辑有问题,像是随手拼的
2. 面板**默认应出现在屏幕底部**,之前做在右侧中部不对
3. 不同选区工具应有**不同的属性面板**(参考 Krita 工具选项栏):
   - 魔棒(MAGICWAND)/相似色(SELECT_SIMILAR):容差滑块 + 羽化/扩展/收缩/平滑
   - 套索(LASSO)/矩形(SELECT_RECT)/椭圆(SELECT_ELLIPSE)/多边形(SELECT_POLYGON)/磁性(SELECT_MAGNETIC):没有容差,只有羽化/扩展/收缩/平滑(或部分)
4. 面板不要关闭按钮(切工具自动隐藏即可)
5. 面板太大、太碍事,遮挡画布,画选区时容易误触

## 设计要求(参考 Krita 浮动面板 / 画世界 Pro)

1. **底部悬浮条**:紧凑、半透明深色背景(Morandi.panelHi + alpha 0.85 左右)、圆角、贴屏幕底部,不 fillMaxWidth,宽度自适应内容,不遮挡画布中心区域
2. **显示逻辑**:选择工具激活时平滑滑入/淡入,切换到非选择工具平滑消失(AnimatedVisibility + 滑动/淡入动画,时长 150-250ms)
3. **内容结构**(从上到下):
   - 第一行:选区合并模式 4 个按钮(替换/加/减/交),选中项 Morandi.accent 高亮
   - 第二行:按当前工具显示属性:
     - 魔棒/相似色 → 容差滑块(显示当前数值)
     - 其他工具 → 无容差
     - 羽化/扩展/收缩/平滑:可做成"属性"展开/收起,或直接用小滑块+数值行,样式参考 Krita 工具选项的紧凑感
   - 第三行:全选 / 反选 / 清除(清除用危险红色 0xFFB05552 或 Morandi 风格的红)
4. **可拖动**:面板可以用手势拖动到其他位置(像 Krita 的浮动面板),拖动时显示位置记忆,初始在底部居中
5. **视觉风格**:与现有 BrushPanel/LayerPanel 一致(胶囊滑块、圆角、莫兰迪色),半透明磨砂感,紧凑,无大块实心背景
6. **不影响画布手势**:面板只占底部边缘,画布中间区域的绘制/选区手势完全不受影响

## 验收标准

1. 选择工具激活 → 底部滑入面板;切回画笔 → 平滑消失
2. 魔棒工具显示容差滑块,套索工具不显示
3. 面板可拖动、不遮挡画布中心
4. 面板所有按钮点击有视觉反馈(Morandi.accent 高亮)
5. 不修改任何 C++ / JNI / CanvasView 手势代码,不改 Morandi 主题色
6. gradle 编译通过(./gradlew assembleDebug --no-daemon)
