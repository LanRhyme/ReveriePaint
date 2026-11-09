# Krita 引擎补丁系列

本目录是 ReveriePaint 使用的 **Krita 引擎补丁集**，对应 fork 分支：

- 仓库：<https://github.com/LanRhyme/krita>
- 分支：`reverie-android`
- 基线：KDE Krita `master` @ `72d3b1e3`（2026-10-27，6.1.0-prealpha）
- 生成方式：`git format-patch 72d3b1e3..reverie-android`

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

## 应用与重建

```bash
git clone https://invent.kde.org/graphics/krita.git
cd krita
git checkout 72d3b1e3          # 或直接用 fork 的 reverie-android 分支
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
