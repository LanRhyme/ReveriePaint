# ReveriePaint 推进文档

## 当前状态

原生安卓版 (Kotlin/Compose + JNI + Krita C++ 核心) MVP 功能完整, 等待真机测试

## 已完成

- [x] 项目迁移: QML/QtWidgets UI 替换为 Jetpack Compose
- [x] 三页导航: 主页 / 创建画布 / 绘画
- [x] 主页: 最近项目列表 + 缩略图 + 新建/打开入口
- [x] 创建页: 6 尺寸预设 + 自定义宽高 + 背景色
- [x] 绘画页: 画世界 Pro 风格布局
  - 左侧工具栏 (13 工具 + 竖向大小/不透明度滑杆 + 取色)
  - 顶部操作栏 (返回/撤销/重做/旋转/缩放/图层/设置)
  - 网格工作区 + 居中画布 + 阴影
  - 变换指示器 (缩放%/旋转°, 1.2s 淡出)
- [x] 触摸手势: 单指绘画 (压感) / 双指缩放旋转平移
- [x] 全部 13 工具: 画笔 / 手 / 橡皮 / 取色 / 填充 / 套索 / 魔棒 /
      直线 / 矩形 / 椭圆 / 文字 / 涂抹 / 液化
- [x] 图层: 新建 / 删除 / 切换 / 显隐 (Krita KisPaintLayer)
- [x] 图层混合模式: 9 种 (Krita composite ops)
- [x] 笔刷面板: 预设 + 大小 + 不透明度
- [x] 取色面板: HSV 色轮 + 亮度滑条 + 预设色板
- [x] 设置面板: 画布信息 + 保存项目 + 重置视图
- [x] 撤销/重做 (多图层快照栈)
- [x] 项目保存/加载 (PNG 导出/导入)
- [x] 连通区域种子填充 (BFS + 颜色容差)
- [x] C++ 引擎: KisImage 直接构造 (绕过 KisDocument/KisPart)
- [x] JNI 桥: 完整 API 覆盖
- [x] APK 体积优化 (140MB -> 41MB)

## 待办 (MVP 之后)

- [x] 真机验证与问题修复 (用户验收)
- [x] 图层面板基础功能 (单行左滑/拖拽重排/组/树形/自适应高度)
- [ ] 笔刷面板: 完全使用 Krita 库 (KisPaintOpPreset + 真实笔刷引擎)
- [ ] 工具面板: 所有 Krita 自带工具
- [ ] 形状工具实时预览 (拖动时显示形状)
- [ ] 画布网格开关
- [ ] 多文档支持
- [ ] 导出到相册/分享
- [ ] 全屏沉浸模式
- [ ] 版本控制增强 (缩略图历史)

## 工具与笔刷计划 (2026-08-13, 完全使用 Krita 库)

用户指令: 工具面板 + 笔刷面板完全使用 Krita 的库, 添加所有 Krita 自带工具和内置笔刷

### 技术侦察结论

- KisPaintOpPreset 在 kritaimage (libs/image/brushengine), 已构建
- 预设文件自包含 PNG (内嵌 XML preset + 笔刷资源), 共 16 个在 krita/data/paintoppresets
- 预设加载需要 KisResourcesInterface (KisLocalStrokeResources, kritaresources 已构建)
- 渲染管线: KisPainter::setPaintOpPreset -> paintLine/paintBezierCurve + KisPaintInformation (压感/倾角)
- paintop 实现 (KisBrushOp 等) 在 plugins/paintops, 需交叉编译:
  kritalibpaintop (依赖 kritaui, 已构建) + kritadefaultpaintops_static (核心笔刷)
- 插件注册: KoPluginLoader 动态加载在 Android 不可靠, 改为静态链接后手动
  KisPaintOpRegistry::instance()->add(factory) 注册

### 阶段 1: 笔刷引擎 (Krita 真实笔刷)

- [ ] 交叉编译 kritalibpaintop + kritadefaultpaintops_static
      (+ colorsmudge/spray/roundmarker/sketch/hairy/particle/deform/filterop/gridbrush/tangentnormal)
