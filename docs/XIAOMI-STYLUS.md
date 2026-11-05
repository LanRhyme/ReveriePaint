# 小米焦点触控笔 Pro 手势链路

## 问题与修复（2026-10-29）

用户报告 K Pad 2 / HyperOS 4 上书写正常，双击和轻捏均无响应，自动匹配和手动选择 Pro 都无效

原实现存在三处断点：

- `Class.forName("com.miui.penengine.touchfilm.MiuiTouchFilmUtils")` 加载的是应用需要集成的 SDK 类，
  当前构建只包含 Qt JAR，没有 PenEngine AAR，不能假设设备 ROM 会把 SDK 类放进应用 ClassLoader
- 初始化异常被吞掉，且没有注册 `PencilEngineManagerService`，无法要求系统投递触控手势
- 通用侧键白名单可能拦截键码 194–197；SDK 回调的功能编号也不代表手势类型，
  原实现把功能 1 当双击、功能 4 当轻捏，系统更改动作配置后会发生错配

新实现独立连接系统服务，并直接解析手势 KeyEvent，保留现有应用内动作配置，不引入专有 SDK 二进制

## 影响评估

- 小米 Pro：轻捏、双击、上下滑动，按键重复分发，前后台切换和服务重连
- 旧款小米笔：实体主键、副键、焦点键及按住擦除继续走原逻辑
- 外接键盘和手柄：字母键盘与 GAMEPAD / JOYSTICK 来源不作为 Pro 手势来源
- 其他品牌：沿用原品牌适配器及侧键过滤
- 绘画线程、JNI、像素缓冲、笔画批处理、画布手势 key 均无改动

## 协议边界

系统服务：`com.xiaomi.touchservice.pencilengine.PencilEngineManagerService`

Binder 描述符：`com.xiaomi.touchservice.pencilengine.IPencilEngine`

| 事务编号 | 参数 | 返回值 |
|---|---|---|
| 1 | 回调 Binder | 无 |
| 2 | 无，注销当前客户端 | 无 |
| 3 | 能力编号、开关；触控能力为 `2` | 整数状态码，`0` 为成功 |
| 4 | 回调 Binder、应用包名 | 无 |

`Settings.System.stylus_pencil_engine_version` 为 `0.3.2` 时使用事务 4，其余使用事务 1，
对应所核对客户端的版本分支；不能据此推断所有后续 ROM 的兼容性

回调描述符为 `com.xiaomi.touchservice.pencilengine.IPencilEngineCallback`，
事务 1 接收三个整数，是取色结果通道，不是手势回调，本适配器不使用该结果

| Android KeyCode | 手势 | 应用配置 |
|---|---|---|
| 194 | 轻捏 | `xiaomiSqueezeAction` |
| 195 | 双击 | `xiaomiDoubleTapAction` |
| 196 | 上滑 | `xiaomiSlideAction` |
| 197 | 下滑 | `xiaomiSlideAction` |

这些键码对应 Android `KEYCODE_BUTTON_7` 至 `KEYCODE_BUTTON_10`，不是 Android 的 F15–F18，
不能与 Linux 扫描码或键盘功能键混用

手势仅在小米设备、Pro 模型、Activity 已恢复、绘画页且非文本输入/快捷键录制时执行
动作 DOWN 执行一次，UP 仅复位；重复 DOWN 与长按连发不重复执行切换或撤销
不写系统手势设置，也不绕过系统对手势投递的限制

## 来源与核对范围

- [小米官方开发准备](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1883)：
  PenEngine 是需要打包到应用中的 AAR
- [小米官方客户端接入指南 §7](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1884)：
  onResume 初始化、dispatchKeyEvent 处理、onPause 销毁；回调传递的是功能编号
- [协议核对用客户端快照](https://github.com/Dhyan2404/StarNotes/tree/6d725ed8c4abca7c8efaea3d5d2b552b6f7d5abe/decompiled/java_sources/sources/com/miui/penengine)：
  核对 Binder 描述符、事务参数、版本分支和键码，新增实现不包含 SDK 代码或二进制
- [FocusPenProX 协议记录](https://github.com/HMQYHM/FocusPenProX/blob/main/app/src/main/java/io/github/hmqyhm/focuspenpro/hook/CompatProfile.kt)：
  额外核对滑动键 196 / 197，该项目运行于系统进程，不能照搬其拦截入口到普通应用

## 真机回归与诊断

本地机械验证：

- 本机缺少 Android SDK 的阻挡已解决：官方 SDK 36 / Build Tools 36.0.0 安装在忽略目录
  `.local/android-sdk`，通过当前进程的 `ANDROID_HOME` 指定，不写入 `local.properties`
- JDK 17 下 `:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` 全部成功
- 项目单元测试共 1361 项，1348 项通过、13 项跳过，零失败、零错误
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，130560320 字节（124.51 MiB），
  包名 `com.reverie.paint`，版本 `1.6.1` / 48，仅 `arm64-v8a`
- APK v1 / v2 签名校验通过，确认包含手势服务类、JNI 桥接库及 arm64 原生依赖
- 在工作区忽略目录 `.local/xiaomi-verification` 用独立 Kotlin/JVM 工程编译真实服务桥接、
  小米适配器、手势模型与接口；Android API 使用 `android-all`，ViewModel 和反馈管理器使用类型桩，
  仅作为局部类型检查，不替代应用构建
- 真实 `XiaomiTouchFilmKeysTest` 的 4 项用例通过：独立手势、重复/长按过滤、释放/暂停复位、无关键透传
- Manifest XML 解析与 `git diff --check` 通过

日志标签：`ReverieXiaomiTouchFilm`

服务注册成功应出现 `Touch-film service ready`，失败会记录绑定、接口、启用返回码等具体原因
尚无 K Pad 2 / HyperOS 4 真机验证结果，服务导出权限、后续协议版本和系统手势开关均需设备确认

待真机回归：

1. 手动选择 Pro，把双击设为切换橡皮、轻捏设为调色盘，确认两者各触发一次
2. 更换系统手势动作后返回应用，确认应用内双击与轻捏映射仍能区分
3. 切后台再返回、关闭再启用 Pro、快速连续手势，确认恢复正常且无重复动作
4. 外接键盘、手柄和旧款小米笔侧键回归
5. 如果仍无响应，先看服务是否成功注册，再确认系统是否向应用投递 194–197 事件
