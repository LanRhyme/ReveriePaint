# ReveriePaint 推进文档

## 当前状态

原生安卓版 (Kotlin/Compose + JNI + Krita C++ 核心) MVP 已构建成功

## 已完成

- [x] 项目迁移: QML/QtWidgets UI 替换为 Jetpack Compose
- [x] 三页导航: 主页 / 创建画布 / 绘画
- [x] 主页: 最近项目列表 + 缩略图 + 新建/打开入口
- [x] 创建页: 6 尺寸预设 + 自定义宽高 + 背景色
- [x] 绘画页: 画世界 Pro 风格布局
  - 左侧工具栏 (13 工具 + 竖向大小/不透明度滑杆 + 取色)
  - 顶部操作栏 (返回/旋转/缩放/图层/设置)
  - 网格工作区 + 居中画布
- [x] 触摸手势: 单指绘画 (压感) / 双指缩放旋转平移
- [x] 工具: 画笔 / 手 / 橡皮 / 取色 / 填充 / 直线 / 矩形 / 椭圆
- [x] 图层: 新建 / 删除 / 切换 / 显隐 (Krita KisPaintLayer)
- [x] 笔刷面板: 预设 + 大小 + 不透明度
- [x] 取色面板: 预设色板
- [x] 设置面板: 画布信息 + 保存项目
- [x] 项目保存/加载 (PNG 导出/导入)
- [x] C++ 引擎: KisImage 直接构造 (绕过 KisDocument/KisPart)
- [x] JNI 桥: 文档/图层/笔刷/触摸/渲染/取色/形状/文件

## 待办

- [ ] 套索 / 魔棒 / 文字 / 涂抹 / 液化 工具
- [ ] 形状工具实时预览 (拖动时显示形状)
- [ ] HSV 取色器 (色轮/滑条)
- [ ] 撤销 / 重做 (KisUndoStack)
- [ ] 笔刷软硬度 (Krita brush engine 集成)
- [ ] 图层混合模式 (Krita composite ops)
- [ ] 画布旋转/缩放指示器浮层
- [ ] 网格开关
- [ ] 多文档支持
- [ ] APK 体积优化 (去符号/排除多余库)
- [ ] 版本控制 (git 已就绪)

## 架构决策

- UI 用 Compose: Qt QQuickWidget 在 Android 上透明 overlay 不可靠, 反复失败
- C++ 引擎绕过 KisDocument/KisPart: 它们是 kritaui 的一部分, 需要完整 QApplication;
  KisImage 公开构造即可满足单文档绘画引擎需求, 依赖面大幅缩小
- Qt 库仅作为引擎运行时: 原生 Activity 里创建 QCoreApplication (无界面)
- 画布显示: C++ 合成 → Android Bitmap 零拷贝 (lockPixels) → Compose Image
- Bitmap 原地修改: mutableStateOf 用 neverEqualPolicy 强制触发重组

## 构建

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 环境

- AGP 9.3.1 + Gradle 9.5 (离线缓存)
- NDK 25.2.9519653, compileSdk 36, minSdk 23
- Qt for Android 6.6.3 (引擎运行时), Krita 6.1.0-prealpha arm64 交叉编译库
