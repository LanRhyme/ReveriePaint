# 快捷键系统（Krita 级可配置）

关联文件：
`model/ShortcutCatalog.kt`（目录与纯函数）、`core/PaintViewModelShortcuts.kt`（持久化与分发）、
`core/PaintViewModelShortcutActions.kt`（动作执行）、`ui/painting/panels/ShortcutSettingsPage.kt`（设置页）

## 目标

原快捷键只登记了 47 个动作、每个动作一个按键，且禁止改动的"兜底 `when`"里还藏着一批只认硬编码键的
行为（`Ctrl+0` 回正、`Ctrl+Shift+=` 放大等）。本次把它做成 Krita「配置 Krita → 快捷键」的形态：

- **147 个可绑定动作**，分 9 个分类 / 若干小节，覆盖工具、视图、编辑、图层、选区、滤镜、面板、动画
- **每个动作可挂两条按键**（主按键 + 备选按键），与 Krita 的多快捷键一致
- **搜索**（按动作名 / 动作 id / 已绑定按键）与分类筛选
- **冲突显式处理**：改键先检测冲突，弹窗列出被占用的动作，用户选「替换」（解绑对方）或「取消」
- **方案预设**：默认(Krita) / Photoshop / Clip Studio Paint / 单手(无 Ctrl)，整表替换
- **方案导入导出**：纯文本，可复制/粘贴分享
- **单条恢复默认**（列表行上的 ↺）与整表重置
- **长按连发开关**：关闭后按住按键只触发一次

## 数据结构

```kotlin
ShortcutDefinition(id, category, section, nameRes, defaultKeys: List<String>, repeat)
```

- `id` 是持久化键，改动会让老用户的自定义失效，**不要随手改**
- `defaultKeys` 为空 = 出厂不绑定；多于一条 = 多按键出厂绑定（如 `redo` = `Ctrl+Shift+Z` + `Ctrl+Y`）
- `repeat` 决定长按扳机：`NONE` 只认首次按下，`FAST` 50ms 节流，`SLOW` 200ms 节流
- 工具动作的 `id` 统一是 `tool_<Tool.id>`，名称走 `toolShortcutNameRes`（单测保证与 UI 的
  `Tool.labelRes()` 完全一致，避免两张表漂移）

绑定表 `PaintViewModel.shortcutBindings: Map<String, List<String>>` 的三态语义：

| 状态 | 含义 |
|---|---|
| 键不存在 | 使用出厂默认 |
| 键存在且为空列表 | 用户显式解绑（不再回落到默认） |
| 键存在且有值 | 用户自定义按键 |

这条语义是分发正确的关键：`handleKeyEvent` 先匹配"有自定义记录"的动作，再匹配"没有记录"的动作的出厂键，
所以解绑后的动作不会被出厂默认重新激活。

## 工具键长按 = 临时工具（Krita transient tool）

绘画/填充/形状/选区这四族工具（`holdTemporary = true`）支持"按住临时借用"：

- 按下工具键**立刻**切到该工具（不延迟，保证按到就能用）
- 松开时按住时长 < 280ms → 视为单击，保持切换（与改动前完全一致）
- 松开时按住时长 ≥ 280ms → 回到按住之前的工具；若期间工具已被别的操作改掉，则不抢用户的显式切换
- 松开走 `KeyEventType.KeyUp`，所以 `handleNativeKeyEvent` 必须放行 `ACTION_UP`；空格长按平移本来就是成对处理
- 切后台时 `clearTemporaryToolHold()` 丢掉挂起状态（KeyUp 可能永远不到）
- 全局开关「长按工具键临时使用」(`shortcut_hold_temporary`，默认开) 可以整族关掉

不参与临时切换的工具：变换、裁剪、文本、液化、测量、抓手（本身是开关）、吸管（自带一次性临时语义）、
绘图辅助，以及所有非工具动作。开启的判定按 `ToolGroup` 走，见 `TEMPORARY_HOLD_TOOL_GROUPS`。

## 持久化与迁移

- SharedPreferences 键仍是 `shortcut_bindings`（备份/恢复与旧版本共用），内容改为制表符纯文本：
  `<id>\t<按键1>\t<按键2>`，首行 `#ReveriePaint Shortcuts v1`
- 用纯文本而非 JSON：`model/` 保持零 Android 依赖、编解码可在 JVM 单测直接往返
- 旧版本写入的 JSON 对象（`{"undo":"LeftCtrl + Z"}`）在 `loadShortcuts()` 里一次性迁移成单按键列表，
  迁移后立即按新格式回写
- `LocalBackupEngine` 的快捷键计数改用同一个解析器，旧备份（JSON 内容）仍可恢复

## 按键规范化

- `normalizeShortcut` 统一修饰键名称（`Ctrl/LeftCtrl/Command/Meta` → `CTRL`）与顺序，比较时忽略
  左右修饰键与大小写
- `+` 键特判：多数键盘的 `+` 就是 `Shift+=`（或小键盘 `+`），`keyEventToString` 对
  `=/小键盘=/+/小键盘+` 统一输出 `=` 并忽略 Shift，所以 `Ctrl+=`、`Ctrl+Shift+=`、
  `Ctrl+小键盘+` 都命中同一条"放大画布"绑定（这是原兜底 `when` 里四条 key 的等价合并）
- `Space` 长按平移是**固定操作**，不进入目录：它需要 KeyDown/KeyUp 成对语义，在
  `handleKeyEvent`/`handleNativeKeyEvent` 的按键层实现；设置页底部有说明文案

