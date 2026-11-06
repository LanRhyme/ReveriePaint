# 液化画质：让"越涂越糊"停下来

## 为什么会糊

液化每次手势结束都会把形变结果**落回图层**，下一次手势从"已经重采样过的像素"重新采样。
引擎的取样核原本是**双线性**：每过一遍就把边缘抹平 0.5~1px，涂几次线条就明显发虚。
（`ReverieCoreMiscTools.cpp` 的 `warpInverseField`：`image(p) = original(p - field(p))`，
一次手势内始终取同一份 pristine src，所以糊不是"同一手势内累积"，而是**跨手势累积**。）

## 改了什么

1. **重采样核换成 Catmull-Rom（三次卷积）**（`LiquifyPixelSampling.h` 的 `bicubic16`）
   - 16 抽头，与双线性共用同一套 alpha 口径（预乘累加再除回直通色），全透明邻域输出全透明；
   - 边界取样用**边缘复制**而不是透明，避免负权重把"透明"放大成暗角；
   - 取样矩形外扩 2px（原 1px）以容纳 4×4 邻域；
   - 回退开关：`setprop debug.reverie.lqbilinear 1` 退回双线性。
2. **液化大小滑杆改成对数刻度**（与画笔大小滑杆同一套算法：
   `value = exp(ln(min) + f·(ln(max)-ln(min)))`），8~40px 这段不再挤在滑杆最左边。

## 量化（C++ host 测试，`app/src/test/cpp/liquify_sampling_test.cpp`）

反复 0.4px 位移 4 次后的边缘对比度（越大越清晰）：

| 取样核 | 边缘对比度 |
|---|---|
| 双线性 | 41 |
| Catmull-Rom | **56**（+37%） |

同时该测试还断言：
- 线性剖面被精确还原（Catmull-Rom 对一次函数是恒等 ⇒ 形变不引入明暗带）；
- 全透明邻域 → 全透明（不漏黑边）。

跑法：`bash app/src/test/cpp/run_cpp_tests.sh liquify_sampling_test`

## 还没做的（下一步）

- **会话级原始像素**：目前"每笔一次重采样"仍在累积。要做到接近无损，需要在进入液化工具时
  把受影响区域固定成一份原始源，整个会话都从它重采样，抬笔/退出时一次性物化
  （Photoshop 的液化面板就是这么做的）。
- **GPU 预览与结果同核**：拖动中的 AGSL 预览仍是单次 `eval`（线性取样），会比最终结果更软；
  要让"所见即所得"，预览着色器也换成同一套 Catmull-Rom 权重。
