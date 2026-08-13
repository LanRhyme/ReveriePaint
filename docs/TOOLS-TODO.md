# 工具系统开发记录

## 完成状态 (2026-08-14)

### Phase 1: 错误行为修复 ✅
- [x] TRANSFORM 变换工具: KisTransformWorker 核心 + 变换框 UI + 属性面板
- [x] MEASURE 测量工具: 距离/角度显示
- [x] DYNA 移除 (Krita 6.1.0-prealpha 无 dyna 引擎)

### Phase 2: 交互对齐 Krita ✅
- [x] POLYGON/POLYLINE: 逐点点击, 面板完成/取消
- [x] SELECT_POLYGON: 逐点点击选区
- [x] PATH: 贝塞尔(Catmull-Rom)转选区
- [x] CROP: 裁剪框预览 + 确认/取消
- [x] MOVE: 选区内容移动 (委托 applyTransform)

### Phase 3: 属性面板 ✅
- [x] TransformPanel: 旋转/缩放 + 应用/取消
- [x] ShapeToolPanel: 点选完成/取消 + 描边宽度 + 填充开关
- [x] GradientPanel: 线性/径向/角度
- [x] FillPanel: 容差
- [x] LiquifyPanel: 强度
- [x] TextInputDialog: 字号滑块
- [x] CropPanel: 尺寸 + 确认/取消

### Phase 4: 测试 ✅
- [x] 桌面工具综合测试 11 项全过 (直线/矩形填充/椭圆/多边形/渐变3种/填充/液化/选区移动)
- [x] transform_test 4 项全过 (平移/缩放/选区约束/旋转)
- [x] undo_test U0-U9 回归全过
- [x] sel_full_test 回归全过
- [x] APK 构建成功

### Phase 5: 审查 ✅
- [x] 25 个工具全部有分发 + 属性面板
- [x] 切换工具状态清理
- [x] 撤销/选区约束验证

## 修复的关键 bug
- selectionMask() 无选区返回全零数组导致 floodFill/变换误判
- 选区变换清空误用不透明黑清掉全图
- pickColorAt dev->pixel 陈旧 tile 数据 (1x1 readBytes 不可靠)
- KisTransformWorker 需有效 filter strategy
