# 动态滤镜图层技术调研与待办记录

## 背景与现状

在现有架构中滤镜图层作为盖印可见图层的静态图层存在 尝试将其重构为类似 Krita KisAdjustmentLayer 的非破坏性动态滤镜属性图层时遇到若干底层同步与性能冲突 经评估决定暂时回滚至稳定版本 并将全动态滤镜图层设为后续待办事项

## 核心技术难点与经验记录

### 1 Krita KisAdjustmentLayer 与异步合并器机制
- Krita 原生 KisAdjustmentLayer 在 KisAsyncMerger 遍历图层树时会清空 original 设备并调用内置插件滤镜
- ReverieCore 使用静态链接的多核多线程滤镜算法 而非 Krita 桌面端动态插件注册表 导致直接挂载 KisAdjustmentLayer 会被合并器重置为透明

### 2 运笔时实时重算与触控流畅度冲突
- 在 8ms 笔刷采样批次中强行执行全图多核滤镜重投影会导致主绘制线程阻塞 笔刷采样堆积 产生严重运笔卡顿
- 若仅在抬笔 touchStrokeEnd 时触发滤镜重投影 则在绘制过程中滤镜图层下方的线条无法实时附带滤镜效果
- 若在 RenderPass 实时动态合成 则全屏 CPU 滤镜运算会导致屏幕帧率下降并在极端笔刷下产生额外延迟

### 3 笔刷自交遮罩与画布背景底色
- Krita 针对 paintbrush 笔刷（如 Basic-1）维护了 interstrokeData 用于防止同笔自交重复叠加透明度 若笔画结束未显式重置跨笔数据 会导致下一笔与上一笔相交处出现白色空隙
- 动态重算管线如果未显式注入 defaultProjectionColor 画布底色 则透明像素会覆盖画布背景导致网格化

## 后续待办事项 (TODO)

- [ ] 动态滤镜图层架构重构
- [ ] 针对局部脏区的增量 GPU / 异步着色器滤镜管线探索
- [ ] 笔刷绘制中对局部实时视口应用滤镜着色器 而非全局 CPU 多核遍历
- [ ] 完善 KisAdjustmentLayer 插件注册接口或在 KisAsyncMerger 接入原生滤镜通道

## 2026-08-23 二次实验记录 (已回滚)

- 全量滤镜注册为 KisFilter(reverie-f0..34) + 真 KisAdjustmentLayer + generator 填充层在真机暴露三类问题, 功能入口暂时下线(基础设施保留: 内核抽取/注册表/layers.xml 持久化/录制回放码):
- ①创建即污染画布: 菜单以 HSBC(filterType=0) 全零参数建层, sat=0 饱和度归零/bright=0 压暗随 merger 重放 → 画布变灰内容消失。教训: 调整层初始参数必须取面板默认值(所见即所得), 且面板需配置流(节流直推 setFilter+进入快照回滚)。
- ②预乘字节序: 注册表滤镜 processImpl 若用 convertToQImage(输出 ARGB32_Premultiplied) 后 writeBytes 写回(按非预乘解释)会逐帧污染投影; 必须用 Format_ARGB32_Premultiplied + readBytes 区域读写。
- ③填充层 SIGSEGV(fault addr 0x0, reverie-render 线程): 前置日志 profileForCsIdWithFallbackImpl couldn't fetch a fallback profile for <空>(KoColorSpaceRegistry.cpp:431) — KisGeneratorLayer 链上某处 colorSpace() 返回 null。generate() 已插桩守卫(qWarning 标记 "reverie-solid-color: generate abort dev=%p cs=%p config=%p"), 复现时 `adb logcat -d -s reverie` 看最后一条定位帧; 无标记则崩点在 strategy/ctor 链更上游。
