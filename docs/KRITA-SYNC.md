# Krita 引擎 fork 与上游同步手册

> 记录 ReveriePaint 所依赖的 Krita 引擎 fork 的位置、补丁构成、同步流程与踩过的坑。
> 补丁本体在 `patches/krita/`，本文件是可重复执行的同步清单。

## 1. 仓库与分支

| 项 | 值 |
|---|---|
| 本地源码 | `/home/lanrhyme/Projects/krita-source` |
| 上游 | `origin` = <https://invent.kde.org/graphics/krita.git>（分支 `master`） |
| fork | `github` = <https://github.com/LanRhyme/krita.git>（HEAD 指向 `reverie-android`） |
| 长期分支 | **`reverie-android`**（唯一），历史上还有 `feat/clipping-mask`，2026-10-27 已统一并删除 |
| 当前基线 | Krita `master` @ `72d3b1e3`（2026-10-27，6.1.0-prealpha） |
| 安全锚点 | tag `pre-sync-20261027`（同步前状态）、fork 上的同名 tag |

分支上是 **11 个线性提交**（不再保留 merge 提交），与 `patches/krita/0001-0011` 一一对应：

1. `feat(android)` 14 个笔刷引擎静态注册 + Android 构建兼容
2. `fix(roundmarker)` KisMarkerPainter 改标准覆盖混合
3. `fix(swap)` 多步撤销 Swap 溢出 qFatal 修复
4. `fix(roundmarker)` 边缘像素改 COMPOSITE_OVER，消除白边
5. `feat(clipping)` 剪切蒙版引擎支持
6. `feat(mypaint)` 静态注册与 Android 构建支持
7. `fix(mypaint)` 传感器懒初始化，修复 dlopen 崩溃
8. `fix(build)` `kis_lockless_stack.h` 补 `#include <QtGlobal>`，修 Qt 6.6.3 下 `Q_DISABLE_COPY` 未定义
9. `fix(android)` 视频编码器空实现桩（Android 无 MLT/FFmpeg），修 `libkritaui.so` 链接失败
10. `docs(REVERIE-PATCHES.md)` 补丁层说明（上一轮补的文档提交）
11. `fix(memento)` 撤销/重做按 memento 身份校验：`rollforward()`/`rollback()` 身份不符时保持现状，
    `purgeHistory()` 摘掉 `m_cancelledRevisions` 里的悬垂条目。修「本地事务 revert 后重做」的
    瓦片装错帧（笔仙）与 `delete[]` 非法 free（SIGABRT），见 `docs/INCIDENT-1.6.4-HISTORY-REDO-CRASH.md`

## 2. 同步流程

**第 0 步（必做）**：跑体检脚本，它会做 3-way 合并预演 + 补丁契约检查：

```bash
./scripts/check_krita_sync.sh          # 默认 ~/Projects/krita-source + origin/master
./scripts/check_krita_sync.sh --no-fetch --fork reverie-android~3   # 负向自测：应报 LOST
```

通过后再动手，冲突只会落在 `patches/krita/HOOKS.md` 记录的文件上；不通过就按 HOOKS 的策略处理。
改完补丁后同样再跑一次，确认契约符号还在（剪切蒙版入口、静态注册、QtGlobal 修补、
视频编码桩、LibAV 禁用段等 30 项断言）。

```bash
cd ~/Projects/krita-source
git -c tag.gpgSign=false tag -f pre-sync-$(date +%Y%m%d) HEAD   # 安全锚点
git fetch origin master                                          # 只拉新提交，浅克隆不会 unshallow
git checkout -B reverie-android origin/master
# 依次重放旧分支的 7 个提交（新 SHA 见 fork 或 patches/krita）
for c in <旧提交系列>; do git cherry-pick -n "$c" || { 解冲突; git add -u; }; git commit -F <统一提交说明>; done

git diff --stat reverie-android <同步前的合并/旧 tip>   # 验收：应只差已知的无关差异
cmake --build build-android -j$(nproc)                  # 原生编译验证
git format-patch -o <ReveriePaint-native>/patches/krita origin/master..reverie-android
# 更新 patches/krita/README.md 里的基线 commit，提交 app 仓库
git push --force-with-lease=reverie-android:<旧远端 SHA> github reverie-android
```

判断上游走了多远：

