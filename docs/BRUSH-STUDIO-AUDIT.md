# 笔刷工坊参数审计 (2026-11-16)

> 对 `ui/painting/brush/` 的控件 ↔ `PaintViewModel` ↔ `brushstore` 的 ControlId/规格表 ↔ Krita 引擎读取点，做了一遍端到端核对。
> 两轮修完之后，本文件记录：① 已修的缺陷与修法；② 仍**故意不修**的少数项及原因。
> 相关提交：`fix(brush): 修正工坊引擎切换写坏预设、蒙版混合模式伪选项与 .gih 笔尖类型`、
> `fix(brush): 修正附加旋转偏角下发错用 setBrushAngle 与 LIVE 覆盖不回写`、
> `feat(stylus,brush): 支持手写笔笔身旋转`、`fix(brush,jni): 回放通道补 Pressure<Id> 并清理串写与死键`、
> `fix(brush,ui): 工坊参数语义与量程修正`。

## 已修（本次）

| 问题 | 根因 | 修法 |
|---|---|---|
| 引擎单选「喷雾/速写/毛刷」选中即写坏预设 | 写的是短名 `spray/sketch/hairy`，Krita 注册的是 `spraybrush/sketchbrush/hairybrush`；`KisPaintOpPreset::load` 查不到工厂直接 `setValid(false)` | 新增 `brushstore/PaintOpIds` 归一（读侧也归一，旧预设自愈） |
| 蒙版混合模式里滤色/变亮/强光选了没反应 | 蒙版引擎只支持 `KisMaskingBrushCompositeOpFactory` 的 10 个 id，其余静默回落到 multiply；`addition` 连 Krita 的 composite id 都不是（真 id `add`） | 改成 multiply/darken/overlay/dodge/burn/linear_dodge/linear_burn/subtract |
| `.gih` 笔尖的 `brush_definition` 类型非法 | 写的是 `type="image_pipe_brush"`，Krita 只注册 auto/gbr/abr/png/svg/text；`KisBrush::fromXML` 对未注册 type 会回退 auto_brush。引擎侧自带按文件头嗅探注入（`ReverieCoreBrush.cpp` 的 `KisImagePipeBrush`），所以当前多数路径还能画对，但任何在注入前重新解析 `brush_definition` 的路径（如 `setPaintOpSize` 走 BrushWriter 的读-改-写）都会拿到回退的圆笔尖 | 写 `gbr_brush`（Krita 官方序列化同款） |
| 原生矩形笔尖回读成圆形 | 读侧只认 `"rect"`，`KisAutoBrushFactory::toXML` 写的是 `"rct"` | 读侧两种都认 |
| 拖「附加旋转偏角」把基础角度覆盖掉 | `applyLive(ROTATION)` 调的是 `setBrushAngle` | 改调 `setBrushRotation`（引擎内部合成），换笔时基础角度与偏角分别下发 |
| 调完角度换笔再换回，值回退 | LIVE 控件只 merge 覆盖、不排 flush，有效 kpp 不重写，readback 只叠 APP_ONLY 覆盖 | LIVE 也走防抖落盘闸 |
| 「笔身旋转」传感器恒 0 | 从未读 `AXIS_RZ`，且 ViewModel 层三处 `safeRotation = 0` | 见 `docs/STYLUS-BARREL-ROTATION.md` |
| 涂抹长度滑到 0 反而满拖动 | `PressureSmudgeRate=false` 时引擎取 `smudgeRate = 1.0`（`kis_colorsmudgeop.cpp`）；预置里恒保持勾选 | 改为恒勾选写入，0 才等于"不涂抹" |
| 勾气笔顺手改掉笔内叠加模式 | `PaintOpAction` 是 `KisPaintingModeOptionData` 的 BUILDUP/WASH 键，与气笔无关 | 气笔只写 `PaintOpSettings/isAirbrushing|rate` |
| 回放通道参数对未勾选预设无效 | `setBrushSoftness/Ratio/Sharpness/SmudgeLength/SmudgeRate` 只写 `<Id>Value`，而 `KisStandardOption` 未勾选一律 `return 1.0` | JNI 补 `Pressure<Id>=true`（Sharpness 另补 `Sharpness/alignoutline`） |
| 颜色比写进副色混合通道 | `setBrushSmudgeRate` 同时写 `MixValue`（前景/背景权重）与零读者的 `ColorRate/strengthValue|isChecked` | 改为 `ColorRateValue` + `PressureColorRate` |
| 柔和度/间距/圆度量程与规格不一致 | 工坊柔和度 0 起而规格钳 0.1；属性页间距 0 起（Krita 有 2% 下限）、圆度 0 会让 `effectiveSrcHeight` 归零 | UI 量程统一 0.1..1 / 0.02..2.5 / 0.05..1.0，并在 `updateBrushRatio` 再兜一次下限 |
| 引擎最大尺寸滑条无效 | 默认「尺寸随画布缩放」时上限由画布决定，滑块不参与 clamp | 该开关开启时滑条置灰并给出说明 |
| 速度感应放在压感组且语义误导 | 它是"速度压低尺寸"，与压力无关 | 挪到「间距与散布」组 |
| 跟随方向控件是死写 | 界面的"自动旋转"走 `CURVE_ROTATION`，该控件无 stage 入口 | 控件改为只读（保留 read 供旧预设回读），合并语义由 `writeFollowDirection` helper 单测保住 |
| 死键/死 JNI | `MaskingBrush/Preset/Spacing`、`MaskingBrush/Preset/paintopSize`、`paintopSize` 读写优先级、`AirbrushOption/*`、`Scatter/isChecked`、`Scatter/strengthValue`、`Scattering/Amount`、顶层 `Antialiasing/antialiasEdges` + `setBrushAntiAliasing` 整条 JNI | 删除写入/删 JNI；`paintopSize` 降级为历史兼容兜底 |