## 分发

`handleKeyEvent` → `findShortcutDefinitionForKey(keyStr)` → `executeShortcutAction(id)`。
执行器按域拆成 9 个 `private fun PaintViewModel.executeXxxShortcutAction(id): Boolean`，
顺序试探、命中即返回。需要 UI 层状态（视口/面板开关/滤镜页）的动作发 `pendingUiCommand`
令牌，由 `PaintingPage` 的 `LaunchedEffect(vm.uiCommandTick)` 消费。

新增一个可绑定动作只需两步：

1. `ShortcutCatalog.kt` 的 `ALL_SHORTCUT_DEFINITIONS` 里加一条（同时补中英文案）
2. `PaintViewModelShortcutActions.kt` 对应域加一个分支

单测 `ShortcutCatalogTest` 会检查 id 唯一、默认键互不冲突、小节归属、工具名一致、方案内无冲突。

## 预设方案的边界

预设是"整表替换"：未列出的动作回到出厂默认，因此不会残留上一个方案的按键。
`ONE_HAND` 额外清掉所有 `CTRL` 组合（平板外接键盘单手可用），单测保证清完之后没有重复键。

Photoshop / Clip Studio Paint 方案只是**近似**常见习惯（例如 PS 的 `H` 抓手与 `,`/`.` 轮换笔预设、
CSP 的 `Ctrl+Y` 重做），不追求与桌面版逐条一致。

## 与数位笔动作的关系

`executeStylusAction`（S Pen 侧键 / 隔空手势）复用同一个 `executeShortcutAction`，因此
`pan_canvas`、`toggle_quick_menu` 这类**只属于笔的 id** 必须继续被处理：

- `pan_canvas` = 抓手开关（再按一次回上一工具）
- `toggle_quick_menu` = 画布滑出快捷菜单（历史语义，不能改成"快捷操作浮窗"；
  浮窗用新 id `toggle_quick_actions`）

`StylusAction` 的 id 白名单与快捷键目录是两套东西：新增可绑定动作不会自动出现在笔动作列表里。

## 界面

- 视觉一律用"色块分层"，**不画描边**：搜索框/分类栏/方案行/动作卡片/按键胶囊都是纯填充，
  录制与导入导出弹窗用阴影区分层级
- 顶部（标题、统计、搜索、分类、方案）固定，只有动作列表滚动：147 条列表翻到中段时
  仍能直接换分类或搜关键词
- 分类胶囊横滑；未绑定的按键显示为暗色 "—"，已绑定为 accent 色胶囊，
  与别处动作冲突的按键转成错误色并带 ⚠ 标记
- 已自定义的行在按键左侧有一个 accent 色的 ↺，点一下只恢复这一条
- 底部两个开关：长按工具键临时使用、长按按键连续触发

## 手动回归

1. 设置 → 快捷键：切分类、搜索、清空搜索；确认小节标题与动作数正确
2. 给「撤销」加备选键 `Z`，回画布按 `Z` 撤销、按 `Ctrl+Z` 仍撤销；再把它改成已被占用的键时应弹出冲突确认
3. 冲突弹窗选「取消」→ 绑定不变；选「替换」→ 对方动作按键被清空（列表里显示「无」）
4. 单条 ↺ 恢复默认、整表重置（有确认弹窗）
5. 依次套用 4 个方案，检查列表按键变化与「方案」标签，以及 `Ctrl+Z`/`B`/`E`/`G` 等键的实际行为
6. 导出方案 → 复制 → 重置 → 粘贴导入 → 确认绑定恢复；导入非法文本应提示无法识别
7. 关闭「长按按键连续触发」，按住 `]` 只生效一次；打开后可连续放大
7.1 切到「全部」并滑到列表中部，确认搜索框与分类栏仍固定在顶部、没有被滚走
7.2 未绑定动作显示 "—"，与其它动作冲突的按键变红并出现 ⚠ 图标
8. 手写笔侧键 / S Pen 隔空：吸管取色仍是一次性临时吸管，`pan_canvas` 仍能切回上一工具，
   `toggle_quick_menu` 仍打开画布滑出菜单
9. 空格长按仍临时平移画布；`Tab` 仍切全屏纯净模式（出厂默认）
9.1 长按 `B`/`E`/`M` 等工具键超过 ~0.3 秒再松开，应回到按下前的工具；快速点按仍是永久切换
9.2 关掉「长按工具键临时使用」后，长按与点按行为一致（永久切换）
10. 组合键含 Shift 的放大：`Ctrl+=`、`Ctrl+Shift+=`、小键盘 `+` 都应放大

## 已知取舍

- 长按键的连发策略是动作元数据（`ShortcutRepeat`），没有做成逐条可配置的 UI，避免设置页过度拥挤
- 预设方案不做逐条差异预览；切换方案后如需微调，直接在动作列表里改
- 目前只在绘画页生效（`handleNativeKeyEvent` 有 `currentPage == PAINTING` 门禁），与改动前一致
- Krita 的 Canvas Input Settings（修饰键 + 鼠标/笔拖拽的输入映射）不在本次范围内：
  Android 的触摸/笔手势由 `CanvasTouchView` 与手写笔侧键通道负责，空格长按平移固定不可改绑
- 日语 / 繁体 / 猫猫语的新文案按 AGENTS.md 在发版前统一补全，本次只维护简中与英文
