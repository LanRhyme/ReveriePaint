# UI 质感升级：玻璃拟态 + 液态动效 + 品牌蓝克制化 设计文档

日期: 2026-08-24
状态: 已批准（用户确认 §1-§4，全自动实施）

## 1. 目标与约束

向 Procreate 的克制高级感靠拢，四点需求：

1. 面板统一玻璃拟态（项目已有 haze 1.5.3），统一样式与边框质感
2. 动效参考液态玻璃 App 交互手感（参考 Kyant0/LiquidGlass），**只要动画交互不要折射纹理**：按压回弹、弹簧过渡、高光跟随；纯 Compose 实现，不引 backdrop 库
3. 减弱（非去除）品牌蓝：**色板饱和度不动**，靠用法分层 + 降低大面积着色的不透明度实现中性化；选中态保留克制品牌色
4. 主页画廊不大改

### 硬性约束（来自 AGENTS.md）

- 零分配铁律：动效 Modifier 全部 remember 预分配，热路径不建对象
- 不碰画布手势代码（`ui/painting/canvas/` 手势 key 与触摸分发路径）
- 依赖单向 `ui/ → core/ → model/`
- 主题色一律引用语义 token

## 2. 现状摘要

- **Haze**: 12+ 处内联 `HazeStyle(panel.copy(alpha), tint 同值, blurRadius=24.dp, noiseFactor=0.05f)`，分散在 ReComponents/TopBar/BrushPanel/HomeBottomBar(参数不一致 alpha 0.75)/ReferenceWindow/ToolRail(x2)/AllToolsPanel/LayerPanel/ToolFloatPanel/SettingsPanel/ColorPanel。全部受 `vm.blurBackground` 开关控制（PaintViewModel.kt L525 默认 **false**）。hazeChild 背景源: PaintingPage.kt L412、HomePage.kt L259。
- **动效**: 零散——按压缩放 spring(0.6/500) scale 0.86、spring(MediumBouncy) 等；面板出入场多为 tween(200~250) 硬切。MainActivity 页面转场已合理，不动。
- **颜色**: `Theme.kt` 单文件，AppColors 14 token。静态莫兰迪色板 + Monet 动态色 (`toMorandiAccent` S coerce 0.22-0.42)。约 250 处 accent 引用；ReComponents.kt (~998 行) 是共享组件库，22 处 accent 用法是模式样本。
- **API 兼容**: minSdk 23，现有 gating 只判 `vm.blurBackground && hazeState != null`，无 SDK 级检查（真模糊需 API 31+）。

## 3. 新增设计系统三件套

### 3.1 `ui/theme/Motion.kt` — 动效 token

```kotlin
object Motion {
    val snapBouncy = spring<Float>(dampingRatio = 0.55f, stiffness = 400f) // 按压回弹
    val springSoft = spring<Float>(dampingRatio = 0.85f, stiffness = 350f) // 面板出入场
    val springSnap = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)  // 选中切换
}
```

### 3.2 `ui/theme/Glass.kt` — 玻璃工厂

```kotlin
object Glass {
    val blurRadius = 24.dp
    val noiseFactor = 0.02f   // 0.05 → 0.02 更细腻

    /** 浮层: 工具栏/顶栏/滑轨 */
    @Composable fun barStyle(alpha: Float = 0.78f): HazeStyle
    /** 弹出: 对话框/弹窗，更透更虚 */
    @Composable fun popupStyle(alpha: Float = 0.66f): HazeStyle
}

/** 方向性渐变描边: 上缘亮 (白 α≈0.14) → 下缘暗 (黑 α≈0.06)，模拟玻璃受光 */
fun Modifier.glassBorder(shape: Shape): Modifier

/** 设备真实模糊能力 (API 31+)；<S 强制实色降级 */
val blurCapability: Boolean
```

有效模糊条件: `vm.blurBackground && blurCapability && hazeState != null`。设置面板在 `!blurCapability` 时显示「设备不支持」并禁用开关。

降级态（关闭/不支持）也升级质感：实色底 + glassBorder。

### 3.3 `ui/components/Liquid.kt` — 液态 Modifier 集

全部 remember 预分配 Animatable/规格参数：

| Modifier | 行为 | 用于 |
|---|---|---|
| `liquidPress()` | 按压缩放至 0.96 + snapBouncy 过冲回弹 | 图标/chip/图层行/卡片 |
| `liquidPressGrow()` | lerp 微放大 (≈1f→1f+4dp/h)，LiquidButton 参数模型 | 大按钮 |
| `liquidLean()` | 触点跟随位移 `maxOffset * tanh(0.05 * offset/maxOffset)` 饱和倾倒 | 工具栏大按钮 |
| `liquidHighlight()` | Canvas 径向渐变高光追踪触点 | 配合以上使用 |

面板出入场统一 springSoft 缩放+位移+淡入，替换各处 tween 硬切。

## 4. 品牌蓝克制化（修订版：不动饱和度）

1. **色板不动**: MorandiDark/Light 的 accent hex 与 Monet coerce 范围保持原样。
2. **用法重分类**（改 ReComponents 共享组件，全 app 继承）：
   - 大面积 tint 背景（选中 chip / menu item 整块 `accent.copy(0.12f)` 底）→ 中性 `panelHi` 底 + accent 低透明度细节染 icon/文字/细指示条；确需保留底色的场景降 alpha（0.12 → ~0.08）
   - 实心填充收窄但保实色（对比度底线）：primary 按钮、Switch checked、slider 填充、取色器当前色
   - accent 作文字/图标色处不降透明度（可读性）
   - 选中态模式统一: icon 变 accent + 小圆点/短横线指示
3. **扫尾**: grep 非组件处 `accent.copy(alpha ≥ 0.3)` 的大面积用法单独过一遍。

主页只继承新 token 与玻璃样式，不改布局。

## 5. 其他决策

- `blurBackground` 默认 false → **true**；设置项保留作低端机降级开关
- HomeBottomBar 的不一致 HazeStyle 参数顺带统一进 Glass
- MainActivity 页面转场、画布手势代码不动

## 6. 实施顺序与验证

| Phase | 内容 |
|---|---|
| 1 | 新增 Motion.kt / Glass.kt / Liquid.kt（纯新增，可编译） |
| 2 | Theme 无改动（修订版）；仅 PaintViewModel blurBackground 默认 true + Glass 能力判断接线设置面板 |
| 3 | ReComponents 接入三件套（glassBorder/liquid*/spring 出入场/accent 用法重分类） |
| 4 | 12 处内联 HazeStyle → Glass.barStyle/popupStyle；各面板 tween → springSoft |
| 5 | 大面积 accent 扫尾 |

每 Phase 后 `./gradlew :app:compileDebugKotlin`；最终 `lintDebug` + `assembleDebug`；引擎无关改动，无需 C++ 回归，UI 动效真机抽查。

## 7. 风险与对策

- API<31 设备: Glass 内置 SDK 判断强制实色降级，设置项禁用+提示
- 零分配: Liquid 全部 remember 预分配；不进笔画/手势热路径
- 回归面: ReComponents 是全 app 共享组件，Phase 3 改动后需逐页面抽查（绘画页工具栏/图层面板/笔刷面板/主页）
