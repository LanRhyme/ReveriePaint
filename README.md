<div align="center">

<img src="art/icon.png" width="128" height="128" alt="ReveriePaint Icon" />

# ReveriePaint

<p>
  <a href="https://github.com/LanRhyme/ReveriePaint/releases"><img src="https://img.shields.io/github/v/release/LanRhyme/ReveriePaint?color=5E8BA8&style=flat-square" alt="Release"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2023%2B)-5E8BA8?style=flat-square" alt="Android Version">
  <img src="https://img.shields.io/badge/Arch-arm64--v8a-7C8F9E?style=flat-square" alt="Architecture">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-8D9E8F?style=flat-square" alt="License"></a>
  <a href="https://afdian.com/a/LanRhyme"><img src="https://img.shields.io/badge/Afdian-@LanRhyme-946ce6?style=flat-square" alt="Afdian"></a>
  <img src="https://img.shields.io/badge/QQ%E7%BE%A4-729283213-12B7F5?style=flat-square" alt="QQ Group">
</p>

基于 Krita 核心引擎打造的 Android 原生现代数字绘画应用

融合 Jetpack Compose 现代化界面与 Krita C++ 原生图像处理内核, 专为平板与触控设备优化的专业创作工作流

</div>

---

## 核心特性

### 绘画引擎与笔刷系统
- **Krita 官方内核集成**: 直接复用 Krita 核心笔刷引擎, 真实物理笔触与颜料混合模拟
- **内置丰富笔刷库**: 内置 240+ 官方笔刷预设, 涵盖铅笔、钢笔、墨水、水彩、油画、喷枪、纹理与马克笔
- **笔刷工坊**: 支持实时调整尺寸、不透明度、流量、间距、软硬度、混色比与压感动态曲线
- **硬件压感适配**: 深度适配 Android 压感手写笔, 具备抖动修正、子帧平滑插值与悬浮光标预览

### 专业图层与合成管理
- **无限图层与分组树**: 动态稀疏瓦片内存管理, 支持无限图层创建、图层组嵌套与层级折叠
- **丰富混合模式**: 支持正常、正片叠底、滤色、叠加、柔光、强光、颜色减淡等 25 种混合模式
- **图层操作全功能**: 剪贴蒙版、Alpha 锁定、图层锁定、独立隐藏/显示、快速合并、向下合并与色彩标签
- **直观交互**: 图层面板支持长按拖拽排序、向左滑动快捷呼出操作菜单与批量图层管理

### 创作工具箱
- **多样化绘制工具**: 包含画笔、橡皮擦、涂抹、模糊、液化变形、渐变填充与文字排版工具
- **矢量几何辅助**: 直线、矩形、椭圆与多边形工具, 支持快速吸附与辅助对齐
- **智能选区体系**: 套索选区、矩形选区、椭圆选区、魔棒快速取选与颜色选区, 支持加选、减选、反选与羽化
- **图形变换**: 支持自由变换、等比缩放、旋转、透视扭曲与画布裁剪

### 录制与延时回放
- **全流程事件流录制**: 零性能开销记录笔迹、笔刷参数、图层变动、滤镜与色彩变迁全过程
- **独立工程归档**: 录制数据随 `.revp` 工程文件自动存储压缩, 方便跨设备共享
- **多功能回放器**: 支持随时在画廊中唤起过程回放, 具备播放、暂停、进度条无缝拖动与 0.5x - 4x 倍速调节

### 自动保存与工程安全
- **后台保活自动存储**: 支持 1/3/5/10/15/30 分钟多档静默自动保存
- **状态恢复机制**: 应用切至后台或意外中断时自动保留最后创作状态, 画廊自动标记未保存草稿并支持快速恢复

### 现代触控与个性化主题
- **多点手势交互**: 双指捏合自由缩放、旋转、平移画布, 120 FPS 视口变换
- **快捷手势操作**: 双指点击撤销、三指点击重做、长按快速吸色
- **莫兰迪全局主题**: 优雅柔和的低饱和度配色体系, 完美支持 Material You 动态莫奈取色
- **工作区底色自由定制**: 支持随主题自适应、经典深灰、暗夜纯黑、明亮纯白及任意自定义 Hex 底色

---

## 快速上手

### 系统要求
- **操作系统**: Android 7.0 及以上 (API Level 23+)
- **芯片架构**: 仅支持 64 位 ARM 处理器 (`arm64-v8a`)
- **推荐设备**: 支持主动式压感手写笔的 Android 平板或大屏移动设备

### 安装说明
1. 前往 [Releases 发布页面](https://github.com/LanRhyme/ReveriePaint/releases) 下载最新版本的 APK 安装包
2. 在设备上点击 APK 文件并允许安装来自此来源的应用
3. 授予存储与手写笔相关权限后即可开启创作

---

## 开发者与源码构建

有关项目技术架构、本地开发环境配置、编译构建步骤与代码规范, 请参阅 [CONTRIBUTING.md](CONTRIBUTING.md) 以及开发指引文档 [AGENTS.md](AGENTS.md)

---

## 开源协议与鸣谢

- **应用本体**: 基于 [GPL-3.0 License](LICENSE) 开源
- **图像引擎**: 绘画与图像处理内核复用 [Krita](https://invent.kde.org/graphics/krita) (GPL-3.0)
- **图标素材**: UI 图标采用 [Tabler Icons](https://tabler.io/icons) (MIT)

---

## 社区与交流

- **QQ 交流群**: 729283213
- **问题反馈**: 欢迎通过 [GitHub Issues](https://github.com/LanRhyme/ReveriePaint/issues) 提交反馈与功能建议
