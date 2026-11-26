# Krita 引擎补丁系列

本目录是 ReveriePaint 使用的 **Krita 引擎补丁集**，对应 fork 分支：

- 仓库：<https://github.com/LanRhyme/krita>
- 分支：`reverie-android`
- 基线：KDE Krita `master` @ `876c4cc0`（2026-11-16，6.1.0-prealpha）
- 生成方式：`git format-patch 876c4cc0..reverie-android`

## 补丁清单

| # | 补丁 | 作用 |
|---|---|---|
| 0001 | `feat-android-14-Android` | 14 个笔刷引擎改为静态注册（Android 无法 dlopen 插件）；根 CMake 在 ANDROID 下禁用 LibAV；去掉 Qt AndroidExtras 依赖；libs/global、libs/flake、libs/pigment、libs/widgetutils、libs/ui 的 Android 兼容修补 |
| 0002 | `fix-roundmarker-KisMarkerPainter` | 圆头笔改用标准覆盖混合，反复落笔不再越画越深 |
| 0003 | `fix-swap-Swap-qFatal` | 多步撤销时 Swap 槽位耗尽不再 qFatal 断言闪退，并抬高软限制阈值 |
| 0004 | `fix-roundmarker-COMPOSITE_OVER-memcpy` | 圆头笔边缘像素改用 COMPOSITE_OVER，消除笔画交叉处的白边 |
| 0005 | `feat-clipping` | 剪切蒙版引擎支持（剪切链表检索、基底定位、递归投影合成、KRA/PSD 落盘与读回） |
| 0006 | `feat-mypaint-Android` | MyPaint 引擎静态注册与 Android 构建支持 |
| 0007 | `fix-mypaint-Android-dlopen` | MyPaint 传感器连接改为懒初始化，修复 Android 上 dlopen 阶段崩溃 |
| 0008 | `fix-build-kis_lockless_stack.h-QtGlobal-Qt-6.6` | `kis_lockless_stack.h` 补 `<QtGlobal>`，修 Qt 6.6.3 下 `Q_DISABLE_COPY` 未定义 |
| 0009 | `fix-android-kritaui` | Android 视频编码器改用空实现桩（无 MLT/FFmpeg），修复 `libkritaui.so` 链接失败 |
| 0010 | `docs-REVERIE-PATCHES.md` | 在 fork 根目录加补丁层说明（提交清单 + 稳定契约） |
| 0011 | `fix-memento-memento-SIGABRT` | 撤销/重做按 memento 身份校验：`rollforward()`/`rollback()` 身份不符时保持现状，`purgeHistory()` 摘掉 cancelled 里的悬垂条目；修「本地事务 revert 后重做」的瓦片装错帧（笔仙）与 `delete[]` SIGABRT |
| 0012 | `fix-transaction-abort` | 事务结束时时间不一致改为安全警告（`KIS_SAFE_ASSERT_RECOVER_NOOP`），避免硬断言触发 `qFatal` 导致进程闪退 |
| 0013 | `perf-experimentbrush-painter-fillPainterPath-60fps` | `fillPainterPath` 新增 `QRegion` 分块合并光栅化，纯色填充改用 `nConseqPixels()` 批量 `applyAlphaU8Mask` 替代逐像素虚调用与 `fillRect` |
| 0014 | `perf-experimentbrush-krita_utils-25fps-splitTriangle` | 解除 `KisExperimentPaintOp` 2010 年遗留的 25fps (`timeThreshold = 40`) 与长笔画距离门控硬节流，重构为 12ms / ~80fps 连续刷新，并为 `splitTriangles` 引入 AABB 预筛选消除昂贵多边形求交 |
| 0015 | `perf-sketch-2D-O-N-2-O-1` | 为 `KisSketchPaintOp` 引入 2D 空间网格桶与局部候选点排序检索，消除长笔画时逐事件遍历整笔历史点的 $O(N^2)$ 计算死锁 |
| 0016 | `perf-marker` | 优化 `KisMarkerPainter` 圆头笔混合：引入透明度缓存跳过 90%+ 内部实心像素的虚函数重设，并简化半平面叉乘与距离判定 |
| 0017 | `fix-gridbrush-HSV-KoColorTransformation` | 修复网格笔刷在启用 HSV 随机调整时每次粒子采样均泄露 `KoColorTransformation` 实例的内存泄露缺陷 |
| 0018 | `fix-paintop-resources` | 修复 `KisHairyPaintOp` 空笔尖/空设备崩溃、`KisTextureOption::apply` 用 `pixelSize()` 越界读取 `channels` 崩溃，以及 `LocalResourcesSource` 悬垂引用 `QList` 导致的 `SIGBUS` |
| 0019 | `fix-resources-LocalResourcesSource-1x1` | `LocalResourcesSource` 改用共享读写锁快照 (`SharedResources`)，修复按需加载 `.gih` 笔尖、内嵌与导入资源对 `bestMatch()` 不可见进而退化为 1x1 笔刷的问题 |
| 0020 | `feat-colorspaceextensions-Android-hsv_adjustment` | `krita_colorspaces_extensions` 拆分出静态库并新增 `krita_register_colorspaces_extensions()` 静态注册入口，使 Android 端可直接注册 `hsv_adjustment` 等色彩空间变换工厂 |

## 应用与重建

```bash
git clone https://invent.kde.org/graphics/krita.git
cd krita
git checkout 876c4cc0          # 或直接用 fork 的 reverie-android 分支
git am /path/to/ReveriePaint-native/patches/krita/*.patch
```

本机重建原生库（预编译模式不需要）：

```bash
./scripts/build_native.sh     # 内部走 gradle -PbuildNative + CMake
```

## 配套文件

- `HOOKS.md`：按 A/B/C 三类列全每个被改动的上游文件、改了什么、冲突时保谁
- `hooks.txt`：机器可读的「补丁触碰文件」清单，`scripts/check_krita_sync.sh` 用它判断冲突是否越界

## 注意

- 补丁基线必须与 `third_party/android-native-libs/` 里的预编译库同源，换基线后要重新编译并替换动态库
- 0001 里对 Android 端 LibAV 的禁用是刻意的：MP4 导出由应用侧 Kotlin `MediaCodec` 实现，不依赖 Krita 的 FFmpeg 通道
- 0009 的空实现桩让 Krita 侧视频导出在 Android 上表现为「无可用格式」，但 `libkritaui.so` 可以正常链接；
  上游视频导出重构后若再新增对编码器类的引用，需要同步补桩
- 上游每次同步后应重新导出本目录，并把基线 commit 写进本文件
