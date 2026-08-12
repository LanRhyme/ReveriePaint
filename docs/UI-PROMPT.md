# ReveriePaint UI 全面优化提示词

你是资深移动端 UI 设计师兼 Jetpack Compose 工程师,为绘画应用 **ReveriePaint** 做 UI 全面优化

用户会提供 **画世界 Pro** 和 **Procreate** 的界面参考截图,请逐张对照,把 ReveriePaint 的每个页面提升到同等质感

## 项目背景

ReveriePaint 是 Android 原生绘画应用,技术栈为 **Kotlin + Jetpack Compose 前端 + JNI 桥接 + Krita C++ 绘画内核**
内核已实现:13 种工具(画笔/手/橡皮/取色/填充/套索/魔棒/直线/矩形/椭圆/文字/涂抹/液化)、真实笔画(压感/样条平滑)、**完整图层系统**(背景层/颜料图层/图层组/25 种混合模式/锁定/锁定透明度/不透明度/颜色标记/独显/复制/清除/重命名/翻转/向下合并/剪切蒙版/滤镜灰度反色模糊锐化/从图层创建选区)、撤销重做、项目保存加载(PNG)
目标风格:画世界 Pro + Procreate 的高级感——精致、暗色扁平、层次分明、流畅轻动效
当前 UI 骨架已有但视觉粗糙,需要你把它提升到专业级,并把尚未实现功能的 UI 占位做好

## 硬性约束(必须遵守,违反即返工)

1. **禁止硬编码颜色** 所有颜色必须从 `Theme.current` 读取(`ui/theme/Theme.kt`),未来支持主题切换
   现状 `Theme.current` 为 Morandi 莫兰迪低饱和配色,可优化配色方案,但只能通过修改 Theme.kt 的 `MorandiColors` 实现
   组件内禁止写死 `Color(...)`
2. **组件化** 所有可复用 UI 元素(按钮/滑块/开关/面板/弹窗/下拉/选择器)必须放进组件库 `ui/components/ReComponents.kt`,以 `Re` 前缀命名,页面只负责组合
   现有组件:`ReIconButton`(图标按钮含选中态)、`ReButton`(主/次按钮)、`ReVerticalSlider`(竖向胶囊滑块)、`ReSlider`(横向滑块)、`ReSwitch`、`ReColorDot`、`ReSectionTitle`、`RePanel`(底部弹窗面板)、`ReSettingRow`、`ReChip`、`ReTextInput`
   需要时新增 `ReDropdown`(下拉选择器)、`ReSegmentedButton`(分段选择)等
3. **绝对禁止改动这些文件**:C++ 内核(`app/src/main/cpp/`)、JNI 桥 `core/ReverieCoreBridge.kt`、`core/PaintViewModel.kt` 的业务方法、`ui/painting/CanvasView.kt` 的手势与笔画逻辑
   UI 面板可以调用现有 ViewModel 方法,但不能改方法签名或行为
4. **触控目标 ≥44dp** 图标按钮、滑块、可点区域
5. 动画轻(150-300ms 淡入/位移动画),不要重动画
6. 所有界面文字用中文(应用名 ReveriePaint 除外)

## 文件结构(先全部读完再动手)

```text
app/src/main/java/com/reverie/paint/
  MainActivity.kt                入口,组合式导航 Home/Create/Painting
  core/PaintViewModel.kt         状态:画笔/图层/工具/项目/撤销(业务方法只读,别改)
  core/ReverieCoreBridge.kt      JNI 声明(别动)
  model/PaintModels.kt           Tool 枚举(13 工具)/BrushPreset/Project/CanvasPreset
  ui/theme/Theme.kt              AppColors + Morandi 配色(可优化配色)
  ui/components/ReComponents.kt  组件库(重点扩展)
  ui/home/HomePage.kt            主页:最近项目、新建画布入口
  ui/home/SettingsPage.kt        设置页
  ui/create/CreatePage.kt        新建画布:尺寸预设/自定义宽高/背景色
  ui/painting/PaintingPage.kt    绘画页编排:顶栏/工具轨/画布/各面板开关
  ui/painting/TopBar.kt          顶栏:返回/撤销/重做/旋转/缩放/图层/设置
  ui/painting/ToolRail.kt        左侧工具轨:13 工具 + 竖向笔刷大小/不透明度滑块
  ui/painting/CanvasView.kt      画布手势与笔画(绝对禁止改动)
  ui/painting/LayerPanel.kt      图层面板(重点优化对象)
  ui/painting/BrushPanel.kt      笔刷设置底部面板
  ui/painting/ColorPanel.kt      取色面板(HSV 色轮)
  ui/painting/SettingsPanel.kt   画布设置底部面板(3 个 tab)
```