- [ ] 静态注册 paintop factories (绕过 Qt 插件机制)
- [ ] APK assets 打包 16 个自带预设
- [ ] 预设加载管线: KisPaintOpPreset::loadFromDevice + KisLocalStrokeResources
- [ ] 替换 dab 循环: setPaintOpPreset + KisPaintInformation + paintLine
- [ ] 桌面 harness 像素验证 (笔刷形状/软硬边/压感)
- [ ] 笔刷面板 UI: 预设列表 (缩略图) + 参数 (大小/不透明度/流量/硬度/间距/纹理强度)

### 阶段 2: 工具面板

- [ ] 评估 KisTool 完整接入 (需 kritaui + QWidget 事件, 风险高)
- [ ] 若不可行: 移植 Krita 工具逻辑到现有架构 (选区/形状/变换/文字等)
- [ ] 工具列表: Krita 自带全部工具
- [ ] 工具面板 UI

### 阶段 3: 整合打磨

- [ ] 工具-笔刷联动 (每工具默认笔刷)
- [ ] 面板样式融入 ReComponents
- [ ] 全量测试

## 架构决策

- UI 用 Compose: Qt QQuickWidget 在 Android 上透明 overlay 不可靠, 反复失败
- C++ 引擎绕过 KisDocument/KisPart: 它们是 kritaui 的一部分, 需要完整 QApplication;
  KisImage 公开构造即可满足单文档绘画引擎需求, 依赖面大幅缩小
- Qt 库仅作为引擎运行时: 原生 Activity 里创建 QCoreApplication (无界面)
- 画布显示: C++ 合成 → Android Bitmap 零拷贝 (lockPixels) → Compose Image
- Bitmap 原地修改: mutableStateOf 用 neverEqualPolicy 强制触发重组
- 渲染缓存: 内容变化才重合成, 触摸期间 30fps 节流
- 区域化合成: 按 Krita projection 机制只重算脏区 (convertToQImage 区域),
  笔画期间每帧只合成笔画包围盒而非全文档, 解决绘画卡顿
- 图层投影修复: 节点结构变化 (增删图层/可见性/混合模式) 后 root 投影
  设备被重建为空, 必须用 KisRefreshSubtreeWalker+KisAsyncMerger 强制
  全量重合成, 否则画布变黑
- 笔画脏区传播: KisPainter 把脏矩形累积在内部, 必须 takeDirtyRegion 后
  device->setDirty 应用, 否则多图层状态下投影永不更新 (笔迹乱码/消失)
- 双指变换: 以双指质心为锚点的增量式变换, 每帧缩放钳制 0.5-2x, 旋转 ±15°
- 撤销: 笔画级多图层快照 (32 层上限), Krita 命令栈后期接入
- 填充: 自研 BFS 种子填充 (KisFillTool 在 kritaui 中, 依赖太重)
- 液化: 局部像素位移 + 径向衰减

## 构建

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 环境

- AGP 9.3.1 + Gradle 9.5 (离线缓存)
- NDK 25.2.9519653, compileSdk 36, minSdk 23
- Qt for Android 6.6.3 (引擎运行时), Krita 6.1.0-prealpha arm64 交叉编译库

## 完整图层系统 (2026-08-12)

- 背景层: 新建画布 = 锁定背景 + 颜料图层1, 背景不可绘/删/改名
- 隐藏背景显示透明底 (网格)
- 图层组 (KisGroupLayer), 组内添加图层
- 混合模式全集 25 种 (Krita KoCompositeOpRegistry)
- 锁定图层 / 锁定透明度 (KisPainter channelFlags)
- 不透明度 / 颜色标记
- 复制 / 清除 / 重命名 / 翻转 / 向下合并 / 删除 (背景保护)
- 独显 (FolioLayers 逻辑, 再点恢复)
- 剪切蒙版 (自实现, alpha 遮罩)
- 滤镜: 灰度 / 反色 / 模糊 / 锐化
- 选区: 从图层 alpha 创建, 绘画受限 (KisSelection)
- 图层面板: 左滑 复制/独显/删除, 点击二级面板, 更多操作菜单
