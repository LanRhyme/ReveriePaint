# vivo / iQOO 手写笔适配说明

日期: 2026-10-07
状态: 已实现并通过真机验收 (vivo Pad2 (PA2373) + vivo Pencil2, 笔迹预测 / 双击切换 / 书写振动 三项实测通过)

## 1. SDK 集成方式 (仓库内置, 不走 maven)

官方接入指南要求在项目级 build.gradle 添加 `https://repos.vivo.com.cn/maven/repository/external-lib/`
(HTTP, 需 `allowInsecureProtocol`) 并依赖 `vivo:penengine-simplify:1.0.0.7`。本项目采用与 OPPO
`libforecast.so` / `Qt6Android.jar` 一致的「仓库内置」策略, 保证克隆即可离线构建、不改仓库配置:

| 仓库内文件 | 来源 (官方 AAR `penengine-simplify-1.0.0.7.aar`) |
|---|---|
| `app/libs/vivo-penengine-simplify-1.0.0.7.jar` | AAR 内 `classes.jar` |
| `third_party/android-native-libs/libtrack_prediction.so` | AAR `jni/arm64-v8a/` (被 `copy_jni_libs.sh` / `copyPrebuiltJniLibs` 打包进 jniLibs) |
| `app/src/main/assets/optparam*.cfg` (4 个) | AAR `assets/` (SDK 运行时从 **app assets 根**按固定文件名读取, 不能放进子目录) |

`app/build.gradle.kts` 仅新增一行 `implementation(files("libs/vivo-penengine-simplify-1.0.0.7.jar"))`。
release 未开启 minify, 无需 proguard keep (官方要求的 3 组 keep 规则已记录于此备查:
`com.vivo.trackpredictor.**` / `com.vivo.bluetoothpen.**` / `com.vivo.penengine.impl.**`)。
清单仅新增两条 `<queries>` (`com.vivo.penwrite` / `com.vivo.penalgoengine`), 未引入
AAR 清单里的 `QUERY_ALL_PACKAGES` (以精筛 queries 替代)。

## 2. 已核对的 SDK 真实 API (javap 反编译, 非文档推测)

- `com.vivo.penengine.impl.VivoAlgorithmManagerImpl(Context)`
  - `computeEstimatePoint(MotionEvent): PointF` — 内部自行消费历史点与 action 状态机;
    以 `event.getX()/getY()` 读 **pointer 0**, 故多点触控时需由调用方保证 pointer 0 为手写笔
  - `isEstimateEnable()` / `release()`
  - 仅在 `Build.BRAND == "vivo"` 且 `isTablet()` 时真正启用, 否则返回当前点 (无害退化)
  - 预测核心: `TrackPredCore`(Java) + `libtrack_prediction.so`; `init()` 会把
    `optparam1130_best.cfg` / `optparam240_12.cfg` 从 assets 拷到 `cacheDir` 再喂 native
  - 形状识别 (`computeShapeData`) 走 AIDL 服务 `com.vivo.penalgoengine`, 本项目未使用
- `com.vivo.penengine.impl.VivoStylusGestureManagerImpl`
  - `getInstance(Context)` / `registerGestureCallback(OnGestureCallback)` / `registerLifecycle(Activity)` / `unregister*`
  - 绑定服务: `com.vivo.penwrite` → `com.vivo.bluetoothpen.service.BluetoothPenService`
  - 手势回调 `onGesture(gestureType): Boolean` — **2 = 双击主键/笔身, 3 = 单击主键, 4 = 单击副键**;
    返回值: 触发业务逻辑才返回 true, 否则 false (未消费, 交还系统)
- `com.vivo.penengine.impl.VivoStylusManagerImpl`
  - `init()` (登记回调) / `enableWritingVibrate(Boolean)` / `destroy()`
  - `writingVibrate(boolean,int)` 为包内私有, 只能经本类驱动;
    **`destroy()` 不会停止振动 → 释放前必须显式 `enableWritingVibrate(false)`**
  - 振动强度由系统设置门控: `vivo_stylus_haptic_feedback_write_switch` (总开关) +
    `vivo_stylus_haptic_feedback_write_select_level` (1/2/3 级 → 96/128/160);
    手势触觉: `vivo_stylus_haptic_feedback_gesture_switch`

## 3. 本项目的接入点

| 能力 | 接入点 |
|---|---|
| 笔迹预测 | `CanvasTouchView` 持有 `VivoAlgorithmManagerImpl`, 与 OPPO 预测共用 `predictedScreenPoint` 消费管线 (onDraw 渐变切线); 门控沿用 `vm.isCurrentBrushPredictionEligible` (含全局预测开关) |
| 双击/按键切换 | `VivoStylusAdapter.handleGesture` → `vm.executeStylusAction(...)`; 动作映射存于 `paint_prefs` 的 `vivoDoubleTapAction` / `vivoPrimaryClickAction` / `vivoSecondaryClickAction` |
| 书写振动 | `StylusFeedbackManager.vivoWritingVibrateHook` (由 `VivoStylusAdapter` 注册), 挂在既有落笔/抬笔调用点; 适配器内做状态去重, 每次书写仅 2 次 binder 调用 |
| 按键切换 (按住变橡皮) | 复用 `StylusDriver.isSideButtonEraseActive` 的 `VIVO_PENCIL` 分支 + 画布层 `tempEraseActive` |
| 型号识别 | `VivoStylusAdapter.detectModel`: 输入设备名优先, 回退默认 Pencil2; 设置弹窗提供 AUTO + 手动下拉兜底 |

能力表 (官方文档型号矩阵) 落地为 `VivoPencilModel` 枚举; 全系支持笔迹预测, 双击/振动/按键按型号区分。

## 4. 真机验证记录 (2026-10-07, vivo Pad2 (PA2373) + vivo Pencil2)

1. 笔迹预测: 纯色实心笔刷绘制时笔尖有前向切线延伸 — 实测通过
2. 双击切换: 双击笔身切换画笔/橡皮 (按设置的动作) — 实测通过
3. 书写振动: 落笔时笔身持续微振, 抬笔立即停止 — 实测通过
4. 回归项 (手掌压屏不断线 / 多指缩放 / 其他品牌设备零副作用) 随日常使用持续观察

排查日志标签: `VivoAlgorithmManagerImpl` / `VivoStylusGestureManagerImpl` / `VivoStylusManagerImpl` /
`trackPredictionSDK` (SDK 自带 EngineLog), 本项目侧: `ReverieVivoStylus` / `ReveriePerf`。

## 5. 已知边界

- 预测仅在 vivo/iQOO 平板生效 (SDK 自检 BRAND + 平板判定, 我方再加品牌门控双保险)
- 书写振动同时受 vivo 系统级「书写振动」开关约束, 应用内开关只能再关不能强开
- 官方未给出「平板 ↔ 笔」对应关系, 自动识别可能误判 → 依赖设置内手动型号下拉纠正
- 多点触控时预测仅在 pointer 0 为手写笔时启用 (SDK 读点方式所限)
