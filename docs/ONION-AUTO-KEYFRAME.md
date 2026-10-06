# 自动建帧后的洋葱皮缓存失效

## 报告与范围

来源：PR #68 作者的附带报告 https://github.com/LanRhyme/ReveriePaint/pull/68#issuecomment-5995918833 。用户确认在主线 1.4.3 复现。PR #68 未修改本问题涉及的 C++ 或自动建帧逻辑，修复独立基于主线 `49f08196`。

影响路径：空白/重复帧新增、删除、复制、克隆、移动，以及这些操作之后的同步画布合成。Kotlin/JNI 签名、播放切帧、笔画热路径、帧缩略图精准失效均不变。撤销重做仍由现有 `syncLayersFromImage → bumpKeyframeThumbGen` 作废缓存。

## 代码证据

- `setAnimationCurrentTime` 在时间改变时调用 `invalidateStrokeOnionCache`。
- 停在曝光延长区时，`strokeOnionProjection` 可能已经缓存“上一关键帧作为当前帧”对应的洋葱皮与覆盖范围；缓存命中仅看时间、配置代际和显式脏标记。
- `ensureKeyframeForPaintOnRenderThread` 调用 `addKeyframe` 后直接落笔；当前时间不变。旧 `addKeyframe` 只标记画布脏区，没有作废上述自持缓存。
- `compositeLayersRange` 还会先用旧覆盖范围决定是否取洋葱皮。因此只更新画布或 Krita 图层缓存不能保证重新计算上一帧的显示范围。
- Krita 的 `activeKeyframeTime` 直接查询关键帧映射，`KisOnionSkinCache` 另有 `channelHash` 校验。现有 JNI `flushOnionSkinCaches` 只清 Krita 那份缓存。尚无证据需要修改 Krita 库或新增 JNI。

修复在成功改变关键帧结构后显式作废自持洋葱皮缓存和覆盖范围；幂等/无效输入的早退路径不触发。只在结构操作发生时处理，不在每次落笔或每次渲染上扫描整个关键帧通道。

## 验证状态

- 补丁检查通过：一个 C++ 文件增加 9 行；另有本文档和完整重建的预编译库。
- 原生完整构建通过：40 个编译/链接步骤完成；303 个 JNI 导出均保留，未新增接口，依赖闭包和强符号校验通过。已更新本分支预编译库。SHA-256：`f7b1d657488deb24b2251b128fb01f9b785b0f18d15c038e451bebdbc994210e`。
- 2026-10-05 在一加 Ace 3（PJE110，Android 14 / API 34，arm64）完成真实 JNI 像素对照。相同 13 项检查中，主线原生库 10 项失败、3 项对照通过，修复库 13 项全部通过。
- 实际调用 Kotlin `ensureKeyframeForPaintOnRenderThread`，再执行原生笔画开始、移动、结束；验证自动建帧后、笔画进行中、抬笔后都显示上一帧洋葱皮，且新笔画确实写入画布。全部原生操作运行于应用的 `renderHandler`。
- 播放头不移动时，重复、删除、复制、克隆、移动关键帧的即时合成结果，均与切换时间后重新计算的参考结果一致。建帧撤销重做、重复建帧不增加撤销项、手动建帧模式、关闭洋葱皮及建帧后切时刻的对照通过。旧版撤销测试在初始洋葱皮断言即失败，不能据此单独断言旧版撤销逻辑有问题。
- 64×64 对照场景中，左侧为第 0 帧、右侧为第 1 帧。停在第 2 帧自动建帧后，旧库像素为左 `80ff0000`、右 `00000000`；修复库为左 `00000000`、右 `80ff0000`（ARGB）。证明旧库保留上上帧且遗漏上一帧，修复后两者均正确。仅调用 Krita 图层缓存清理的旧库对照仍失败。
- 测试 APK 使用同一份已编译的隔离 Kotlin 宿主，仅替换原生库；逐项核对 APK 内容确认有效载荷唯一差异为 `libreverie_jni.so`，55 个 Qt 库均与原始手机宿主一致。该宿主含其他开发中的 Kotlin 代码，本测试未调用其图案填充接口，不作为发布 APK。此前模拟器启动失败后改用上述真机，不将模拟器计为通过。
- 2026-10-05 至 10-06 在此工作树补跑 `:app:compileDebugKotlin`，两次均因 JVM 原生内存分配失败退出。第二次已改用单 worker、进程内 Kotlin 编译、1536 MiB 堆、Serial GC 和两个处理器；仍未完成，不能计为编译通过。日志为 `kotlin-compile.log`、`kotlin-compile-retry.log` 及相应 JVM 崩溃日志。
- 验证边界：本轮完成原生完整构建、测试 instrumentation 编译、手机 A/B APK 打包安装及真实引擎运行回归；主应用 Kotlin 编译受上述环境问题阻塞，未重新执行 Gradle 单元测试或完整 APK 构建，也未模拟完整触屏 UI 操作。Kotlin 源码未修改。
- 本地证据目录：`F:/Codex/work/reveriepaint-onion-auto-frame/`，包含 `OnionRegression.java`、`phone-{baseline,fixed}-extended.{log,json}`、前后渲染 PNG、`phone-regression-summary.json` 和原生构建日志。

## 复现与验收

1. 新建动画轨道，在第 0 帧左侧画一个方块，第 1 帧右侧画另一个方块；打开只显示前 1 帧的洋葱皮。
2. 移到尚无关键帧的第 2 帧并等待画布显示延长的第 1 帧，再落笔触发自动建帧。
3. 新帧应该显示右侧第 1 帧的洋葱皮，左侧第 0 帧不应作为“前 1 帧”残留。检查落笔前后、笔画进行中、抬笔后的画面。
4. 对照点「+」建帧；两条路径的洋葱皮应一致。
5. 撤销笔画、撤销建帧，再重做，确认曝光恢复与洋葱皮切换正确。
6. 在播放头不移动时复制、重复、删除或移动邻近关键帧，检查邻帧与覆盖范围同步更新。
7. 关闭洋葱皮、编辑已有帧、使用手动建帧模式，确认不引入多余关键帧和叠影。
