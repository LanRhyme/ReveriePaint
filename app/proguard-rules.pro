# SPDX-License-Identifier: GPL-3.0-or-later
#
# ReveriePaint R8 规则
#
# 原则: 只保留"运行时靠名字查找"的边界, 其余全部交给 R8 优化。
# 每条规则都对应一处真实的 JNI 符号 / 反射点, 注释写清来源, 便于后续审查。

# ---------------------------------------------------------------- JNI 引擎桥接
# reverie_jni_*.cpp 直接链接 Java_com_reverie_paint_core_ReverieCoreBridge_<方法名>
# 符号。类名或方法名一旦被混淆, 338 个 external fun 会全部 UnsatisfiedLinkError
# (表现为一进绘画页就崩)。
-keep class com.reverie.paint.core.ReverieCoreBridge { *; }

# ------------------------------------------------------------- Qt for Android
# ReverieCoreBridge.initQtAndroid() / syncActivity() 反射调用
# QtNative.setClassLoader() 并写入私有静态字段 m_activity —— Qt 的
# QAndroidApplication::context() 依赖它, 缺失时 KF6I18n 崩在
# "GetMethodID received NULL jclass" (见桥接类头注释的历史教训)。
-keep class org.qtproject.** { *; }

# -------------------------------------------------- androidx graphics lowlatency
# FrontBufferProbe 用 Class.forName + getDeclaredMethod 探测
# CanvasFrontBufferedRenderer 与 PreservedBufferContentsVerifier (按类名/方法名查找,
# 混淆后探测恒失败 -> 前缓冲真墨通路静默降级)。
-keep class androidx.graphics.lowlatency.** { *; }

# ---------------------------------------------------------- ONNX Runtime (着色)
# ai.onnxruntime 的 Java 类由 JNI 侧回连 (异常构造与对象回填), 官方 AAR 未自带
# keep 规则; 混掉会表现为着色辅助首次推理时报 NoClassDefFoundError。
-keep class ai.onnxruntime.** { *; }

# ------------------------------------------------------------------ Room / WorkManager
# Room 用反射构造生成的数据库实现 (Room.getGeneratedImplementation 里
# Class.forName("<库名>_Impl").getDeclaredConstructor().newInstance())。
# room-runtime 自带的规则只有 `-keep class * extends androidx.room.RoomDatabase`
# ——只保住类名, 成员仍可被删: 实测 R8 的 usage.txt 把 WorkDatabase_Impl 的
# <init>() 当死代码删掉, 启动期 androidx.startup.InitializationProvider 建库时
# 直接 InstantiationException ("Failed to create an instance of
# androidx.work.impl.WorkDatabase") -> 整个应用起不来。
# Glance 桌面小组件通过 WorkManager 引入这条路径, 所以必须显式 keep 构造器。
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# ----------------------------------------------------------- 崩溃上报可读性
# 遥测/崩溃看板需要源文件名与行号; 配合 release 构建归档的 mapping.txt 可 retrace。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
