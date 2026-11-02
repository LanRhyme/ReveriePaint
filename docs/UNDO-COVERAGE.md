# 撤销栈覆盖面（桌面端对齐清单）

目标：**凡是会改变文档内容或文档状态的用户操作，都应该是一步撤销**，与桌面 Krita 的
撤销栈语义一致；纯视图/工具/笔刷偏好不进撤销栈。

维护约定（新增可撤销操作时照做）：

1. 新代码一律走 `ReverieCore::pushUndoCommand()`（它统一处理回放模式
   `m_undoCaptureEnabled=false` 与 `m_redoCount` 清零），不要直接
   `undoAdapter()->addCommand()`。
   （存量例外：笔刷选区移动、多图层变换、液化三处仍是直接 `addCommand` —— 它们在回放
   模式下会把自己的命令也压进栈，靠回放结束时的 `clearUndoHistory()` 兜底，暂不动。）
2. **拖动类滑块**用 `xxxDirect()` 高频预览 + 松手调用非 Direct 版本提交一步
   （先例：`setLayerOpacityDirect` / `setLayerOpacity`、`setLayerStrokeParamsDirect` /
   `setLayerStrokeParams`）。
3. 打开"暂定事务"（预览/暂存回滚）之前必须先 `invalidateRedoState()`，
   见 `docs/INCIDENT-1.6.4-HISTORY-REDO-CRASH.md`。
4. 命令的执行体放在 `redo()` 里（`KUndo2QStack::push` 会执行一次 redo），
   `undo()` 负责回滚；不要在这两个函数里做异步/需要等待的操作。

## 1. 可撤销（已覆盖）

| 域 | 操作 | 实现 |
|---|---|---|
| 绘画 | 笔刷笔画（含对称/双笔刷蒙版） | `KisTransaction` → "Stroke" 宏 |
| 绘画 | 速创图形提交 | 同上（重放为一次笔画） |
| 绘画 | 填充图层 / 图案填充 / 印章 / 选区内容移动 | `KisTransaction` + `ReverieCompositeCommand` |
| 绘画 | 液化（整段手势一步） | `KisTransaction`（每目标设备一个）|
| 绘画 | 滤镜应用（单层/多层） | `KisTransaction` + 备份设备 |
| 图层 | 新增 / 删除 / 复制 / 拖拽排序 / 上移下移 / 入组 / 出组 | `KisImageLayerAddCommand` / `RemoveCommand` / **`KisImageLayerMoveCommand`** |
| 图层 | 合并 / 向下合并 / 拼合组 / 栅格化 / 应用蒙版 / 加蒙版 / 删蒙版 | 组合命令 |
| 图层 | 重命名 | `KisNodeRenameCommand` |
| 图层 | 可见性 | `ReverieNodeVisibleCommand` |
| 图层 | 不透明度 | `KisNodeOpacityCommand`（拖动用 Direct 预览）|
| 图层 | 混合模式 | `KisNodeCompositeOpCommand` |
| 图层 | 剪贴蒙版 / 继承 alpha | `ReverieNodeClippingCommand` / `ReverieNodeAlphaInheritCommand` |
| 图层 | **透明度锁定** | `ReverieNodeFlagCommand(AlphaLocked)` |
| 图层 | **图层锁定** | `ReverieNodeFlagCommand(UserLocked)` |
| 图层 | **颜色标签** | `ReverieNodeColorLabelCommand` |
| 图层 | **组穿透** | `ReverieGroupPassThroughCommand` |
| 图层 | 描边图层参数 | `KisTransaction`（拖动用 Direct 预览）|
| 图层 | 填充/调整层配置 | 组合命令 |
| 变换 | 自由变换 / 透视 / 网格变形 / 多图层变换 | `KisTransaction`（单步复合）|
| 选区 | 全选 / 反选 / 形状 / 套索 / 魔棒 / 相似 / 从图层 / 扩展 / 收缩 / 羽化 / 填充 / 清除 | `ReverieSelectionCommand` |
| 画布 | 水平/垂直翻转 | `KisTransaction` + `ReverieCompositeCommand` |
| 画布 | 背景色 / **整层填充（清空画布）** | `KisTransaction` |
| 动画 | 关键帧增删/复制/移动、末帧保持、色标、当前帧 | `Krita 关键帧命令` / 自定义 |
| 动画 | **帧率** | `ReverieAnimationFramerateCommand`（连续改合并一步）|
| 动画 | **播放范围** | `ReverieAnimationRangeCommand` |

## 2. 故意不进撤销栈

| 类别 | 例子 | 原因 |
|---|---|---|
| 视图 | 缩放/旋转/平移视图、洋葱皮抑制、solo、参考图窗口、吸附 | 不改文档 |
| 工具/笔刷偏好 | 笔刷尺寸/流量/动力学、工具切换、选区模式 | 桌面端同样不入栈 |
| 文件与元数据 | 作者、工程保存/另存、导入笔刷与图案、资源面板 | 桌面端不是撤销步 |
| 预览态 | 变换预览、液化预览、滤镜预览、速创预览 | 未提交的中间态 |
| 暂存选区 | 存储/更新/删除/重命名暂存选区 | 本应用自有的 UI 侧数据（桌面 Krita 用选区蒙版图层表达）|

## 3. 仍未覆盖（有明确原因）

| 操作 | 现状 | 原因 / 后续方向 |
|---|---|---|
| **裁剪画布 / 缩放画布** | 不进撤销栈，且会清空重做区 | `KisImage::resizeImage()` 内部走 `KisProcessingApplicator`（异步 stroke）。撤销回放期间图像处于 `barrierLock`，异步 stroke 排不进来 → `waitForDone` 卡死；桌面 Krita 是靠 `KisSavedCommand`/保存命令把异步 stroke 当撤销步回放解决的。要做需要引入 saved-command 基础设施（大改，单独排期）|
| **图层动画通道开关**（`enableLayerAnimation`）| 不进撤销栈 | libkritaimage 没有导出 `removeKeyframeChannel`，撤销无法把通道删掉；当前只在建工程与"转为动画图层"时调用 |
| 图像分辨率（DPI） | 不进撤销栈 | 桌面 Krita 同样不是撤销步 |
| 时间轴拖动定位 | 默认不进栈（`animationSeek(recordUndo=false)`）| 与桌面一致；关键帧编辑本身是可撤销的 |