## 仍未修（有明确原因，不是遗漏）

| 项 | 现状 | 为什么不动 |
|---|---|---|
| 速度感应无法关掉「原生 speed 动力学」 | 读侧只认本 App 写出的编码（`(0,1)` 起点、`y(1)=1-v*0.7`），原生 speed 曲线一律读 0；滑块拉到 0 也不会删掉原生曲线 | 若"回读 0 再写 0"就拆曲线，会违反「恒等写无副作用」这条硬约束（`PresetPropertyMapRoundTripTest` 会挂，且用户换个控件也会顺手毁掉别人的曲线）。要支持只能先给"原生 speed 曲线不可调"一个只读态 UI，涉及 readback 传新状态，暂缓 |
| 录制/回放不带笔身旋转 | 录制事件没有 rotation 字段 | 要改录制格式并升版本号，属于格式演进，单独排期 |
| `requiredBrushFile` / `requiredBrushFilesList` | 仍由 `writeTipAsset` 写入 | Krita 侧零读者，但体积可忽略且被 App 侧当作笔尖索引线索，保留无害 |
| `brush_definition` 的 `width`/`height` | 仍写入 | 引擎忽略、Krita 不会回写，但 App 侧 `readSize` 用它做自包含尺寸，保留 |

## 复核时用到的判据

- 引擎 id：`KisPaintOpRegistry` 里注册的名字（`plugins/paintops/*/*_plugin.cpp` 的字符串）
- 键位：`plugins/paintops/libpaintop/KisStandardOptionData.h` / `KisKritaSensorPack.cpp`（`<Id>Value|Sensor|UseCurve` + `Pressure<Id>`）
- 勾选语义：`KisStandardOptions.h`（未勾选 `return 1.0`）、`KisSharpnessOption.cpp`（`isChecked() && alignOutlinePixels && strengthValue>0`）
- 蒙版：`libs/ui/tool/strokes/KisMaskingBrushCompositeOpFactory.cpp`、`KisPaintopSettingsIds.cpp`
- 笔尖：`libs/brush/kis_brush_registry.cpp`、`kis_predefined_brush_factory.cpp`、`kis_auto_brush_factory.cpp`
