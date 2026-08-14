# Gemini 修复任务简报

## 项目概况

ReveriePaint 是安卓原生绘画应用(Kotlin/Jetpack Compose UI + C++ 核心),C++ 核心直接使用 Krita(6.1.0-prealpha)的 arm64 交叉编译动态库

- 仓库: ~/Projects/ReveriePaint-native (main 分支, 当前 HEAD 61ad825)
- UI: app/src/main/java/com/reverie/paint/ (Compose)
- C++: app/src/main/cpp/ (ReverieCore.cpp/h + reverie_jni.cpp)
- JNI 桥: app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt (Kotlin external fun) 与 reverie_jni.cpp (Java_com_reverie_paint_core_ReverieCoreBridge_*)
- 构建: ./gradlew assembleDebug (默认 prebuilt 模式, 不编译 C++)
  - 开发者模式(修改 C++ 后必须): ./scripts/build_native.sh (两遍构建, 重编译 reverie_jni.so)
  - 仅改 Kotlin: ./gradlew assembleDebug 即可
- 测试设备: Redmi K70 (adb serial ed3fdd92)
- 安装: adb install -r app/build/outputs/apk/debug/app-debug.apk

## 用户报告的三个问题(Redmi K70)

1. 变换工具(TRANSFORM)一用就崩溃
2. 裁剪工具(CROP)一用就崩溃
3. 液化工具(LIQUIFY)明显有问题

## 已完成的修复(仍要复核)

### 问题1+2: 变换/裁剪崩溃(UnsatisfiedLinkError)

根因: prebuilt 模式打包了旧版 libreverie_jni.so, 不含新增 JNI 符号

已做:

- reverie_jni.cpp 修复 3 处结构损坏(contentBounds 插入破坏: 孤立 JNIEXPORT 片段、cropCanvas/drawShape 丢 extern "C" 前缀、setShapeStrokeWidth 重复前缀)
- 重新编译 libreverie_jni.so (112 个 JNI 导出, 含 contentBounds/setLiquifyBrushSize 等), 更新 third_party/android-native-libs 和 jniLibs
- 提交 61ad825

待复核:

- 新 APK 已安装(PID 1259), 但用户反馈时可能测的是旧版, 需要用户重新测试确认
- 变换工具崩溃历史原因: PaintViewModel.contentBounds() 曾在 UI 线程直调 JNI 与渲染线程竞争 (7a13c15 已改 runCore 同步)
- 裁剪崩溃历史原因: cropCanvas 未同步 m_docWidth/m_docHeight + resizeImage 异步未等 waitForDone (7a13c15 已修)

### 问题3: 液化工具

已做 (7a13c15):

- C++ liquify 增加 mode 参数 (0推拉/1膨胀/2收缩/3顺时针/4逆时针) + setLiquifyBrushSize 独立笔刷大小
- LiquifyPanel 重做: 模式切换 + 笔刷大小滑块(8-300) + 强度滑块(0.05-2)
- 面板从全屏 RePanel 改为浮动胶囊 ToolFloatPanel

待复核:

- 用户反馈液化有问题但具体现象未明(可能仍是旧 APK 的符号缺失崩溃)

## 关键文件

- app/src/main/cpp/ReverieCore.cpp (C++ 核心, 4千+行)
- app/src/main/cpp/reverie_jni.cpp (JNI 入口, Java_com_reverie_paint_core_ReverieCoreBridge_*)
- app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt (Kotlin external fun 声明, 必须与 JNI 符号完全匹配)
- app/src/main/java/com/reverie/paint/core/PaintViewModel.kt (runCore 渲染线程调度 + 各工具 VM 方法)
- app/src/main/java/com/reverie/paint/ui/painting/PaintingPage.kt (工具面板挂载)
- app/src/main/java/com/reverie/paint/ui/painting/CanvasView.kt (手势/工具分发)
- app/src/main/java/com/reverie/paint/ui/painting/ToolPanels.kt (液化/渐变/填充浮动面板)
- app/src/main/java/com/reverie/paint/ui/painting/LiquifyPanel.kt (液化面板)

## JNI 约定(必须遵守)

- Kotlin external fun 与 C++ Java_com_reverie_paint_core_ReverieCoreBridge_* 必须一一对应
- Kotlin 包路径: com.reverie.paint.core
- 新增符号后必须 ./scripts/build_native.sh 重编译, 否则 prebuilt 模式会用旧库崩溃 (UnsatisfiedLinkError)
- 修改 C++ 后也要同步更新 third_party/android-native-libs/libreverie_jni.so (strip 后)

## 构建/验证流程

1. 仅改 Kotlin: ./gradlew assembleDebug --no-daemon
2. 改 C++: ./scripts/build_native.sh (两遍构建, 约3分钟)
3. 更新 prebuilt jni (改 C++ 后):
   OBJ=$(ls -d app/build/intermediates/cxx/Release/*/obj/arm64-v8a | head -1)
   cp "$OBJ/libreverie_jni.so" third_party/android-native-libs/libreverie_jni.so
   cp "$OBJ/libreverie_jni.so" app/src/main/jniLibs/arm64-v8a/libreverie_jni.so
4. adb install -r app/build/outputs/apk/debug/app-debug.apk
5. adb shell am start -W -n com.reverie.paint/.MainActivity
6. 看崩溃: adb logcat -d -s AndroidRuntime:E ReverieCore ReverieSel

## 注意事项

- 用户自己测试, 不要用 adb 截图/像素分析自行验证 UI (用户禁止)
- 主题色: 不要硬编码颜色, 全部走 ui/theme/Theme.kt 的 Morandi 色板
- C++ 修改必须保底桌面测试: ~/tmp/harness 有桌面 harness 可复现 C++ 逻辑 (需先 cp 最新 ReverieCore.cpp/h)
- 液化 C++ 核心在 ReverieCore.cpp liquify(), 模式实现为像素位移 (mode: 0 push/pull, 1 bloat, 2 pucker, 3 rotate CW, 4 rotate CCW)
- 变换核心: applyTransform() 用 Krita KisTransformWorker (无选区) / QImage 裁剪合成 (有选区)
- 裁剪核心: cropCanvas() 调 image->resizeImage + waitForDone + 同步尺寸
- 工具面板风格参考: ui/painting/SelectionFloatPanel.kt (底部浮动胶囊, 不要全屏遮罩)
