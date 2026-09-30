# 液化透明合成修复与性能记录

日期：2026-09-30；基线：eac3c13；环境：Windows + WSL Debian，Qt 6.6.3、NDK r25c、既有 Krita Android 工具链

## 复现与根因

用户反馈为拖动中出现矩形，抬笔后短暂保留，保存重开不出现。离屏测试使用生产 GLES 着色器，输入 alpha=0、RGB 非零的像素，复现旧上传路径下 528 个背景像素被矩形污染。Krita RGB8 是 straight BGRA，而 Android Bitmap、GL_ONE 合成和 Surface 消费 premultiplied RGBA；仅交换通道会把透明像素的隐藏 RGB 直接叠到背景。普通画布脏区输出也存在相同契约错误，解释了覆盖层退出后仍可能短暂残留的显示问题。此实验稳定复现这一机制，但尚未在用户设备上确认其是否为全部偶发问题的唯一来源。

## 实现与影响面

- PixelAlpha 统一设备到显示的预乘转换、GPU 回读的反预乘和透明度加权插值；ARM64 上传使用 NEON
- 全量、局部脏区、缩放画布保持相同预乘契约；变换模块使用 straight QImage 的既有转换函数不变
- CPU 预览修正 texel center；CPU/GLES 对源图外部采样返回透明，避免 CLAMP 色带
- 输入按顺序保留触控历史、折返与压力；固定分段插值参数，帧预算只限制推进数量
- GLES 每批最多消费 24 个 dab；提交 fence 等待全部已排队 dab 完成，避免抬笔尾部尚未累积就回读；读取失败返回原有 CPU 重放路径
- 位移场同 tile 插值由四次查找减少为一次，按 tile 行发布；不改变累积、归一化和衰减数学语义
- scratch 纹理按 64 像素容量复用；field 路径去除无效逐帧 materialize 调度

保留引擎 HandlerThread、前后台 Bitmap 双缓冲、原有事务及撤销写回流程。队列缓冲复用，容量不足时才增长，未新增逐帧对象分配。输入长时间超出消费能力仍可能积压，须真机检查长笔画抬笔延迟。

## 自动验证

`bash scripts/test_liquify_transparency.sh`：宿主 g++ + Mesa llvmpipe，直接提取生产 Kotlin shader

- 原路径矩形复现、修复后透明输出；缩放 0.5/1/2/4，平移 -7/0/9，旋转 0/0.4 弧度
- 全部 256×256 alpha/颜色组合、原地转换、不同 dirty stride 与 tile/SIMD 边界尺寸
- 半透明 GPU 回读转换、隐藏 RGB 不污染可见颜色
- 5 种液化模式、场步长 1/2/4/8/16，与冻结基线逐点浮点完全相同
- JVM：折返/压力、队列增长与环绕、亚像素慢速、分帧强度、提交 fence 和旧手势重置

320 dab、5 次中位数（ms，宿主 CPU，非 Android FPS）：

| 笔刷尺寸 | 基线 | 优化后 | 耗时下降 |
|---|---:|---:|---:|
| 8 | 17.44 | 10.03 | 42.5% |
| 16 | 65.84 | 38.98 | 40.8% |
| 64 | 65.53 | 38.37 | 41.4% |
| 256 | 28.71 | 16.69 | 41.9% |

基准仅度量位移场内核；不能据此推断整机 FPS、峰值内存或端到端延迟。NEON 分支已交叉编译，宿主执行的是标量分支。

## 构建与真机验收

原生构建使用本地 RGB8 pigment 库对应的 `REVERIE_MASKING_FLOAT16=OFF`；默认配置仍为 ON。原生库重新编译、strip 后替换项目预编译库，再通过 Gradle 生成独立 beta Debug APK。

