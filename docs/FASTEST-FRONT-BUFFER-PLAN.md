# ReveriePaint 超低延迟前缓冲预览方案设计与实施规划 (Fastest Front-Buffer Plan)

> **目标分支**: `feat/latency-prediction` (工作树: `E:\gongju\ReveriePaint-lowlatency`)  
> **核心组件**: `androidx.graphics:graphics-core:1.0.4` (Apache-2.0, API 29+)  
> **设计定位**: 笔尖前沿孪生预览段 (Preview Segment) 单缓冲直出，将笔尖到墨迹感知延迟压至 4~8ms 硬件极限。

---

## 1. 核心物理事实与选型依据 (Android 低延迟图形管线架构)

基于 Android 低延迟图形架构规范 (`androidx.graphics:graphics-core`) 与操作系统窗口合成器机制：

### 1.1 Android 硬件前缓冲流水线机制
- **核心组件**: `androidx.graphics:graphics-core` 库中的 `CanvasFrontBufferedRenderer` (@RequiresApi(29))。
- **底层原理**:
  1. 通过 Android 10+ 的 `SurfaceControl` 创建层级控制树：宿主 SurfaceView -> `MultiBufferedSurfaceControl`（双/三缓冲）与 `FrontBufferedSurfaceControl`（单缓冲直出）；
  2. 单缓冲前缓冲层配置为 `setMaxBuffers(1)`，绕过 Choreographer 的垂直同步 (VSYNC) 周期排队与合成管线；
  3. 当手写笔通过 `requestUnbufferedDispatch(event)` 产生触摸位移时，UI 线程捕获最新几何段后，渲染器在独立的硬件渲染线程直接向单缓冲 Surface 绘制并呈现；
  4. 呈现延迟从传统的 `Input -> VSYNC -> RenderThread -> SurfaceFlinger -> Display`（共 3~4 帧，约 30~50ms）压缩至硬件直出的 4~8ms 极限。

### 1.2 核心物理边界厘清
- **真实像素合成 vs 笔尖前沿预览**:
  - 全屏真实图层合成：ReveriePaint 的核心是 Krita 图像引擎，笔画需要完整的图层树投影合并、笔刷纹理混合、选区裁切与 CPU/GPU 双缓冲同步，真墨物理管线耗时通常在 30~50ms。
  - **ReveriePaint 达到 4~8ms 硬件极限的途径是「笔尖前沿孪生预览段」**：当前笔尖与真实墨水前沿之间存在 1~2 帧的延迟空窗。通过利用前缓冲独立图层以单缓冲极速直出该微小前沿段，能在视觉和物理手感上彻底消灭笔尖与线条之间的断裂感，当 Krita 后台完成真墨渲染后无缝融合。

### 1.3 Canvas 版对口 API: `CanvasFrontBufferedRenderer`
对于轻量级 2D 笔尖矢量前瞻预览，`androidx.graphics:graphics-core` 提供了 `CanvasFrontBufferedRenderer`：
- 无需搭建庞大的 EGL/GLSL 渲染管道与上下文切换开销，直接通过硬件加速 `Canvas` 回调绘图；
- 缓冲格式配置为 `TRANSLUCENT`，背景完全透明透出底层真实画布；
- 生命周期与 SurfaceHolder 自动绑定，资源安全可控。

---

## 2. 架构路线权衡：为什么必须坚决裁撤 B2？

在方案推演过程中，曾考虑两种演进路径：

