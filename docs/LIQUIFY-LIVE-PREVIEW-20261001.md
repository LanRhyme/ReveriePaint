# 液化残影与实时预览：2026-10-01

基线：1a3d587。用户真机：2048×2048、300 DPI、Android 16、天玑 9300+。本轮没有设备帧捕获，代码缺陷与宿主回归不能当作真机 FPS 结果。

## 本次定位

1. 原排除目标图层的底图只处理文档与显示缓冲 1:1 的路径，缩放缓冲仍读取包含旧画迹的投影，再 source-over 叠加移动后的预览。预览透明处不会擦除旧画迹。
2. EGL swapBuffers 只提交 BufferQueue，原实现却当作 HWUI 已消费，并且用当前 gestureId 标记可能属于上一笔的帧。几个 volatile 整数也不能保证矩形快照一致。
3. 底图推进只在下一批触摸输入中检查已提交矩形。快速短笔或移动后停住时，最后一帧不会主动推进底图。按笔刷半径设定的推进阈值又会长期保留半透明重叠带。
4. 即使底图完全覆盖本次读取区域，仍先读取原投影、再读底图覆盖，增加内存读写。

## 实现

- 全量、脏区和缩放路径统一调用 readLiquifyDisplayRegion，在文档空间替换底图，然后统一预乘并进行视口过滤。底图完整覆盖读取区域时直接读底图，不再读旧投影。
- PixelRegion::replace 采用带行跨度的替换语义，包含 alpha=0，检查越界输入。
- 每次 EGL 提交设置单调纳秒时间戳，用固定容量元数据环记录该帧真实的手势和矩形。TextureView.onSurfaceTextureUpdated 按 SurfaceTexture.timestamp 精确匹配消费帧。丢弃未知、过期、上一手势时间戳，不借用最新帧的矩形。
- UI 收到实际消费事件就推进底图，无需继续移动笔。矩形重复时不再排队，取消原来依赖移动距离的滞后阈值。引擎执行时再次检查手势，避免旧任务改动新笔画。
- 仅单一普通绘画层、可见、正常混合、100% 图层不透明度、无选区/透明锁/裁剪/动画等条件下使用固定整层覆盖。源纹理本来已经覆盖文档，新增内存仅一份四整数矩形；预览在同一 GPU 帧中合成棋盘与变形图层，文档内输出 alpha=1，从合成语义上阻止旧画迹透出。此分支完全跳过底图重建/Bitmap 上传；像素网格开启时保留原路径。提交离屏帧显式关闭棋盘合成，保留透明像素。像素自身仍可透明或半透明。
- 不修改位移场分辨率、衰减或补点强度；多图层等复杂场景继续局部预览路径。

## 回归与证据

- 新增 LiquifyFrameRectsTest：已消费帧不能借用新提交矩形；停笔无需新输入；旧手势拒绝；环容量溢出/未知时间戳拒绝；2048² 整层预览连续 240 帧底图仅推进 1 次。
- 原生测试使用生产 PixelRegion/PixelAlpha：旧位置透明度累积 2048，替换后 0；1/2/4 倍面积聚合保持零；非零脏区偏移、行跨度、越界拒绝和半透明颜色转换通过。这是像素/覆盖测试，不是 Android Surface 或 Qt 缩放器的端到端截图测试。
- 当前仓库两项旧采样测试仍假定 latest-wins 计数，已依据现有 FIFO 行为修正：部分推进未完成的输入仍计入 lag，不应计入已完成输入数。
- 393 项 JVM 测试全部通过；Android arm64 原生编译通过；Lint 为 135 errors / 536 warnings / 34 hints，错误位置与 HEAD 比对均在未修改行，未关闭检查。
- 原生回归命令：`bash scripts/test_liquify_live_preview.sh`；构建日志：`build/liquify-r3-native.log`、`build/liquify-r3-package.log`。

## 构建环境与验收边界

项目已从 D:/Projects/ReveriePaint 迁到 C:/Users/xuantree/projects/ReveriePaint。Debian 中 Qt/Krita 依赖的 272 条旧项目符号链接仅在新目标确实存在时重定向；使用新 jni-build-c 目录，保留旧构建目录和源码。没有复制 local.properties 内容。

尚需真机确认 EGL 呈现时间戳对应行为、透明单层与多层/混合模式、第一次预览交接、连续短笔、旋转缩放、撤销重做和低端机延迟。普通单层通过自包含不透明预览避免两个 Surface 的透明叠加；复杂图层仍使用两个独立 Surface，不能凭本轮模型测试声称所有闪烁或多层混合问题都已解决，也不能声称与商业绘画软件完全等价。

## 完整替换预览补充验证

`tests/native/liquify_scene_test.cpp` 通过 `scripts/test_liquify_scene.py` 从生产 Kotlin 文件提取实际 GLSL，使用 Debian Mesa/EGL 无窗口 GLES 执行。先将旧画布清成绿色，再绘制透明空洞和 50% 透明新画迹：显示结果无旧绿色贡献；关闭显示棋盘后，提交结果 alpha 分别精确保持 0/128，红色保持 0/100。测试通过。此测试验证片元与混合语义，不是 Android 整条显示链的真机测试。

命令：`python3 scripts/test_liquify_scene.py && g++ -std=c++17 -O2 -Wall -Wextra -Ibuild tests/native/liquify_scene_test.cpp -lEGL -lGLESv2 -o build/liquify-scene-test && EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 ./build/liquify-scene-test`。

最终 Kotlin/APK 构建日志：`build/liquify-scene-build.log`。完整替换分支每次拖动的底图 JNI 调用从随影响矩形推进降为 0，未新增图层尺寸纹理；代价是每帧覆盖可见画布而非仅笔刷包围盒。没有连接 Android 设备，不能将调用次数变化换算为真实帧率提升。