## 各页面现状与用户痛点

### 绘画页(最核心,优先)

- 布局:左侧 60dp 工具轨 + 顶部操作栏 + 全屏画布 + 底部弹出面板
- 用户反馈的整体问题:**UI 观感粗糙、AI 味重、不够高级**,需要对照参考图全面提升
- 工具轨:13 个 Tabler 图标按钮竖排,底部两个竖向胶囊滑块(笔刷大小/不透明度),当前工具高亮
- 顶栏:返回/撤销/重做/旋转/缩放/图层/设置,文档名居中
- 变换指示器:双指缩放旋转时画布上浮层显示"缩放 x%,旋转 x°",1.2s 后淡出

### 图层面板(重点优化)

- 位置:屏幕右侧 300dp 悬浮面板,圆角 14dp,半透明毛玻璃感
- 行布局:颜色标记条(3dp)→ 40dp 缩略图(白色浅灰网格底)→ 可见性眼睛 → 组缩进 → 组图标 → 名称 → 锁定/透明度锁标记 → 更多按钮
- 左滑操作:整行平滑滑开(220ms),露出右侧三等分彩色抽屉(复制=蓝/独显=青/删除=红,图标+小字),与行融为一体,不点按不显示
- 点击行:右侧滑入二级面板(带返回按钮),包含不透明度水平滑块、混合模式下拉(25 种)、滤镜下拉(灰度/反色/模糊/锐化)、颜色标记 9 色块、锁定透明度/剪切蒙版开关、操作胶囊(清除/重命名/复制/删除/水平翻转/垂直翻转/向下合并/创建选区)
- 顶部工具:添加图层/添加图层组/关闭
- 缩略图:图层内容实时预览(笔画结束/撤销/增删图层后刷新),透明区域显示白色浅灰网格

### 主页

- 最近项目缩略图网格(从 PNG 文件读)
- 新建画布按钮
- 需要更专业的排版:项目卡片/空状态/标题层级

### 新建画布页

- 6 个尺寸预设(手机/方形/横屏/A4/壁纸/2K)+ 自定义宽高 + 背景色色板

### 底部面板(笔刷/取色/设置)

- 笔刷面板:预设列表、大小/不透明度/颜色
- 取色面板:HSV 色轮 + 亮度 + 预设色板 + 前景/背景切换
- 设置面板:3 个 tab(画布/导出/设置)

## 未实现功能的 UI 占位(做好布局,事件可留空)

- 笔刷硬度/流量滑块(数据暂无,先做好 UI)
- 图层更多操作:批量选择、排序
- 选区浮动工具栏(移动/缩放选区)
- 文字工具编辑面板(输入/字体/大小/颜色)
- 变换工具面板(自由变换/缩放/旋转)
- 液化参数面板(半径/强度)
- 网格/参考线开关
- 导出面板:格式(PNG/JPG)、质量、分辨率
- 主页:搜索/排序/删除项目
- 新建画布:更多背景预设(纯色/渐变/透明)

## 视觉要求

- 对照用户提供的画世界 Pro / Procreate 截图逐页对齐:间距节奏、圆角体系、图标尺寸、层次阴影(或扁平)、选中态表达
- 暗色扁平为主,莫兰迪低饱和配色,强调色克制
- 圆角/间距/字号统一成 token 体系(可扩展 ReComponents.kt 的 Dimens)
- 图标统一用 Tabler 风格(现有 drawable 是 Tabler 转换的 vector,`R.drawable.ic_*`)
- 空状态、加载态、弹窗过渡都要有设计

## 交付要求

1. 分页面列出你改动的文件与改动要点
2. 每个页面对照参考图自查一遍,列出仍不一致之处
3. 不改内核/手势/业务方法,只做 UI 层
4. 改动后项目必须能编译通过