尚无 ADB 设备连接，不能声称完成以下真机验收：透明/半透明多图层与混合模式、选区与 Alpha 锁、撤销重做、快速连续多笔、GPU context 丢失、长笔画溢出回退、低端设备 60/120/240Hz 采样、峰值内存与 P50/P95 延迟。应以相同画布、笔刷、路径和压力录制分别测试旧 APK 与本 APK；保存重开验证像素、撤销重做恢复原图，并检查预览叠加是否仍有半透明加深。当前改动修复已复现的 alpha 契约问题，不代表完成与专业软件的全面体验等价验收。

## 本次交付结果

- C++ Android arm64 交叉编译、Kotlin 编译、394 项 JVM 测试、离屏 GLES/原生测试通过
- `assembleDebug` 成功，APK：`app/build/outputs/apk/debug/app-debug.apk`，92.50 MiB
- 包名 `com.reverie.paint.beta`，版本 `1.3.8-test`（25），可与正式包并存
- apksigner v1/v2 验证通过；APK 中 JNI 与重新编译并 strip 的预编译库 SHA256 完全一致
- APK SHA256：`4678e4bf68dd2f2d04b121009c8791d57a021913e64b20190244004bb9586827`
- JNI SHA256：`1302c04434991e6d3ef53f80841ddfa49a69f796fd34936ee873ead11563d031`
- Lint 未通过：135 errors、536 warnings、34 hints；135 个错误位置逐项与 HEAD 比对，均在未修改行，包括已有 Half/API、Compose 资源调用等。未设置忽略基线或修改全局检查规则
- 日志：`build/liquify-alpha-native.log`、`build/liquify-transparency.log`、`build/liquify-apk-build.log`；Lint 报告：`app/build/reports/lint-results-debug.html`
- ADB 当前无设备；未提交 Git、未发布版本

## 第二轮：2048×2048 真机卡顿与闪烁反馈

用户环境：Android 16、天玑 9300+、2048×2048、300 DPI。尚未获得笔刷尺寸、当前预览模式、逐帧 trace；以下为代码定位和宿主验证，不是该设备的帧率实测。

### 新发现和修复

1. GPU/AGSL 收尾先清覆盖层，再异步安排新 Bitmap 渲染，存在露出旧画迹的时间窗。新增独立于 GLES 的手势世代与 Bitmap 消费栅栏：在引擎结束事务后继续保留预览，直到 Canvas 确实录制了提交后的 Bitmap，再退出覆盖层。取消及下一笔会废弃旧栅栏；不能把 CPU 已写回误认为屏幕已呈现。TextureView 与 HWUI 仍由系统异步合成，跨 surface 的原子交换尚需设备帧捕获验证。
2. 每个插值 dab 原来分别发布唤醒和局部 invalidate。改成每批输入完成后发布一次，保留所有 dab 和压力，不改变轨迹；CPU/GPU 绘制队列仍各自在原线程。
3. CPU 物化队列的去重键由线性 QVector 改为 QSet；同一行相邻 tile 合并处理，交互最多 512×32，收尾最多文档一行。不跨空隙、不跨行、不丢最后不足宽度的边界块。2048×2048 同覆盖测试中 warp/painter/composite 调用数量由 1024 次减少至交互 256 次/收尾 64 次。
4. Alpha 加权插值遇到四个全透明 texel 时直接输出零；源中的隐藏 RGB 不影响结果。1,048,576 个样本、90% 透明、五次中位数：旧 7.504 ms、新 2.591 ms（宿主 CPU），输出逐字节一致。
5. Debug 每笔结束输出一次 `ReverieLiquify` 日志，包含文档/笔刷尺寸、field/GLES 路径、输入/dab/呈现帧/上传/丢弃计数。避免下一轮把不同渲染路径混在一起判断。

### 被否决的实验

尝试按笔刷尺寸把场降为 2/4/8 倍以减少 GPU 填充，但对全分辨率场的最大位移误差分别约 0.138/0.321/1.908 px，后两者超出本轮 0.25 px 实验门槛。因此已删除自动降采样实现，最终产物保持原有精度。测试保留诊断实验以记录决定，不将其当作通过的精度优化。

