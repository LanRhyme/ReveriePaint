# INCIDENT — 1.6.3 移动变换画布空白事故分析与加固（2026-11-02）

## 0. 事故概要

- 来源: 用户反馈移动变换时草稿内容“全没了，撤回也没用，自动保存也没用”
- 诊断报告: `reverie_diag_20261102_143103.txt`（Samsung SM-T860 Tab S6，Android 12，App v1.6.3 50，Release）
- 结论: **图层像素未丢失**，为“显示与状态层呈现故障”叠加笔刷解析失败导致的假死假空现象

## 1. 还原时间线

| 时刻 | 行为与日志 | 说明 |
|---|---|---|
| 14:22:17.372–18.522 | 撤回条拖动: 11 步 undo + 约 60 步 redo | 撤销重做通路正常 |
| 14:22:29.936 | `saveProject SUCCESS: Untitled Artwork 2 (155 strokes)` | 手动保存成功 |
| 14:22:38.980 | `loadProject SUCCESS: Untitled Artwork 2` | 重进画布 |
| 14:22:43–14:24:52 | 绘制至 228 笔，最后抬笔 14:24:52.011 | `strokeEnd count=9045` |
| 14:24:27.557 | `Switch tool to: lasso` | 建立选区 |
| 14:24:39–14:24:54 | 连续 7 次 Undo | 撤回选区内笔画 |
| 14:24:59.976 | `Switch tool to: transform` | 进入变换工具 |
| 14:25:00.052 | `Transform Preview` 长事务创建 | 目标图层被剪入临时设备，底图清空 |
| 14:25:01.688 | `Switch tool to: eraser`（疑触碰 S-Pen 侧键） | 切工具触发变换会话取消/自动提交 |
| 14:25:01.717 | `Eraser_hard` 笔尖断言失败 | 笔尖解析为空，橡皮擦不可用 |
| 14:25:07.199 | `autoSaveRevpAsync blob=441958 ... 0.57MB` | 距上次抬笔 15s，自动保存触发 |
| 14:28:07 / 14:29:47 | 自动保存再次落盘，文件大小 0.57MB | 见第二节增量复用证据 |
| 14:26:01 起 | 用户在社群反馈草稿丢失 | 此时画布处于空白/假死态 |

## 2. 关键反证：像素未丢失

后两次自动保存的详细 profile：

```
14:25:07  entries=4(reused=0,   reusedBytes=0)      file=0.57MB
14:28:07  entries=4(reused=2,   reusedBytes=113676) file=0.57MB
14:29:47  entries=4(reused=2,   reusedBytes=113676) file=0.57MB
```

1. `ReverieCoreIO.cpp` 中增量保存复用图层 PNG 的判据为 `dev->sequenceNumber()`
2. `Untitled Artwork 2.autosave.revp` 中的 4 个 PNG 为两图层 + preview + thumbnail，两个图层 PNG 合计 113,676 字节（空白 1080P 图层通常仅 1~2KB），证明图层内完整保留了约 110KB 的草稿像素
3. 14:28:07 与 14:29:47 连续命中两层复用，说明底层的 `cancelTransformPreview()` 在 14:25:07 之前已成功 revert 恢复像素，随后没有发生任何像素篡改

## 3. 根因剖析

### 3.1 为什么视觉上“全没了”

- 变换预览开局破坏性清空: `startTransformPreview` 直接对图层执行 `device->clear()`，把像素暂存至临时设备，画面显示完全依赖 Compose 层的 `transformPreviewBitmap` 悬浮位图
- 快速切工具与空选区边界: 14:25:01 切至橡皮时，空选区与零位移走 cancel 路径；`cancelTransformPreview()` 虽在 C++ 成功 revert，但未向 UI 层发出 `notifyLayerChanged` 与缩略图强刷信号，导致视图层没有及时重组出恢复后的图层

### 3.2 为什么“撤回也没用”

- 变换事务不入历史栈: 变换预览为引擎内部临时长事务，cancel 时通过 `revert()` 释放，在撤销管理器中并不产生历史步；用户点击撤销无法“撤销回滚”，反而会撤掉进入变换前画的笔画
- 门禁机制: v1.6.3 期间若 `transformPreviewBitmap` 残留或引擎状态未清，撤销会被 `isHistoryBlockedByTransform` 拦截且提示节流

### 3.3 为什么“自动保存也没用”

- 守卫条件缺失: `PaintViewModelProjects.kt` 中的自动保存守卫仅检查 `isTransformActive`（双指捏合手势标志），未覆盖变换工具预览状态
- 视效误导: 自动保存文件本身是完好的，但由于用户在当前界面看到是空白，重新打开若继续遇到显示未刷新的旧态，会误以为自动保存也是空的

### 3.4 橡皮笔尖断言失败

14:25:01 切到 `Eraser_hard` 时触发 `SAFE ASSERT "!d->brushTipImage.isNull()"`，笔尖有效性 `valid=0`，导致此后屏幕触摸无法起笔，用户误以为整个软件卡死崩溃

## 4. 挽回方案

1. **自动保存历史快照**: 打开应用主页 -> 通用设置 -> 最近自动保存快照，选择 14:25 左右对应条目，点击“以副本打开”即可正常呈现
2. **直接读取图层 PNG**: 将 `/data/user/0/com.reverie.paint/files/autosave/Untitled Artwork 2.autosave.revp` 复制并重命名为 `.zip`，解压即可提取 `layer_001.png` 获取草稿原图
3. **回放引擎重建**: 进入该作品回放页，事件流（441KB）完好，拖至第 228 笔处导出即可

## 5. 加固措施

1. **自动保存守卫与骤降防冲刷**:
   - `PaintViewModelProjects.kt` 自动保存守卫校验 `currentToolId == Tool.TRANSFORM.id || transformPreviewBitmap != null`，变换期间禁止落盘
   - 若覆盖保存时新文件体积较旧文件骤降 70% 以上，自动保留 `.autosave.revp.bak` 备份
2. **变换取消强刷机制**:
   - `PaintViewModelTools.kt` 中 `cancelTransformPreview` 的 `after` 回调补充 `notifyLayerChanged(forceThumbs = true, immediateRender = true)`，确保取消后图层与缩略图立即同频刷新
3. **页面生命周期兜底**:
   - `PaintingPage.kt` 增加 `DisposableEffect`，确保离页、切页时兜底调用 `cancelTransformPreview` 与 `tfState.active = false`，不残留未完长事务
4. **JNI 失败兜底回滚**:
   - `reverie_jni_tools.cpp` 中若位图锁定或预览提取失败，立即调用 `core()->cancelTransformPreview()` 恢复原图层，杜绝图层被置空后无位图可画的情况