```bash
git rev-list --count origin/master..reverie-android   # 本地领先
git rev-list --count reverie-android..origin/master   # 落后多少提交
git diff --name-only origin/master..reverie-android | sort > /tmp/mine.txt
git diff --name-only <旧基线> origin/master | sort > /tmp/up.txt
comm -12 /tmp/mine.txt /tmp/up.txt                    # 双方都改过的文件 = 冲突候选
```

## 3. 已知冲突点（每次同步必查）

| 文件 | 情况 | 解法 |
|---|---|---|
| `libs/ui/CMakeLists.txt` | 上游的 `if(ANDROID)` 源列表会新增 `KisAndroidMediaEncoderRunnable.cpp`、`KisLibavMediaEncoderRunnable.cpp` | **保持排除**。Android 端 MP4 由应用侧 Kotlin `MediaCodec` 实现（`core/export/Mp4VideoEncoder.kt`），Krita 侧的 LibAV 通道在 0001 里已禁用；只保留 `KisMediaEncoderFormatPreferencesDialog.cpp` |
| `plugins/impex/psd/psd_loader.cpp` | 上游新增 `layer->setUserLocked(...)`，本地新增 `enableClippingLayer(...)`，同一位置 | **两条都保留**（先后顺序无关） |
| `cmake/modules/FindLibMyPaint.cmake` | 上游在移除 `find_package_handle_standard_args` 的 `FOUND_VAR`（新版 ECM 弃用） | 自动合并；本地只加 ANDROID 的 `json-c` 分支，不冲突 |
| `CMakeLists.txt` | 上游改了 LibAV 相关探测 | 自动合并；本地在 ANDROID 下把 LibAV 置为 `FOUND FALSE`、降级 `OPTIONAL` |
| `libs/ui/animation/*` | 上游把视频导出重构成 `KisMediaEncoderWrapper` + 编码器 runnable | Android 只编译 wrapper，另外提供一个空实现桩（见 0009），真实编码器实现不参与 Android 构建 |

## 4. 构建验证

**不要直接 `cmake --build build-android`**：默认目标包含 `plugins/assistants` 等从未参与 Android 出货、
且依赖更新 Qt 才编得过的目标。按出货清单建（`third_party/android-native-libs/libkrita*.so` 对应的 38 个目标）：

```bash
cd ~/Projects/krita-source
ninja -C build-android -j$(nproc) \
    lib/libkritaui.so lib/libkritaimage.so lib/libkritapigment.so lib/libkritaflake.so \
    lib/libkritawidgetutils.so lib/libkritawidgets.so lib/libkritastore.so lib/libkritaimpex.so \
    lib/libkritaresources.so lib/libkritaresourcewidgets.so lib/libkritacommand.so \
    lib/libkritaglobal.so lib/libkritaversion.so lib/libkritaplugin.so lib/libkritamultiarch.so \
    lib/libkritasurfacecolormanagementapi.so lib/libkritametadata.so lib/libkritalibbrush.so \
    lib/libkritalibkra.so lib/libkritalibpaintop.so lib/libkritapsd.so lib/libkritapsdutils.so \
    lib/libkritabasicflakes.so lib/kritalcmsengine.so \
    lib/kritacolorsmudgepaintop.so lib/kritacurvepaintop.so lib/kritadeformpaintop.so \
    lib/kritaexperimentpaintop.so lib/kritafilterop.so lib/kritagridpaintop.so \
    lib/kritahairypaintop.so lib/kritahatchingpaintop.so lib/kritamypaintop.so \
    lib/kritaparticlepaintop.so lib/kritaroundmarkerpaintop.so lib/kritasketchpaintop.so \
    lib/kritaspraypaintop.so lib/kritatangentnormalpaintop.so
```

2026-10-27 这次同步就是按上述目标集验证通过的（228 个编译步骤，0 错误）。

如果之后还要验证 app 侧 JNI 是否跟着编得过，再补一步（用 gradle 既有的 CMake 配置目录）：

```bash
cd ~/Projects/ReveriePaint-native
D=$(ls -td app/.cxx/Release/*/arm64-v8a | head -1)
ninja -C "$D" -j$(nproc) reverie_jni
```

注意 `lib/libkritadefaultpaintops_static.a` 也被 app 直接链接：它的 mtime 若比源码新（比如从提交里
捞回来的构建目录），ninja 不会重建，链 JNI 时会报 `undefined symbol:
KisPaintOpSettingsWidget::setImage(...)` 这类已删 API 的错。先 `ninja -C build-android
lib/libkritadefaultpaintops_static.a` 刷新它再链。