### 影响范围与限制

影响普通/场液化收尾、取消与连续手势、Canvas Bitmap 呈现回调、原生物化队列和透明插值；其他工具不持有退休栅栏时直接跳过，无 JNI 上移 UI 线程。新增回归覆盖旧 Bitmap 不得清除预览、旧手势不得影响新手势、取消释放栅栏及 tile 合并覆盖。

这轮尚未证明透明半透明多图层的预览重复叠加、系统 Surface 合成和低端设备峰值延迟已全部解决。请继续用同一个项目/笔刷做 A/B，保留问题出现时的 `adb logcat -s ReverieLiquify ReverieLiquifyGles ReverieCore`；需要以路径和时序数据区分剩余瓶颈。

### 第二轮构建验证

- 398 项 JVM 测试、原生/GLES 回归、Android arm64 C++ 编译及 Kotlin/APK 编译通过
- APK：`app/build/outputs/apk/debug/ReveriePaint-liquify-r2.apk`，93.78 MiB，包名 `com.reverie.paint.beta`
- APK SHA256：`76c9d19b1c7086e5a06ff339a85e4bbabf029edaac03f3cfcb8eda2acf3f30d2`
- JNI SHA256：`6c3137d333793c66cd336b526b6a9863be3e1292d45cba1ad13e5753bee3d965`，确认 APK 中与重新编译的预编译库逐字节一致
- apksigner verify 返回 0；日志 `build/liquify-r2-signature.log`
- Lint 仍为 135 errors / 536 warnings / 34 hints，未宣称通过；报告来自本轮完整检查
- 原生构建日志 `build/liquify-presentation-native.log`，最终 Kotlin/打包日志 `build/liquify-presentation-package.log`，Lint 日志 `build/liquify-presentation-final.log`
- 再次检查 ADB 无连接设备，未进行本地真机 FPS 验收；不将宿主微基准解释为天玑或低端设备的端到端性能

## 第三轮：透明画布下的液化残影

真机回归确认液化性能问题已基本解决，但透明背景 / 透明画布上液化仍留残影。本轮是**纯合成与状态
修复**：不改动既有液化算法的几何、参数、计算流程与写回实现。

### 根因

交互预览只携带**目标图层**的形变像素，却叠在"已经合成好的文档"之上：

- 形变把像素搬走后，原地的**未形变**像素仍然留在画布合成里；
- 画布不透明时，形变后的不透明像素把原地盖住，所以看不出来；
- 画布透明（或图层本身带透明区）时，原地与被搬过来的内容同时可见 —— 这就是残影。

换个说法：预览对同一块区域做的是**叠加**而不是**替换**。第一轮的预乘契约修复解决的是"透明像素
隐藏 RGB 污染背景"的矩形污染；本轮解决的是"目标图层原始像素没有被替换掉"的残留，两者不重叠。

### 修法（扩而不改）

预览期间让**画布**在这块区域里"不含目标图层"，目标图层的像素改由预览自己提供，于是预览对该
区域是替换而非叠加。液化算法、参数、流程与写回像素一字未改。

- `ReverieCore::setLiquifyPreviewBaseRect`：预览基座矩形；空 = 不排除（退回改动前的行为）
- `ReverieCore::ensureLiquifyPreviewBase`：以 `compositeLayersRange(..., excludeIdx)` 合成
  "整摞减去目标图层"；单调增长时只补新增边带，非单调（经典通路 worker 窗口平移）才整块重算
- `ReverieCore::applyLiquifyPreviewBase`：在 `renderToBuffer` 写完缓冲之后、叠加预览之前，把
  基座矩形内改写成不含目标图层的结果（1:1 路径）
- `ReverieCore::invalidateLiquifyPreviewBase`：手势结束 / 取消 / 预览目标不可见 / 场通路回退时
  摘掉，并让旧矩形按真实文档重读一次
