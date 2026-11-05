# Krita fork 补丁层清单（HOOKS）

这份清单回答一个问题：**同步上游时，哪些文件可能冲突、冲突时该保谁**。

- fork 分支：`reverie-android`（基线见 `README.md`）
- 机器可读列表：`hooks.txt`（`check_krita_sync.sh` 用它做门禁）
- 当前规模：**89 个文件**（新增 16 / 修改 73，共 +1502 / −367 行）

## 0. 三类改动，从轻到重

| 类别 | 文件数 | 同步风险 | 处理原则 |
|---|---|---|---|
| **A. 纯新增文件** | 16 | 零（上游不存在同名文件） | 不可能冲突；只有上游真的加了同名文件才需改名 |
| **B. 构建 / 平台适配** | ~49 | 中（上游重构构建或 Android 代码时会撞） | 只改必须改的行；冲突时**保留本地那份** |
| **C. 引擎语义** | 14 + 6 | 高（撞了必须读懂两边） | 见下面的逐文件策略，剪切蒙版按现状保留 |

## 1. A 类：新增文件（16 个）

| 文件 | 用途 |
|---|---|
`plugins/paintops/{colorsmudge,curvebrush,defaultpaintops,deform,experiment,filterop,gridbrush,hairy,hatching,mypaint,particle,roundmarker,sketch,spray,tangentnormal}/register_static.cpp` | 15 个笔刷引擎的静态注册入口（Android 不能 dlopen 插件）
`libs/ui/animation/KisMediaEncoderRunnables_stub.cpp` | 视频编码器空实现桩（Android 无 MLT / FFmpeg）

同步时只要确认这 16 个文件还在、且对应的 `CMakeLists.txt` 仍然引用它们即可。

## 2. B 类：构建 / 平台适配（改了上游已有文件）

| 文件 | 改了什么 | 冲突策略 |
|---|---|---|
`CMakeLists.txt` | `if(ANDROID)` 下禁用 LibAV（`set(LibAV_FOUND FALSE)`、探针降级 `OPTIONAL`） | 保留本地的禁用；上游若新增 FFmpeg 探测，同样置为禁用 |
`cmake/modules/FindLibMyPaint.cmake` | ANDROID 下 `MYPAINT_PKGCONF_LIBRARIES=json-c`、清空 CFLAGS | 保留本地分支即可，注意上游对 `find_package_handle_standard_args` 的改法 |
`plugins/paintops/*/CMakeLists.txt`（15 个） | 源列表加 `register_static.cpp` | 一行改动；上游重排源列表时手工补回 |
`plugins/paintops/mypaint/CMakeLists.txt` | 同上 | 同上 |
`libs/global/CMakeLists.txt` | 去掉一处 moc/源（Android 不需要） | 保留本地 |
`libs/ui/CMakeLists.txt` | `if(ANDROID)` 源列表：保留 `KisMediaEncoderFormatPreferencesDialog.cpp`、`KisMediaEncoderWrapper.cpp`、`KisMediaEncoderRunnables_stub.cpp`，**排除** `KisAndroidMediaEncoderRunnable.cpp` / `KisLibavMediaEncoderRunnable.cpp`；`Qt AndroidExtras` 改 `Qt6::Core Qt6::Gui` | 上游每次视频导出重构都会撞这里，见 §4 |
`libs/widgetutils/CMakeLists.txt` | Android 构建微调 | 保留本地 |
`libs/global/KisAndroidUtils.cpp`、`KisAndroidExitInfo.cpp`、`KisUsageLogger.cpp` | 日志/退出信息改走 stdout 与 JNI | 保留本地实现；上游若加了 `Q_OS_ANDROID` 分支，优先合并两边的行为 |
`libs/flake/KoShapeBulkActionLock.h`、`text/KoCssTextUtils.cpp`、`text/KoSvgTextShapeMarkupConverter.cpp` | Android 文本布局与 Qt6 编译修补 | 保留本地；上游重写文本布局时要重新验证 |
`libs/pigment/KoColorSpaceRegistry.cpp`、`surfacecolormanagementapi/.../KisSurfaceColorimetry.cpp` | Android 颜色空间注册/头文件修补 | 保留本地 |
`libs/ui/KisAndroidDonations.cpp`、`KisAndroidScaling.cpp`、`KisLongPressEventFilter.cpp`、`KisMainWindow.cpp`、`KisReferenceImage.cpp`、`KisSurfaceColorSpaceWrapper.h`、`canvas/kis_display_color_converter.cpp`、`kis_painting_assistant.cc`、`opengl/kis_opengl_image_textures.cpp`、`animation/KisAndroidMediaEncoderRunnable.cpp` | Android/GUI 适配（无 GUI、GLES3、参考图缓存、显存纹理） | 逐条看，本地改动通常是「Android 上没有 X 就跳过」，合并不掉就保留本地 |
`libs/widgetutils/KisKineticScroller.cpp`、`xmlgui/kkeysequencewidget.cpp` | 触屏滚动与按键序列控件适配 | 保留本地 |
`libs/image/kis_base_accessor.h` | 给 Android 侧开放访问器 | 保留本地（上游若把它改成模板参数，需重新适配） |
`libs/global/kis_lockless_stack.h` | 补 `#include <QtGlobal>`（Qt 6.6.3 的 qatomic 链不传递 qglobal） | 保留本地；上游若自己补了同样的 include，本地这份可以删 |