构建目录 `build-android/` 的关键配置（`CMakeCache.txt`）：

- 工具链 `~/ReveriePaint-android/toolchain/qt-chainload.cmake`
- `Qt6_DIR=~/Qt6/6.6.3/android_arm64_v8a/lib/cmake/Qt6`
- `ANDROID_ABI=arm64-v8a`、`ANDROID_NDK_ROOT=~/Android/Sdk/ndk/25.2.9519653`
- 依赖前缀 `~/ReveriePaint-android/deps`

### 刷新预编译库（发版/换引擎必做）

产物要按**旧文件名**落盘，应用侧的加载路径才不用改。三个注意点：

1. 上游现在把插件产物命名为 `krita*.so`（无 `lib` 前缀），而 APK 里历史上是
   `libkrita*.so`（有前缀）——复制时按旧名重命名：
   `build-android/lib/kritaroundmarkerpaintop.so` → `third_party/.../libkritaroundmarkerpaintop.so`
2. `build-android/lib/` 里可能同时存在**陈旧**的带前缀副本（构建目录是从旧状态恢复时会这样），
   一律以无前缀的新产物为准，别被 `-f` 判断骗到
3. 逐个 `llvm-strip --strip-unneeded`（未 strip 的中间库约 888MB，strip 后 ~50MB），
   再 `scripts/copy_jni_libs.sh` + `./gradlew assembleDebug`

`libreverie_jni.so` 也必须换成用新引擎重编的版本（`app/build/intermediates/cxx/Release/*/obj/arm64-v8a/`），
否则会在运行期和新的 `libkritaui` 行为不一致。

**soname 版本号坑**：新构建出来的 `libkritastore.so` / `libkritaresources.so` 会按 deps 里的
`libbz2.so.1.0` 记录 NEEDED，而 APK 里只有 `libbz2.so`，真机启动即
`UnsatisfiedLinkError: dlopen failed: library "libbz2.so.1.0" not found`。复制后用
`patchelf --replace-needed libbz2.so.1.0 libbz2.so` 改回无版本名，再跑一次依赖闭包检查
（`readelf -d` 扫全部 NEEDED，注意别只匹配 `.so` 结尾，版本号 soname 会漏掉）。

### 刷新 32 位 (armeabi-v7a) 闭包（每次同步后必须与 arm64 同步做）

32 位这一套**不能只刷 `libreverie_jni.so`**：`libreverie_core.so` 与 Krita/Qt 闭包必须同源，
否则 Kotlin 与原生库会错配。2026-10-31 的 v1.6.1 事故就是这么来的 —— 32 位闭包停在 10-18，
Kotlin 侧 10-28 新增的 `nativeSnapshotMode` 在旧库里不存在，32 位用户一开工程就
`UnsatisfiedLinkError` 崩溃（arm64 包正常，所以测试时完全看不出来）。

一条命令搞定（重编 C++ → 重收闭包 → 跑符号门禁）：

```bash
./scripts/build_native.sh --abi armeabi-v7a
```

它内部做的事，手工排查时按顺序看：

1. `cmake --build ~/Projects/krita-source/build-android-armv7 --target kritadefaultpaintops_static`
   —— 这个**静态库被直接链进 `libreverie_core.so`**，它若比源码旧，链接期会报
   `undefined symbol: KisPaintOpSettingsWidget::setImage` 之类看起来毫不相干的错误
   （2026-10-31 实际踩到：档案停留在 Krita 同步之前的 10-27，moc 产物还引用着已删除的符号）
2. CMake 独立配置（不走 gradle 的 `buildNative`，那条通路只补 arm64 的闭包）：
   `-DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-23 -DANDROID_STL=c++_shared`，
   Krita/Qt/deps 路径由 `app/src/main/cpp/CMakeLists.txt` 按 ABI 自动选择
3. `scripts/collect_armv7_libs.sh` 重收闭包：Krita 库 + KF6/deps + Qt armv7 + `libc++_shared`
   + 两个 ReverieCore 产物，**全部 `llvm-strip --strip-unneeded` 后写入**
   `third_party/android-native-libs-armeabi-v7a/`（不 strip 的话体积翻十倍）
4. `scripts/check_jni_symbols.sh armeabi-v7a` 对账符号，零缺失才算完

发版前的双 ABI 校验：

