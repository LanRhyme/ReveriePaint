# ReveriePaint UI 优化提示词

你是资深移动端 UI 设计师兼 Jetpack Compose 工程师,为绘画应用 **ReveriePaint** 做 UI 全面优化

## 项目背景

ReveriePaint 是一款 Android 原生绘画应用,技术栈为 **Kotlin + Jetpack Compose 前端 + JNI 桥接 + Krita C++ 绘画内核**(Krita 是 GPL 开源绘画引擎,内核已实现真实笔画、图层、混合模式、13 种工具)
目标 UI 风格:参考 **画世界 Pro** 和 **Procreate** 的界面设计——高级、精致、暗色扁平、流畅动画
当前 UI 已有基础骨架但视觉粗糙,需要你把它提升到专业级,并把尚未实现功能的 UI 占位做好

## 硬性约束(必须遵守)

1. **禁止硬编码颜色** 所有颜色必须从 `Theme.current` 读取(文件 `app/src/main/java/com/reverie/paint/ui/theme/Theme.kt`),未来要支持主题切换
   现状 `Theme.current` 是 Morandi 莫兰迪低饱和配色,你可以优化配色方案但必须通过修改 Theme.kt 的 `MorandiColors` 实例实现,禁止在组件里写死 `Color(...)`
2. **组件化** 所有可复用 UI 元素(按钮、滑块、开关、面板、弹窗、选择器)必须放进组件库 `app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt`,以 `Re` 前缀命名,页面代码只负责组合
   现有组件:`ReIconButton`(图标按钮,含选中态)、`ReButton`(主/次文字按钮)、`ReVerticalSlider`(竖向胶囊滑块)、`ReSlider`(横向滑块)、`ReSwitch`(开关)、`ReColorDot`(色块)、`ReSectionTitle`(小节标题)、`RePanel`(底部弹窗面板,含遮罩+标题+关闭)、`ReSettingRow`(设置行)、`ReChip`(选择胶囊)、`ReTextInput`(输入框)
3. **保持功能不变** 不要改 C++ 内核、JNI 桥、PaintViewModel 的业务逻辑和已接通的交互(CanvasView 的手势/笔画逻辑绝对不要动),只做 UI 层
4. **触控目标 ≥44dp** 最小可点区域,图标按钮、滑块等
5. 触摸设备上的动画要轻(150-300ms 淡入/位移动画),不要重动画

## 文件结构(先读这些)

```text
app/src/main/java/com/reverie/paint/
  MainActivity.kt            入口
  core/PaintViewModel.kt     状态(画笔、图层、工具、项目、撤销)不要改业务方法
  model/PaintModels.kt       Tool 枚举(13 工具)、BrushPreset、Project、CanvasPreset
  ui/theme/Theme.kt          AppColors 主题 + 预设数据
  ui/components/ReComponents.kt  组件库(重点扩展)
  ui/home/HomePage.kt        主页
  ui/create/CreatePage.kt    新建画布页
  ui/painting/               绘画页
    PaintingPage.kt          布局编排(顶栏+工具列+画布+面板+变换指示器)
    TopBar.kt                顶栏(返回/撤销/重做/旋转/缩放/图层/设置)
    ToolRail.kt              左侧工具列(13 工具 + 竖向笔刷/不透明滑块)
    CanvasView.kt            画布(不要改)
    BrushPanel.kt            笔刷面板(预设+大小+不透明)
    ColorPanel.kt            取色面板(HSV 色轮)
    LayerPanel.kt            图层面板(右上角下拉)
    SettingsPanel.kt         设置面板
res/drawable/                Tabler 图标(ic_*.xml 矢量)
```

## 要做的优化

### A. 视觉整体提升(参考画世界 Pro / Procreate)

1. 顶栏与左工具列:画世界 Pro 是**连成一体的深色面板**(顶栏和左栏无缝隙连接,左栏底部收纳笔刷大小/不透明度滑块),当前是分离的两块,请合并设计
2. 工具列:当前工具图标 46dp 方格 + 选中蓝底,参考 Procreate 的精致图标按钮(选中时高亮、图标描边细)
3. 面板:底部弹窗(笔刷/颜色/设置)和右上角图层面板,加层次感(背景、圆角、留白、分组间距、图标)
4. 变换指示器(缩放/旋转百分比提示)样式精致化
5. 主页:专业绘画 App 首页(Logo、新建、最近项目卡片带缩略图圆角、空态引导)
6. 新建画布页:尺寸预设卡片化、背景色选择、自定义尺寸输入框样式
7. 全局:统一圆角、统一 44dp 触控、统一图标间距、配色微调(Morandi 基础上)

### B. 未实现功能的 UI 占位(方便之后接功能)

先检查 PaintViewModel/ReverieCoreBridge 是否已有方法,有就接通,没有就做 UI 占位(点击不崩溃,显示"开发中"提示或空面板)

1. 笔刷面板扩展:硬度、流量、间距、笔刷形状预览(圆点预览当前笔刷大小)
2. 图层面板扩展:混合模式(已有部分)、锁定、复制、合并、重命名、缩略图
3. 选区工具:套索/魔棒使用时的浮动操作条(填充/描边/删除/取消选择)
4. 文字工具面板:字体、字号、颜色、对齐
5. 变换工具面板:移动、缩放、旋转、翻转
6. 液化面板:强度、笔刷大小滑块
7. 设置面板扩展:画布网格开关、对称绘制、手势设置、导出/分享、应用信息
8. 主页:项目排序、搜索、重命名、删除、多选
9. 新建画布:更多背景预设(纯色/透明/图案)

### C. 动画与动效(轻量)

1. 面板弹出:底部弹窗从下方滑入(RePanel 加 AnimatedVisibility slideInVertically)
2. 面板关闭:反向滑出
3. 工具选中:瞬时高亮变化即可,不要缩放弹跳
4. 变换指示器:淡入淡出(现有 1.2s 自动隐藏保留)

## 交付要求

1. 所有新组件进 ReComponents.kt(或按需拆分 ReComponents 目录),页面复用
2. 不破坏现有编译:改完必须能 `./gradlew assembleDebug` 通过
3. 不引入新依赖(用 Compose 自带 API)
4. 改完列出:改了哪些文件、新增哪些组件、哪些是 UI 占位、哪些接通了真实功能
5. 不要动:CanvasView.kt 的坐标/手势逻辑、C++ 内核、JNI、PaintViewModel 业务方法签名

## 参考图

我会给你画世界 Pro / Procreate 的界面截图,请对照这些截图调整配色、布局、间距、图标风格,但不要照搬它的图标(我们用 Tabler 图标),重点是布局与质感的借鉴
