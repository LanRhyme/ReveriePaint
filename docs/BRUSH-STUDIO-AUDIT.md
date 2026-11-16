# 笔刷工坊参数审计 (2026-11-16)

> 对 `ui/painting/brush/` 的控件 ↔ `PaintViewModel` ↔ `brushstore` 的 ControlId/规格表 ↔ Krita 引擎读取点，做了一遍端到端核对。
> 本文件只记录**本次未修**的疑点，已修的部分见提交 `fix(brush): 修正工坊引擎切换写坏预设、蒙版混合模式伪选项与 .gih 笔尖类型`、
> `fix(brush): 修正附加旋转偏角下发错用 setBrushAngle 与 LIVE 覆盖不回写`、`feat(stylus,brush): 支持手写笔笔身旋转`。

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

## 未修（待确认后再动）

| 级别 | 控件/参数 | 现象与证据 | 建议 |
|---|---|---|---|
| P1 | 涂抹长度 `brush_studio_color_smudge_length`（`ControlId.SMUDGE_LENGTH`） | 滑条拉到 0 时 `KppSensors.writeStrength` 会写 `PressureSmudgeRate=false`，而引擎 `kis_colorsmudgeop.cpp` 是 `isChecked() ? computeSizeLikeValue : 1.0` —— 未勾选等于**满拖动**，与"0 = 不涂抹"完全相反 | 值 0 也保持勾选（`SmudgeRateValue=0`），或写一条平的 `SmudgeRateUseCurve` 曲线让强度可由滑块控制 |
| P1 | 气笔开关 `ControlId.AIRBRUSH` | 顺手写 `PaintOpAction=1/2`；该键的读者是 `KisPaintingModeOptionData`（BUILDUP/WASH 笔内叠加语义），与气笔无关，勾气笔会顺手改掉预设的叠加模式 | 去掉这一行，只在用户显式改"绘制模式"时写 |
| P1 | 回放通道 `ReverieCore::setBrushSoftness/Ratio/Sharpness/SmudgeLength` | 只写 `<Id>Value` 不写 `Pressure<Id>`；Krita `KisStandardOptions.h` 里未勾选一律 `return 1.0`，Sharpness 还要求 `Sharpness/alignoutline` | JNI setter 补齐 `Pressure<Id>=true`（Sharpness 另加 alignoutline） |
| P1 | 回放通道 `setBrushSmudgeRate` | 把"颜色比"同时写进 `MixValue`（前景/背景混合比通道）与死键 `ColorRate/strengthValue`、`ColorRate/isChecked`（真开关是 `PressureColorRate`） | 删 `MixValue` 与两个死键，改 `PressureColorRate=true` + `ColorRateValue` |
| P2 | 柔和度滑条（工坊 `brush_studio_masking_softness` / 属性页） | UI 从 0 起，规格读写都 `coerceIn(0.1, 1.0)`：拖到 0% 实际写 10%，换笔回读跳到 10% | UI 下限改 0.1，或证明 0 对引擎有意义后去掉夹取 |
| P2 | 间距 / 圆度（属性页 `BrushPropertyPage`） | 属性页 spacing 从 0 起（Krita `KisBrush::setSpacing` 有 2% 下限，工坊是 1%…250%）；ratio 从 0 起，写进 `MaskGenerator ratio="0.0"` 后 `effectiveSrcHeight=0`，而位图笔尖的 `normalize` 又把 ≤0.005 当 1.0 → 0% 跳 100% | 属性页量程统一到 0.02…2.5 / 0.05…1.0，ratio 写入 `coerceAtLeast(0.01)` |
| P2 | 引擎最大尺寸 `brush_studio_engine_max_size` | `brushSizeScalesWithCanvas=true`（默认）时上限直接取 `max(docW,docH)/2`，`maxSizeLimit` 不参与 clamp | 该开关开启时置灰滑条并提示 |
| P2 | 速度感应响应 `brush_studio_press_speed` | 读侧只认本 App 写出的两段曲线签名，原生 speed 动力学读 0；值为 0 且不拥有曲线时 write 恒等返回，关不掉原生 speed 调制；它还与压力无关却放在"压感"组 | 读侧放宽到"Size 传感器含 speed 子传感器"，UI 挪组 |
| P2 | 几何罗盘 `StudioAngleDial` | 指针只画 `brushAngle`，引擎实际笔尖角度是 `angle + rotation` | 指针画合成角，或加一条偏角辅助线 |
| P3 | `ControlId.FOLLOW_DIRECTION` | 没有任何 stage 入口（UI 的"自动旋转"走 `CURVE_ROTATION`），read/write 永不执行 | 确认历史 kpp 是否还需要读它，再决定删或接回 |
| P3 | 多处死键/死规格 | `MaskingBrush/Preset/Spacing`、`MaskingBrush/Preset/paintopSize`、`paintopSize`、`requiredBrushFile(sList)`、`AirbrushOption/*`、`Scatter/isChecked`、`Scatter/strengthValue` 在 Krita 侧零读者 | 逐条删或标注为 App 私有键 |
| P3 | 录制/回放不带笔身旋转 | 录制事件里没有 rotation 字段，回放只回放静态偏角 | 若需要回放自转，录制格式要加字段（涉及版本号） |

## 复核时用到的判据

- 引擎 id：`KisPaintOpRegistry` 里注册的名字（`plugins/paintops/*/*_plugin.cpp` 的字符串）
- 键位：`plugins/paintops/libpaintop/KisStandardOptionData.h` / `KisKritaSensorPack.cpp`（`<Id>Value|Sensor|UseCurve` + `Pressure<Id>`）
- 勾选语义：`KisStandardOptions.h`（未勾选 `return 1.0`）、`KisSharpnessOption.cpp`（`isChecked() && alignOutlinePixels && strengthValue>0`）
- 蒙版：`libs/ui/tool/strokes/KisMaskingBrushCompositeOpFactory.cpp`、`KisPaintopSettingsIds.cpp`
- 笔尖：`libs/brush/kis_brush_registry.cpp`、`kis_predefined_brush_factory.cpp`、`kis_auto_brush_factory.cpp`