### 2.1 原方案 B2 路径（全画布 SurfaceView 化）—— **坚决放弃**
- **做法**: 将 `CanvasTouchView` 拆分，画布位图、棋盘格、像素网格移入 `SurfaceView`（`lockCanvas(dirtyRect)`），前缓冲层挂在画布之上。
- **为什么必须放弃？**
  1. **AGSL 致命冲突**: ReveriePaint 的实时液化（Liquify）依赖 Android 13+ 的 `RuntimeShader`（AGSL）。AGSL **强制要求硬件加速 Canvas**。而 `SurfaceHolder.lockCanvas()` 产出的是**软件 Canvas**，在软件 Canvas 上绘制 AGSL 会直接报非法异常崩溃或黑屏！
  2. **巨大重构成本与灾难性回归风险**: `CanvasTouchView` 拥有 5686 行复杂代码，承载缩放/平移/旋转手势矩阵、对称轴镜像、选区蚂蚁线、拾色放大镜、图钉等。整体迁移至少耗时数周，极易破坏现有稳定绘制状态。
  3. **遮挡误解澄清**: 原方案误以为"画布留在 Window 里会被前缓冲层遮挡"。实际上，透明 SurfaceView（`setZOrderOnTop(true)` 或 `setZOrderMediaOverlay(true)`）本身只有被绘制的笔尖线段有像素，其余全部是透明通道，底层 Window 画布天然完全透出！

### 2.2 推荐路径：轻量独立透明前缓冲预览层 (Overlay)
- **核心理念**: **零侵入！保持现有 5686 行 `CanvasTouchView`、Krita 引擎、AGSL 液化着色器完全不动**。
- 仅新增一个独立的透明 `FrontBufferPreviewOverlay`（封装 `SurfaceView` + `CanvasFrontBufferedRenderer`）。
- 仅在手写笔落笔期间（`DOWN`..`UP`）处于激活状态，单缓冲直出笔尖前伸几何段，提笔即提交并透明休眠。
- 遇异常或在不支持的系统上，全自动零成本回退到现有 in-window 预览。

---

## 3. 目标运行架构

```
┌────────────────────────────────────────────────────────┐
│ Window 顶层：Compose UI (工具栏 / 调色盘 / 对话框)        │
├────────────────────────────────────────────────────────┤
│ 前缓冲层：FrontBufferPreviewOverlay (SurfaceControl)     │  ← 4~8ms 单缓冲直出笔尖前沿段
├────────────────────────────────────────────────────────┤
│ Window 底层：CanvasTouchView (5686行，Krita位图+手势+AGSL)│  ← 正常硬件加速显示
└────────────────────────────────────────────────────────┘
```

### 运行时时序：
1. **落笔 (ACTION_DOWN)**:
   - 手写笔事件进入 `CanvasTouchView`；
   - 激活 `FrontBufferPreviewOverlay`，初始化 `CanvasFrontBufferedRenderer`。
2. **运笔 (ACTION_MOVE)**:
   - 触控采样照常送入后台 Krita CPU 攒批流水线；
   - UI 线程将"引擎已渲染前沿 $\to$ 当前触控预测点"打包为轻量几何对象；
   - 调用 `CanvasFrontBufferedRenderer.renderFrontBufferedLayer(param)`，直接在单缓冲 SurfaceControl 上清空上一小段并重画最新段（4~8ms 直出上屏）。
3. **抬笔 (ACTION_UP)**:
   - 调用 `commit()` 或 `cancel()` 清空前缓冲；
   - Krita 真实墨迹末帧上屏，前缓冲层静默透明休眠；
   - 无残影、无撕裂、无层级穿透。

---

## 4. 分期实施计划

### Phase B0 · 真机可行性探针 (Spike)
1. **引入依赖**: `app/build.gradle.kts` 添加 `androidx.graphics:graphics-core:1.0.4`；
2. **环境探活**: 新增 `FrontBufferProbe.kt`，检测 API 29+ 及系统 SurfaceControl 运行状况；
3. **沙盒覆盖层**: 新增 `FrontBufferPreviewOverlay.kt`，实现透明 SurfaceView 与 CanvasFrontBufferedRenderer 框架；
4. **验证门禁**:
   - 透明度测试（无黑底遮挡）；
   - 国产 ROM 兼容性测试（无黑屏/闪烁）；
   - 抬笔清空与无残影验证；
   - 240fps 慢动作比对延迟收益。

### Phase B1 · 真实前沿几何对接与主流程集成
- 将 `CanvasTouchView` 中的 `currentRenderedFrontier` 及触控点通过纯数据结构同步至 `FrontBufferPreviewOverlay`；
- 当前缓冲层激活时，短路现有 in-window 模拟线绘制（防双重重叠绘图）；
- 完善手势变换（缩放/平移/旋转）坐标映射。