- `compositeLayersRange` 新增 `excludeIdx`（默认 -1，递归进组），既有调用点零改动
- Kotlin：场通路的裁剪是整篇文档、真正出图的只有受影响矩形，由
  `LiquifyPath.quantizedPreviewRect` 向外量化到 64 像素后经 `setLiquifyPreviewBaseRect` 推给引擎；
  只有跨过一格才推一次，每次推送只让引擎重算新增边带

### 边界与限制

- 目标图层解析不到（遮罩 / 投影等非图层目标）时保持 -1，不排除、不猜，退回改动前行为
- 目标图层之上仍有图层时，预览按文档既有口径画在最上层（原有已知近似，未扩大）
- 缩放视口路径（缓冲宽 != 文档宽）不应用基座，维持原行为；本应用显示缓冲恒为文档尺寸
- 预览期间引擎多出的开销 = 每次基座变化时合成**新增边带** + 一次该边带读回，与笔刷同阶

### 第三轮验证

- 404 项 JVM 测试通过（含 `LiquifyPath.quantizedPreviewRect` 的 6 项新用例）
- Android arm64 C++ 交叉编译通过，JNI 导出 `setLiquifyPreviewBaseRect`
- 宿主透明度 / 几何回归 `bash scripts/test_liquify_transparency.sh`：PASS；位移场内核耗时相对
  基线比值 0.576~0.586，与第一轮持平（无回退）
- APK：`app/build/outputs/apk/debug/ReveriePaint-liquify-r3.apk`，97,004,912 字节，包名
  `com.reverie.paint.beta`，版本 `1.3.8-test` (25)，仅 arm64-v8a，可与正式包同装
- APK 内 `lib/arm64-v8a/libreverie_jni.so` 与 `third_party/android-native-libs/libreverie_jni.so`
  SHA256 完全一致：`dec594cd1185b970d764dd95dc99607659af6355e449e439238fa058a576540f`
- 无 ADB 调试线，真机验收（透明 / 半透明多图层、选区与 Alpha 锁、撤销重做、连续多笔）由用户
  侧载本 APK 完成；宿主结论不能替代真机帧捕获

## 第四轮：拖拽时的背景色线框闪烁

用户环境：透明画布残影已消失，但液化拖拽期间每次都出现**背景色矩形线框闪烁**，抬手立即消失。
本轮仍不动液化算法，只修"预览基座矩形"与覆盖层绘制范围的**一致性**与**推进时序**。

### 根因

第三轮为了让预览"替换"而不是"叠加"目标图层，引入了"预览基座"：预览期间画布在这块区域里
不合成目标图层，由覆盖层补上形变后的它。但两处矩形当时不一致：

1. **矩形不一致（主因）**：推给引擎的基座用 `quantizedPreviewRect` **向外**量化到 64 像素，
   而覆盖层真正绘制的是未量化的受影响矩形（shader 的 `uDrawSize` 裁剪）。基座比绘制范围
   最多大 64 像素 ⇒ 那圈"被挖掉却没人补"，透明画布上直接露出背景 —— 由于差值恰好落在矩形
   四边，视觉上就是**一圈背景色线框**；随着受影响矩形增长跨过 64 像素格，线框宽度跳变 ⇒
   **闪烁**；抬手时覆盖层清空、提交把真实像素写回 ⇒ 线框立刻消失。
2. **时序不一致（次因）**：画布（引擎侧 Bitmap）与覆盖层（GLES TextureView）是两条独立呈现
   路径，无法原子更新。即使矩形相同，只要"挖掉"领先于"画上去"，那一圈同样会露一帧背景。

### 修法（仍是纯合成 / 时序修复）