## 3. C 类：引擎语义（14 + 7 个文件）

### 3.1 剪切蒙版（**按现状保留，不再重构**）

| 文件 | 改了什么 |
|---|---|
`libs/image/kis_layer.h` / `.cc` | `enableClippingLayer` / 剪切属性存取 |
`libs/image/kis_node.h` / `.cc` | 剪切链表检索、基底定位、属性命令 |
`libs/image/kis_layer_projection_plane.cpp` | 剪切层递归合成（唯一必须在核心文件里改的地方） |
`libs/image/kis_layer_properties_icons.cpp` / `.h` | 图层面板剪切图标 |
`libs/image/commands/kis_node_property_list_command.cpp` | 剪切属性进命令（撤销） |
`libs/ui/tool/strokes/freehand_stroke.cpp` | 脏矩形同步传播到基底 |
`plugins/impex/libkra/kis_kra_loader.cpp` / `kis_kra_savexml_visitor.cpp` / `kis_kra_tags.h` | KRA 读写 `clipping` 属性 |
`plugins/impex/psd/psd_loader.cpp`、`libs/psd/psd_layer_section.cpp` | PSD 读回/落盘（PSD 里上游也有 `userLocked`，两行都保留） |

冲突策略：**两边都要**。上游若重构 `KisLayer` / `KisProjectionLeaf` 的虚函数，先保证剪切递归仍被调用。

### 3.2 笔刷与存储修复

| 文件 | 改了什么 | 冲突策略 |
|---|---|---|
`libs/image/kis_marker_painter.cpp` | ① 标准覆盖混合 ② 边缘像素 `COMPOSITE_OVER` 替代裸 memcpy | 保留本地（上游若重写 marker painter，要把两处语义搬进去） |
`libs/image/kis_image_config.cpp` | 抬高 Swap 软限制阈值 | 合并取较大值 |
`libs/image/tiles3/swap/kis_chunk_allocator.{cpp,h}`、`kis_swapped_data_store.cpp` | 槽位耗尽不再 qFatal | 保留本地 |
`plugins/paintops/mypaint/MyPaintSensorPack.{cpp,h}` | 传感器连接懒初始化（dlopen 阶段崩溃） | 保留本地 |
`cmake/modules/FindLibMyPaint.cmake` | 见 B 类 |  |

### 3.3 撤销历史一致性（**按现状保留**）

| 文件 | 改了什么 | 冲突策略 |
|---|---|---|
`libs/image/tiles3/kis_memento_manager.cc` | `rollforward()` / `rollback()` 增加 memento 身份校验（`reverieFindCancelledRevision()`）：身份不符时保持现状而不是盲目 `takeFirst()/takeLast()`；`purgeHistory()` 顺手摘掉 `m_cancelledRevisions` 里指向该 memento 的条目 | 保留本地。少了这段，「本地事务 revert 后重做」会重新变成瓦片装错帧（笔仙）+ 对已释放 `KisMemento` 做 `delete[]` 的 SIGABRT；详见应用仓库 `docs/INCIDENT-1.6.4-HISTORY-REDO-CRASH.md` |

## 4. 上游的「热点」：每次同步必查

这几个文件上游改动频繁，是本仓库冲突的主要来源：

1. `libs/ui/CMakeLists.txt` — 视频导出重构（2026-10 那次加了 LibAV 编码器就撞了一次）
2. `CMakeLists.txt` — LibAV / FFmpeg 探测逻辑
3. `libs/image/kis_layer*.{h,cc}`、`kis_node.{h,cc}` — 图层/投影架构演进（剪切蒙版全挂在这上面）
4. `libs/ui/animation/*` — 视频导出实现（是否需要补桩）
5. `libs/psd/*`、`plugins/impex/libkra/*` — 序列化格式演进

## 5. 重新生成 hooks.txt

```bash
cd ~/Projects/krita-source
git diff --name-only origin/master..reverie-android | sort > /path/to/patches/krita/hooks.txt
```

本清单（HOOKS.md）需要人工同步维护：新增补丁时补上「改了什么 / 冲突策略」两列。
