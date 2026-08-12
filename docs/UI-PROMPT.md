# ReveriePaint 图层面板优化提示词

你是资深移动端 UI 设计师兼 Jetpack Compose 工程师,为绘画应用 **ReveriePaint** 的**图层面板**做专项优化
用户会提供 **画世界 Pro** 和 **Procreate** 的图层面板参考截图,请逐张对照,把现有图层面板提升到同等质感

## 项目背景

ReveriePaint 是 Android 原生绘画应用,技术栈为 **Kotlin + Jetpack Compose 前端 + JNI 桥接 + Krita C++ 绘画内核**
图层内核已完整实现:背景层(锁定/不可绘)/颜料图层/图层组/25 种混合模式/图层锁定/锁定透明度/不透明度/颜色标记/独显(只显示当前层)/复制/清除/重命名/水平垂直翻转/向下合并/剪切蒙版/滤镜(灰度/反色/模糊/锐化)/从图层创建选区/可见性切换/图层缩略图实时预览
**你只负责图层面板的 UI,不改内核**

## 硬性约束(必须遵守,违反即返工)

1. **只允许修改这几个文件**:`ui/painting/LayerPanel.kt`(图层面板)、`ui/components/ReComponents.kt`(组件库,需要时扩展)、`ui/theme/Theme.kt`(配色,通过改 `MorandiColors` 实现)
2. **绝对禁止改动**:C++ 内核(`app/src/main/cpp/`)、`core/ReverieCoreBridge.kt`、`core/PaintViewModel.kt`(只能调用其现有方法,如 `vm.layerCount`/`vm.layerName(i)`/`vm.layerThumbs[i]`/`vm.setCurrentLayer(i)`/`vm.toggleLayerVisible(i)`/`vm.copyLayer(i)`/`vm.soloLayer(i)`/`vm.removeLayer(i)`/`vm.setLayerBlendMode(i,opId)`/`vm.setLayerOpacity(i,v)`/`vm.setLayerAlphaLocked(i,b)`/`vm.setLayerClipped(i,b)`/`vm.setLayerColorLabel(i,n)`/`vm.flipLayerHorizontal(i)`/`vm.flipLayerVertical(i)`/`vm.mergeDown(i)`/`vm.clearLayer(i)`/`vm.renameLayer(i,name)`/`vm.selectionFromLayer(i)`/`vm.applyFilter(i,id)`/`vm.addLayer()`/`vm.addGroupLayer()`/`vm.setLayerName`)
3. **禁止硬编码颜色** 所有颜色从 `Theme.current` 读取,组件内禁止写死 `Color(...)`
4. **组件化** 可复用 UI 元素(下拉/开关/胶囊/图标按钮)放入 `ReComponents.kt` 以 `Re` 前缀命名
5. 触控目标 ≥44dp,动画轻(150-300ms),界面文字用中文

## 当前图层面板结构(先读完 LayerPanel.kt 再动手)

### 主面板(屏幕右侧 300dp 悬浮,圆角 14dp,半透明毛玻璃感)

- 顶部工具行:添加图层 / 添加图层组 / 关闭
- 图层列表(顶图层在上,可滚动):
  - 每行高 56dp,行内布局:颜色标记条(3dp 竖条)→ 40dp 图层缩略图(白色浅灰网格底,显示透明区域)→ 可见性眼睛按钮(紧挨缩略图)→ 组缩进 → 图层组文件夹图标 → 图层名 → 锁定/透明度锁小标记 → 更多按钮(⋯)
  - 当前图层行高亮(强调色浅底)
  - 左滑交互:整行平滑滑开(220ms 动画),露出右侧三等分彩色抽屉——复制(蓝)/独显(青)/删除(红),图标+小字,与行融为一体,不点按不显示,点行收回
  - 左滑后抽屉常显,点按钮或点行才收回

### 二级面板(点击图层行从右侧滑入,带返回按钮)

- 头部:返回箭头 + 图层名 + 关闭
- 不透明度:水平滑块 + 百分比
- 混合模式:下拉列表(25 种,中文名,当前项高亮)
- 滤镜:下拉列表(灰度/反色/模糊/锐化)
- 颜色标记:9 个色块
- 开关:锁定透明度 / 剪切蒙版(背景层不显示)
- 操作胶囊:清除/重命名/复制/删除/水平翻转/垂直翻转/向下合并/创建选区
- 二级面板整体可滚动

## 用户痛点(优化重点)

- 用户反馈图层面板**视觉粗糙、AI 味重、不够高级**,需要对照参考截图全面精修
- 具体可打磨方向:行间距与内边距、选中态表达、缩略图边框与圆角、抽屉按钮配色与图标布局、二级面板信息层级、下拉菜单样式、分隔线、面板层级阴影(或扁平层次)、面板与屏幕边缘的距离
- 参考画世界 Pro 图层面板的常见细节:缩略图稍大、行高舒适、眼睛图标清晰、当前层高亮醒目、二级面板信息分组清晰

## 未实现功能的 UI 占位(布局先做好,事件可留空)

- 图层批量选择、拖拽排序(先做视觉占位)
- 图层搜索/筛选
- 二级面板更多入口(如"更多滤镜…"列表占位)

## 交付要求

1. 列出改动的文件与改动要点
2. 对照参考截图自查,列出仍不一致之处
3. 不改内核/ViewModel 业务方法,只做图层面板 UI
4. 改动后项目必须能编译通过
