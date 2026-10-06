# 对称参考线显示修复（#64）

基于主线 `49f08196`，独立分支 `fix/symmetry-guide-visibility`。

## 原因与改动

对称参考线原先只在 `assistedDrawing || drawingGuidePanelOpen` 时绘制。
关闭绘图辅助再收起面板会把线隐藏，与其他参考模式不一致。
移除这层显示条件后，是否显示由已有的 `GuideMode.SYMMETRY` 分支决定；
切换为 `GuideMode.OFF` 仍隐藏参考线。

仅修改 `CanvasOverlay.kt`，其余 diff 是移除条件后的缩进调整。
保持面板打开时才显示编辑控制柄、接受中心和旋转控制柄拖动的限制。
笔画镜像仍由绘图辅助开关决定，没有修改触摸、笔刷、JNI 或录制逻辑。

## 验证范围

本轮运行 Kotlin 编译、JVM 单元测试与独立包名 APK 构建。
运行检查通过设置与面板操作相同的 ViewModel 状态，比较实际 Compose 画布截图，
并向画布发送触控验证控制柄与镜像笔画；不把它记作完整点击面板的手动测试。
原始 APK 保留主线预编译原生库；模拟器运行使用仓库外的 Qt ARM 转译兼容副本。

测试基线使用 `e4d8053a` 实验 APK，其 `CanvasOverlay.kt` 与本次主线基准完全相同，
Git blob 均为 `779a41bb43a142d27a009bf82577a0ab450a826e`；它不是完整主线 APK。
构建日志、截图、外部运行脚本与详细结果保存于 `F:/Codex/work/reveriepaint-issue64/`。

## 本轮结果

- Kotlin 编译、520 项 JVM 单元测试和 Debug APK 构建通过
- 测试包签名、ZIP 完整性、112 个预编译依赖与主线逐文件核对通过
- 修复前的基线复现关闭面板后参考线消失，失败日志为 `before.log`，前后截图已留存
- 修复包 5 组运行回归通过：辅助关闭后收起面板仍显示参考线；其他三种对称类型；
  网格／等轴测／透视参考线；面板关闭防误拖、打开可调轴；辅助开关控制镜像笔画
- 每种参考模式均检查切换为 OFF 后参考线消失，画布区域截图恢复一致
- 测试通过设置 ViewModel 状态、向 CanvasTouchView 发送触摸和检查真实截图／像素完成；
  未做完整面板点击流程、真机或全量 Lint，未修改原生库

独立测试包：`ReveriePaint-issue64-test.apk`，应用名 `ReveriePaint #64 Test`，
包名 `com.reverie.paint.issue64test`，不会覆盖正式版或 QuickShape 测试应用。
SHA-256：`2180fdc007ae64568eb6de07790890aedb240f18e2ebf6244145b2cff73564f9`。
