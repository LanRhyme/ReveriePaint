# 工具系统完整开发计划

## 现状

- 正常工具: 笔刷/橡皮擦/涂抹/套索/魔棒/矩形选择/椭圆选择/相似色选择
- 其余工具不可用或不完整

## 目标

每个工具完整可用 + 独立属性面板, 逐步开发逐个测试, 参考 Krita 工具逻辑

## 任务清单

### Phase 1: 错误行为修复

- [x] 审计全部工具分发 (CanvasView)
- [ ] TRANSFORM 变换工具 (重点): 变换框 + 控制点 + 应用/取消 + 属性面板
- [ ] MEASURE 测量工具: 两点距离/角度显示
- [ ] DYNA: 从工具列表移除 (Krita 6.1.0-prealpha 无 dyna 引擎)

### Phase 2: 交互对齐 Krita

- [ ] POLYGON/POLYLINE: 逐点点击, 完成按钮/双击完成
- [ ] SELECT_POLYGON: 逐点点击选区
- [ ] PATH: 贝塞尔路径工具 (点-拖-点), 完成后转形状/选区
- [ ] CROP: 裁剪框预览 + 确认/取消按钮
- [ ] MOVE: 有选区时移动选区内容, 无选区移动图层

### Phase 3: 属性面板 (每个工具独立)

- [ ] ToolOptionsBar 框架: 底部面板按工具切换
- [ ] ShapeToolPanel: 形状填充/描边宽度
- [ ] GradientPanel: 渐变类型(线性/径向)/重复
- [ ] TransformPanel: 模式/参考点/应用/取消
- [ ] FillPanel: 容差
- [ ] LiquifyPanel: 强度/笔刷大小
- [ ] TextPanel: 字体大小/对齐
- [ ] CropPanel: 尺寸显示
- [ ] MeasurePanel: 测量结果
- [ ] MovePanel: 移动对象选项

### Phase 4: 测试

- [ ] 桌面 harness 测试每个 C++ 工具 (像素验证)
- [ ] 构建安装
- [ ] 用户测试反馈

### Phase 5: 审查

- [ ] 全部工具走查
- [ ] 撤销/选区约束验证
- [ ] UI 一致性检查