- [`LiquifyPath.previewRect()`](app/src/main/java/com/reverie/paint/model/LiquifyPath.kt:169)：
  受影响矩形 → 整数矩形，改为**向内**对齐。同一个矩形同时喂给
  `LiquifyGlesPreview.pushDrawRect`（覆盖层绘制范围）与 `setLiquifyPreviewBaseRect`（基座），
  两者逐像素一致 ⇒ 既不会"被挖掉没人补"（露背景），也不会"没被挖却在叠加"（深色描边）。
  向内对齐使边界 1~2 像素既不挖也不叠，显示未形变原像素 —— 该处位移已按场的衰减曲线归零。
- [`LiquifyPreviewBasePolicy`](app/src/main/java/com/reverie/paint/model/LiquifyPreviewBasePolicy.kt:23)
  （新增，纯逻辑）：基座**只能推进到覆盖层已经提交上屏的矩形**，永不领先；带最小增长门槛
  （影响半径的 1/8，夹到 4~64 像素）把"每次推进都要合成并渲染一圈边带"的频率压到与笔刷尺度
  同阶；矩形一旦收缩/平移立刻跟随（否则基座会大于绘制范围，又变成露背景）。
- [`LiquifyGlesPreview.noteDrawRectCommitted()`](app/src/main/java/com/reverie/paint/core/LiquifyGlesPreview.kt:800)
  与 `copyCommittedDrawRect()`：渲染线程在 `swapBuffers` 成功后上报"真正上屏的矩形"，UI 线程
  只读它；手势开始/结束时作废，避免跨手势残留。
- [`CanvasTouchView`](app/src/main/java/com/reverie/paint/ui/painting/canvas/CanvasTouchView.kt:3722)：
  每帧先用 `previewRect` 推覆盖层绘制矩形，再按策略把"已上屏矩形"推给引擎做基座；补点循环里
  不再单独推浮点矩形（否则两者会差出一圈）。
- 引擎侧（`setLiquifyPreviewBaseRect` / `ensureLiquifyPreviewBase` / `applyLiquifyPreviewBase` /
  `invalidateLiquifyPreviewBase` / `compositeLayersRange(excludeIdx)`）**无需改动**：它们的入参
  语义没变，仍是"给定矩形内按不含目标图层合成"。

### 边界与已知代价

- 两条呈现路径的延迟差仍然存在：基座滞后于覆盖层最多一帧多，滞后期间那一圈是"同一份形变像素
  叠了一次"（不透明内容逐像素相同，肉眼不可见），而不是露背景。
- 目标图层之下/之上的图层合成顺序沿用文档既有口径；目标解析不到时基座保持为空（不退化为挖洞）

### 第四轮验证

- `:app:compileDebugKotlin`、`:app:testDebugUnitTest`：通过，**411 项测试 0 失败**；新增
  `LiquifyPathTest` 中"覆盖范围 ⊆ 受影响区域""文档夹取""拖拽 200 帧单调包含"以及
  `LiquifyPreviewBasePolicyTest` 的 8 项（首次推进/重复帧不推进/提交序列同序不领先/最小增长
  门槛/收缩立刻跟随/复位/非法输入）
- 宿主 `bash scripts/test_liquify_transparency.sh`：PASS；位移场内核耗时相对基线比值
  0.5757~0.5893（无回退）
- APK：`app/build/outputs/apk/debug/ReveriePaint-liquify-r4.apk`，97,644,236 字节，
  包名 `com.reverie.paint.beta`，版本 `1.3.8-test`(25)，仅 arm64-v8a
- APK SHA256：`0be3cee896cebcd513270cc3b9d6e365c19c0590e7b6885af3025ca1bff41510`；
  包内 `lib/arm64-v8a/libreverie_jni.so` 仍与
  `third_party/android-native-libs/libreverie_jni.so` 一致
  （`dec594cd1185b970d764dd95dc99607659af6355e449e439238fa058a576540f`，本轮未改 C++）
- 本轮仍无 ADB 设备（`adb devices` 为空），真机拖拽验收待用户侧载：快速拖拽、低端机 / 天玑、
  不同缩放与平移、多图层与透明背景，重点确认抬手后无残留边框
