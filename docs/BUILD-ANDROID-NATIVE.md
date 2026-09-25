# C++ 引擎本地重编译环境 (Linux / WSL)

> 目标读者: 需要修改 `app/src/main/cpp/` 的开发者
> 结论: **已在本机 WSL (Debian 13) 上完整打通**, 产物与仓库发布的预编译库
> 符号集、依赖闭包完全一致 (验证命令见 §6)

## 1. 为什么必须用 Linux 宿主

- [`CMakeLists.txt`](../app/src/main/cpp/CMakeLists.txt:228) 把 NDK 头文件/库路径写死为
  `toolchains/llvm/prebuilt/linux-x86_64`, 这是 Linux 宿主的布局;
- 仓库只提交预编译产物 ([`third_party/`](../third_party)), **不含** Krita/Qt/KF6 头文件,
  而 C++ 重编译必须拿到这些头文件;
- GitHub / savannah 等上游在部分网络不可达, 因此依赖统一从
  **invent.kde.org** 与 **download.kde.org** 获取。

## 2. 已验证的版本矩阵

| 组件 | 版本 | 判定依据 |
|---|---|---|
| Qt for Android | **6.6.3** (`android_arm64_v8a`) | `libQt6Core_arm64-v8a.so` 内含 `Qt 6.6.3` 字符串 |
| 宿主 Qt | 6.6.3 (`gcc_64`, 只需 qtbase) | KF6 configure 需要 moc/rcc/uic (`libexec/`) |
| Android NDK | **r25c / 25.2.9519653** | 预编译库内嵌 `clang version 14.0.7 (9352603)` 与 r25c 完全一致 |
| Krita 源码 | **master (6.1.0-prealpha)** | `KRITA_VERSION_STRING` 与 [GEMINI-BRIEF.md](GEMINI-BRIEF.md:5) 记载一致 |
| KF6 | **6.6.0** 源码 | [`CMakeLists.txt`](../app/src/main/cpp/CMakeLists.txt:105) 的 `include/KF6/*` 布局 |
| CMake / Ninja | 3.31 / 1.12 | WSL 自带 Debian 包 |

> ⚠️ 不要用 KDE 的 `transition.now-qt6` 依赖包里的 `ext_qt`: 那是 KDE 自建 Qt
> **6.8.0**, 与本仓库 .so 的 Qt 6.6.3 不一致, 头文件/ABI 会对不上。
> 但 `ext_k*` 之外的三方库包 (boost/lcms2/openexr/freetype/...) 与 Qt 版本无关,
> 可以直接取用 —— 见 §4。

## 3. 快速开始 (在 WSL 里执行)

```bash
cd /mnt/d/Projects/ReveriePaint

# 1) 首次: 拉取 Qt/NDK/Krita/KF6/第三方头文件 (数 GB, 视网络)
scripts/setup_native_env.sh --fetch

# 2) 之后每次: 组装/刷新合成头文件 (秒级)
scripts/setup_native_env.sh

# 3) 重编译引擎并同步产物
scripts/build_native_wsl.sh          # 增量
scripts/build_native_wsl.sh --clean  # 全量
```

依赖默认落在 `~/reverie-deps` (可用 `REVERIE_ENV_ROOT` 覆盖), 目录布局:

```
~/reverie-deps/
├── Qt/6.6.3/android_arm64_v8a/   # QT_ANDROID_DIR
├── QtHost/6.6.3/gcc_64/          # QT_HOST_DIR
├── android-ndk-r25c/             # NDK_DIR
├── krita-source/                 # KRITA_SRC_DIR  (Krita 6.1.0-prealpha)
├── src/*-6.6.0/                  # KF6 模块源码
├── krita-build/                  # KRITA_BIN_DIR  (合成: config/export 头 + lib 软链)
├── deps/                         # DEPS_DIR       (合成: KF6/三方头 + lib 软链)
└── native-env.sh                 # 供 build_native_wsl.sh source 的变量
```

## 4. 脚本分工