```bash
./scripts/check_jni_symbols.sh                     # arm64 + armv7 都要绿
./gradlew :app:assembleDebug -PtargetAbis=armeabi-v7a   # 抽包检查 lib/armeabi-v7a/ 里的两个 .so
```

`check_jni_symbols.sh` 已经挂进 `development.yml` 与 `release.yml`（copy 之后、build 之前），
所以第二个 ABI 的库再落后，CI 会直接失败而不是把崩溃包发出去。

**已知差异**：上游删除了导出 `KisColorManager` 的 `libkritacolor.so` 插件（新树里没有对应目标），
当前仍保留旧文件（34KB，新代码不引用它），等真机验证后再决定是否从 `third_party` 和
`app/src/main/cpp/CMakeLists.txt` 里摘掉。

## 5. 踩过的坑

1. **`tag.gpgSign=true`**：这个仓库开了标签签名，脚本里 `git tag -f` 会要求提交说明而失败。
   用 `git -c tag.gpgSign=false tag ...`，或者写 `git tag -a -m "..."`。
2. **`git add -A` 会把 `build-android*/` 吃进提交**（该仓库没忽略这些构建目录），随后
   `git reset --hard` 会连带删除这些构建产物，构建目录直接报废。
   - 提交前只用 `git add -u` 或显式列文件
   - 万一删了：从悬空提交里捞回来
     `git archive <含构建产物的悬空提交> build-android build-android-armv7 build-desktop configure_armv7.sh | tar -x -C .`
3. **浅克隆**：`.git/shallow` 边界在基线提交上，`git fetch origin master` 只补新提交不会 unshallow；
   但 `git merge-base` 到更早的提交（如 `krita/6.0` 分叉点）会查不到。
4. **上游重建历史**：KDE 偶尔 rebase `master`，此时只能用 `format-patch` + `cherry-pick` 重放，
   不要指望 fast-forward 或 merge。
5. **Qt 6.6.3 的隐式包含比上游 CI 少**：`libs/global/kis_lockless_stack.h` 只包含
   `<QAtomicPointer>` 却用 `Q_DISABLE_COPY`（Qt 6 里该宏在 `qtclasshelpermacros.h`，
   由 `qglobal.h` 提供，而 qatomic 链不传递包含），因此凡是把它作为第一个 Qt 头文件的
   TU（如 `CmykU16ColorSpace.cpp`）会在我们这套工具链上编译失败，已用 0008 补上
   `#include <QtGlobal>`。同步后出现「unknown type name 'Q_DISABLE_COPY' / 'QAtomicInt'」
   一类错误时先查这个。
6. **恢复构建目录后 moc/生成文件会陈旧**：从提交里捞回 `build-*` 后其 mtime 比源码新，
   ninja 会跳过 moc 重生成，报「no member named X in ...」的假错误。用
   `git ls-files -z | xargs -0 touch` 刷新源码时间戳再编（代价是近似全量重编）。

## 6. 与核心拆分的关系

`docs/plans/CORE-SPLIT.md` 里把应用侧核心拆成独立 `libreverie_core.so`（状态所有权收敛）。
该工作**不需要** fork 再改 Krita，只换应用侧边界；唯一交叉点是 P2（引擎侧 `reverie_*`
影子属性并进结构体），届时同样用 `scripts/check_krita_sync.sh` 验证契约不受影响。

## 7. 版本线

- 2026-07-26 之前：基线 `a4d196ef`（6.1.0-prealpha）
- 2026-10-27：同步到 `72d3b1e3`（6.1.0-prealpha，+529 提交），合并 13 个双方都改过的文件、
  2 处手工解冲突，历史整理为线性提交并删除 `feat/clipping-mask`
- 2026-11-02：新增第 10 个功能补丁 `fix(memento)`（撤销/重做历史错位加固），
  双 ABI 重编 `libkritaimage.so` 并刷新预编译闭包（导出符号与旧库完全一致，只 +1.8KB 代码）
- 2026-10-27 晚：补丁层收敛 —— fork 内新增 `REVERIE-PATCHES.md`（10 个提交，含该文档本身），
  应用仓库新增 `patches/krita/HOOKS.md`（按 A/B/C 分类的逐文件冲突策略）、
  `patches/krita/hooks.txt`（机器清单）与 `scripts/check_krita_sync.sh`（合并预演 + 30 项契约断言）；
  预编译库同步到新引擎并在平板 OPD2413 上验证启动渲染正常
