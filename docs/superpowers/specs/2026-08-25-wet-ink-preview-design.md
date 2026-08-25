# 湿墨预览层 (Wet-Ink Preview) 设计

日期: 2026-08-25
状态: 已批准 (用户确认方向与设计)
关联: 笔迹延迟优化 commit 4e7c04d 的后续, 对齐画世界 Pro / Procreate 跟手感

## 1. 目标

落笔当帧即见墨。引擎管线 (采样批处理 → Krita 投影重组合 → 位图翻转 → Compose 上屏)
端到端仍有 1~3 帧延迟; 湿墨层让未走完管线的新采样以简化笔触直接叠画在画布上,
真墨到达后按时间戳自动替换。叠加 Android 12+ PointerPredictor 笔迹预测,
预测点仅用于显示, 视觉延迟趋近于零。

## 2. 架构与数据流

```
触摸采样 ─┬→ WetInkPreview.onSample (UI线程, 预分配环形队列, 零分配)
          └→ 现有管线不变 (touchMove 平滑 → queueStrokeMove → 引擎flush → doRender)
                                            ↓ 翻转时记录 lastRenderCommitElapsedMs
CanvasTouchView.onDraw 绘制顺序: 文档位图 → 湿墨尾巴(折线圆头) → 光标环
                                            ↑
          检测 displayRevision 变化 → 丢弃 tMs <= lastRenderCommitElapsedMs 的样本
PointerPredictor(API≥31): predict(event) 的预测点只进湿墨层显示,
                          不进引擎、不进录制、抬笔即清除
```

### 关键决策

- **依赖方向**: WetInkPreview 归 `ui/painting/canvas/`, 由 CanvasTouchView 持有;
  `PaintViewModel.touchMove` 通过注入回调 `strokeSampleListener: ((x,y,p)->Unit)?`
  把平滑后的采样喂给预览层 —— core 层不反向依赖 ui 层。
- **消费判定用时间戳而非脏区匹配**: 引擎按序 flush, 任何一次成功渲染都包含
  截至最后一次 flush 的全部墨迹; `elapsedRealtime` 全线程同源, 无需 rect 换算。
- **绘制参数与光标环同源**: 宽度 = brushSize × scale ×
  `ReverieCoreBridge.brushPressureFraction(p)` (预设 SizeSensor 曲线一致);
  alpha = brushOpacity × 图层opacity × 0.9。

## 3. 自动门控

以下任一条件成立时不绘制预览 (真墨路径完全不受影响):
非 BRUSH 工具 | 橡皮擦 | 路径引擎预设 (experimentbrush/curvebrush/sketchbrush/
gridbrush/particlebrush) | 纹理启用 | 气笔刷 | 选区激活 | 开关关闭。

设置面板新增开关 (持久化到 prefs, 与既有 gesture 开关同模式):
- **落笔即时预览** 默认开
- **笔迹预测** 默认开, 仅 API≥31 生效

## 4. 健壮性

- 所有缓冲预分配, 热路径零分配 (架构铁律 §4); 单线程 (UI) 访问无锁。
- PointerPredictor 创建/调用全程 try/catch, 杂牌 ROM 异常静默降级为无预测。
- 预测点标记来源, 仅入显示队列; 抬笔/取消/悬停退出立即清空。
- 预览 alpha ≤0.9 且真墨即达, 替换瞬间观感差异最小化。

## 5. 文件清单

| 文件 | 改动 |
|---|---|
| `ui/painting/canvas/WetInkPreview.kt` | 新增: 预览层 + 纯逻辑核心 WetInkQueue (顶层类, 无 Android 导入) |
| `ui/painting/canvas/CanvasTouchView.kt` | 接线: 持有实例 / onDraw 绘制 / Predictor / 门控读取 |
| `core/PaintViewModel.kt` | `strokeSampleListener` 注入点 + `@Volatile lastRenderCommitElapsedMs` + 开关字段与 prefs |
| 设置面板 (panels/) | 两个开关行 |
| `app/src/test/.../WetInkQueueTest.kt` | 追加/按提交时间丢弃/容量回绕 |

不改 C++、不动 JNI 边界、不动双缓冲结构。

## 6. 测试策略

- JUnit (纯逻辑): WetInkQueue 追加回绕、dropBeforeCommitted、快照输出顺序。
- 真机回归: 快速运笔跟随感提升、纹理笔刷自动降级无跳变、两开关生效、
  抬笔预测尾巴消失、录制回放内容不受污染、橡皮/选区路径行为不变。