| 脚本 | 职责 |
|---|---|
| [`scripts/setup_native_env.sh`](../scripts/setup_native_env.sh) | 总入口: 断言依赖 → 组装环境 → 写 `native-env.sh` |
| [`scripts/prepare_native_deps.py`](../scripts/prepare_native_deps.py) | 合成 Krita 构建目录与 KF6 头文件前缀 (核心) |
| [`scripts/fetch_kde_dep_headers.py`](../scripts/fetch_kde_dep_headers.py) | 从 KDE 包提取三方头文件 / 仅链接期需要的 `.so` |
| [`scripts/build_native_wsl.sh`](../scripts/build_native_wsl.sh) | 直调 CMake 交叉编译 + strip + 同步产物 |

`prepare_native_deps.py` **不运行** Krita/KF6 的 CMake configure (那需要完整 deps 与
工具链), 而是合成编译期真正需要的东西:

1. 解析 `generate_export_header()` / `ecm_generate_export_header()` 调用, 合成
   `*_export.h` (宏定义, 消费端默认可见性), 并兜底补齐源码里被 include 但没解析到的;
2. 用迷你 configure 处理 `*.h.cmake` 模板 (`KoConfig.h`、`config-*.h`、
   `kritaversion.h` …), 取值按 release 构建 (断言隐藏、锁自由哈希表开启);
3. KF6 6.6.0 源码头文件按 `include/KF6/<Module>` 布局组织;
4. 用软链把仓库内预编译 `.so/.a` 接到 `KRITA_BIN_DIR/lib` 与 `DEPS_DIR/lib`。

## 5. 三个已知坑 (脚本已处理, 改动时注意)

1. **`-lexpat` 与运行时依赖**: [`CMakeLists.txt`](../app/src/main/cpp/CMakeLists.txt:259)
   链接 `-lexpat`, 但引擎不引用任何 expat 符号, 仓库发布的预编译库 NEEDED 里也没有
   `libexpat.so`。若用动态 expat, 产物会多出一条 APK 里不存在的运行时依赖 → 真机
   `dlopen` 失败。脚本因此放置一个**空 `libexpat.a` 占位** (静态归档不进 NEEDED)。
2. **版本化 include 子目录**: KDE 包把 boost 装到 `include/boost-1_90/boost/`,
   Imath/OpenEXR 也在独立子目录, 而本仓库的 CMakeLists 只把 `${DEPS_DIR}/include`
   加进搜索路径 → 需要把这类目录内容扁平化软链到 `include/` 一级。
3. **导出头文件名大小写**: ECM 会把 `BASE_NAME` **小写**后作为文件名
   (`KI18n` → `ki18n_export.h`), 而 Linux 文件系统区分大小写, 拼错就会链接到
   一份陈旧/不存在的头文件。

## 6. 验证方式

构建脚本会输出来源 `third_party/android-native-libs/libreverie_jni.so`。与 git 中的
上一版对比 (等价性验证):

```bash
NM=~/reverie-deps/android-ndk-r25c/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm
RE=~/reverie-deps/android-ndk-r25c/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
git show HEAD:third_party/android-native-libs/libreverie_jni.so > /tmp/prev.so

diff <($NM -D --defined-only /tmp/prev.so | awk '{print $3}' | sort) \
     <($NM -D --defined-only third_party/android-native-libs/libreverie_jni.so | awk '{print $3}' | sort)
diff <($RE -d /tmp/prev.so | grep NEEDED | awk '{print $NF}') \
     <($RE -d third_party/android-native-libs/libreverie_jni.so | grep NEEDED | awk '{print $NF}')
```

**2026-09-25 实测结果**: JNI 导出符号 248 个完全一致; NEEDED 闭包 53 项完全一致;
体积 3,772,744 B vs 原库 3,772,376 B (差异仅来自构建路径字符串)。
即: 该环境可以**等价复现**原构建。

## 7. 产物与注意事项

- 产物默认 `strip` (`llvm-strip --strip-unneeded`) 后同步到
  `third_party/android-native-libs/` 与 `app/src/main/jniLibs/arm64-v8a/`;
  同步前未 strip 约 40 MB, strip 后约 3.7 MB。
- 仅支持 `arm64-v8a` (与仓库 [`.github/workflows`](../.github/workflows) 的 abiFilters 一致)。
- 真机回归仍需手动执行 (AGENTS §8): 本环境只证明**可编译且 ABI 等价**, 不代表行为
  正确 —— 改了 C++ 后请按 [`RENDER-OPTIMIZATION.md`](../RENDER-OPTIMIZATION.md) 的回归
  清单在设备上验证笔画/图层/撤销/滤镜路径。
