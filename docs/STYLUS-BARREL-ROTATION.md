# 手写笔笔身旋转 (Barrel Rotation)

> 记录 ReveriePaint 对「笔身旋转」这一路输入的支持方式、口径来源，以及用户报「倾斜/自转用不了」时的排查方法。

## 1. 结论先行

- **Android 没有专用的「笔轴自转」接口。** `MotionEvent.AXIS_TILT` 是自垂直倾角，`AXIS_ORIENTATION` 是笔尖指向的方位角（倾角方位），两者都不是自转；把 `AXIS_ORIENTATION` 当自转用会让带朝向动态的笔刷在画弧线/折线时 dab 剧烈扭曲撕裂（历史结论，见 `CanvasTouchView.updateStylusSensors` 注释）。
- **Wacom 系笔尖（Art Pen 2 等）把自转放在通用旋转轴 `AXIS_RZ`（弧度）。** 这一点由 Drawpile 开发者在 Krita 论坛定位（[主题帖](https://krita-artists.org/t/barrel-rotations-seems-doesnt-work-in-android-system/184547)），并已合入 Krita 的 Qt 分支：qtbase 提交 `11238262` `[android] Support stylus barrel rotation`。该提交做的正是把 `QTabletEvent::rotation()` 从 `AXIS_ORIENTATION` 改读 `AXIS_RZ`。
- 所以「有些用户的倾斜能用、自转不能用」的真实原因不是笔坏了，而是：**自转数据一直在 `AXIS_RZ` 里，App 之前从来没有读它**（`touchRotation` 写死 0，ViewModel 层还有三处 `safeRotation = 0` 把上层解析结果吞掉）。至于倾斜，取决于笔/驱动有没有上报 `AXIS_TILT` 的量程 —— 见 §3 的排查方法。

## 2. 实现与口径

| 环节 | 位置 | 说明 |
|---|---|---|
| 口径换算 | `model/BarrelRotationPolicy` | `rotationDeg = remainder(toDegrees(rz) + 180, 360)`，输出 `(-180, 180]`；Android 的 0° 指向屏幕下方，Krita/Qt 的 0° 指向上方，故 +180 翻转后再归一 |
| 能力判定 | `CanvasTouchView.deviceReportsBarrelRotation` | `InputDevice.getMotionRange(AXIS_RZ) != null`；按设备 id 缓存一次。**未上报时必须保持 0** —— 该轴缺省读数是 0 弧度，直接换算会得到 180°，反而把 Rotation 动态顶到曲线末端 |
| 主画布 | `CanvasTouchView.updateStylusSensors` | 旋转先于倾角解析（倾角缺失不再连带清零旋转）；主笔批次、镜像、点按、抬笔尾锋共用同一份值 |
| 下发 | `PaintViewModelTools.touchStart/touchMove` → `PaintViewModel.queueStrokeMove` | 6 元采样批 `[x, y, p, tiltX, tiltY, rotation]` → JNI `touchStrokeMoveBatch` → `KisPaintInformation::rotation` |
| 试画板 | `BrushStudioScratchpad.resolveScratchpadRotation` | 同一套换算，带 Rotation 曲线的笔刷可以在试画板上直接验证 |
| 引擎消费 | `plugins/paintops/libpaintop/sensors/KisDynamicSensors.h` | `KisDynamicSensorRotation::value() = rotation()/180`，配合曲线域 `(-180→0, 0→0.5, +180→1)`，正好用满 `[0,1]` |

自转对不同动态的效果由用户自己在笔刷工坊里配：几何页的「旋转驱动传感器」把 Rotation 动态的驱动源选成「笔身旋转」，或在压感/不透明度/流量组的传感器 chip 里选它。

## 3. 用户报「用不了」时怎么查

App 会在每台设备**首次落笔**时打一条 logcat：

```
I/ReverieStylus: stylus-axes device=<name> id=<id> tool=stylus tilt=<min..max> orientation=<min..max> rz=<min..max> pressure=<min..max>
```

这条日志会进「设置 → 隐私与诊断 → 全景系统运行诊断」导出的日志包，直接看三个量程即可定性：

| 现象 | 判据 | 结论 |
|---|---|---|
| `tilt=none` | 设备完全没有上报 `AXIS_TILT` | 笔/驱动不支持倾斜，App 侧无法补偿 |
| `tilt=0.000..0.000` | 轴存在但量程恒 0 | 驱动没接倾斜数据（常见于把笔当鼠标/触控笔上报的设备） |
| `rz=none` | 没有 `AXIS_RZ` | 该笔没有自转硬件（绝大多数 Android 笔），「笔身旋转」恒 0 属预期 |
| `rz=...` 但画笔没变化 | 有量程 | 先确认笔刷的 Rotation 动态确实选了「笔身旋转」传感器，再看曲线是不是平的 |

## 4. 已知边界

- 非 Wacom 协议的笔（如国内 OEM 的星闪/私有通道）如果自转不走 `AXIS_RZ`，App 读不到；需要厂商 SDK 或系统级映射才可能支持。
- 录制/回放目前不带 rotation：回放里的自转取不到录制时的值（`BrushApply.applyReplayExt` 只回放静态「附加旋转偏角」）。
