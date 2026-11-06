# 录制 / 回放的自动验证

回放是把录制的事件流重新灌回同一个引擎，所以「回放到某个进度，再把文档另存出来，
与预期像素比较」就是**客观、可重复、可进 CI** 的判据 —— 不需要人眼看屏幕，
也不需要构造具体的操作步骤去复现。

两套脚本 + 一个 debug 专用接收器：

| 组件 | 作用 |
|---|---|
| `app/src/debug/.../ReplayVerifyReceiver.kt` | 仅 debug 源集。adb 广播触发：打开工程 → 进回放 → 按 steps 依次 seek 并另存文档 → 写 `result.json`；`resave` 模式只做「打开 → 正常保存」（用于验证录制重新锚定） |
| `scripts/replay-verify.py` | 真工程保真度：触发、等待、拉取、逐层像素比较，输出墨迹像素 / 差异像素 / 差异 bbox；退出码可进 CI |
| `scripts/replay-synth.py` | 合成用例：手写事件流 + 可计算的期望图，逐个钉住单种操作（填充、圈涂、变换、渐变、撤销/重做） |

## 用法

```bash
# 真工程: 回放是否忠实于原件
python3 scripts/replay-verify.py --serial <serial> --project "作品.revp" --steps 0.5,1.0

# 单个工具操作的判定用例 (全部通过时退出码 0)
python3 scripts/replay-synth.py --serial <serial>
python3 scripts/replay-synth.py --serial <serial> --only fill_tolerance
```

`--keep` 保留设备侧与宿主机侧产物，`.replay-verify/<run>/` 下有 `original.revp`、
`step_XX_<进度>.revp`、`compare.json`。

## 定位手段（从"整体不一致"到"哪一条事件"）

- **截断二分**：把 recording 截断到前 N% 事件重放，找墨迹"掉到 0"的那一刻
- **消融**：删掉某一类事件（如全部 `L_CLEAR`）再重放，看差异是否消失
- 两者都是纯 Python 改写 zip 里的 `recording` 条目，见提交历史里的 `truncate.py` / `ablate.py`

## 合成用例的写法

1. 以**真实工程**为容器（只替换 `recording` / `layer_001.png` / 预览图），保证容器是引擎认可的结构
2. 事件流：`Ev()` 构造器逐条写入（`session_start` / `context` / `fill_v4` / `enclose_fill` / `transform` / `gradient_v3` / `undo` / `redo`）
3. 期望图：PIL 直接算（纯色区域、色块边界、多边形、线性渐变）
4. 需要"已有画面"的用例：把起点画面写成一份 `.revp` 并作为**录制内嵌快照**（`flags` bit0），回放会从它开始

## 当前覆盖

| 用例 | 覆盖点 | 结果 |
|---|---|---|
| `fill_whole` | 泛滥填充（整层） | 0 px |
| `fill_tolerance` | 容差填充被色块边界挡住 | 0 px |
| `enclose_in_outline` | 圈涂沿轮廓填内部区域 | 0 px |
| `transform_translate` | 整层平移（T_TRANSFORM） | 0 px |
| `gradient_linear` | 渐变 V3（双色标） | 0 px |
| `undo_fill` / `redo_fill` | 填充的撤销 / 重做 | 0 px |
| `pattern_fill_region` | 图案填充（区域模式，纯色图案） | 0 px |
| `liquify_noop` | 液化强度 0 不改画面（且后续事件不错位） | 0 px |
| `liquify_bounded` | 液化强度 1：改动 > 0 且全在掰动中心附近（自定义判定） | 通过 |
| `filter_curves_invert` | 曲线 LUT（通道反转）提交到图层像素 | 0 px |
| `timeline` | 时间序列：0.25/0.5/0.75/1.0 四个中间帧各自确定 | 4 帧全 0 px |

## 上屏帧自检

文档对不代表用户看到的画面对。`seek` 后若投影没整幅重合成，画布上只剩当前脏区
（"显示不出来 / 只剩一条"），而另存出来的文档本身是好的 —— 只比文档永远发现不了。

`ReplayVerifyReceiver` 每次 seek 完抓两张上屏帧（400ms / 1400ms，1/4 缩放），
`replay-verify.py` 逐帧与该进度文档的合成结果比墨迹量。晚帧用于区分"投影没重合成"
与"抓帧太早"。

两个坑：
- 合成文档时必须按 `layers.xml` 的 `visible` / `opacity` 处理，否则隐藏层会让"文档墨迹"
  虚高，帧看着只有 0.5 倍（我先踩过，差点误判成 bug）
- 帧文件名带进度（`frame_00_0.300.png` / `..._late.png`），要按进度配文档，不能按顺序配

## flags=0 的老录制

`未命名作品 4.revp` 这类 `flags=0` 的录制裁不了快照，回放从空白画布重建，逐层必然对不上。
这不是回放逻辑的 bug，是录制时就没存起点。新版本保存的工程（`flags=1`）才能逐层像素一致。

## 判定方式

- **精确判定**：期望图完全可算 → 比较差异像素数（阈值通常 0.5%~3%）
- **自定义判定**：结果不可精确预测但性质可判 → `CHECKERS` 里注册一个 `check(got, expected)`
  - 液化用几何性质：改动像素数 > 0，且改动全部落在掰动中心的半径内（Krita 的形变网格无法离线复现）
- **自定义判定的坑**：直接对 RGBA 用 `ImageChops.difference(...).getbbox()` 在只有部分通道变化时可能判成"无差异"，
  要先逐通道取最大值 (`ImageChops.lighter`) 再求 bbox —— 液化用例就踩过这个，差点把"没改动"当成通过

## 时间序列（多步）用例

`timeline` 用例的事件时间戳刻意排成 8/16/32/48/64ms，于是进度 0.25/0.5/0.75/1.0
正好各自落在一次事件之后，期望图可以逐帧算出来（填充 → 撤销 → 重做 → 平移）。

这个用例抓到过一个真实的 seek 缺陷：`ReplayTimeline.advance` 会执行"跨过目标"的那条事件
（播放需要这样），但 seek 沿用它就会把目标之后很远的操作提前执行掉 —— 拖到 25% 却出现
50% 之后才该有的内容，继续播放又从错误状态开始。现在 seek 走 `advanceExclusive`
（只应用时间戳不晚于目标的事件）。

另外：回放一进页面就自动播放，验证器在每次 seek 前会先 `pauseReplay()`，
否则断言的是"当前位置 + 目标"而不是"目标位置"。

## 已知边界

- 圈涂在**纯透明无轮廓**的层上不填充是引擎语义（没有闭合区域可填），不是回放问题；用例因此把轮廓放进快照
- 笔画是亚像素级的：回放用 `currentMs/1000` 作笔迹时间，速度类动态与当时略有出入，单笔终态差约 0.06% 像素（非内容丢失）
- 只比终态；「回放过程中一闪一闪」这类**时间序列**问题还没纳入，需要按进度采样多帧比较
- 整层图案填充 (`wholeLayer=1`) 只对**填充层**生效（引擎 `setFillLayerPattern` 要求 `reverie_is_fill`），
  普通颜料层要走区域模式；要测整层模式需要一个带填充层的容器

## 相关提交

- `419b41c4` 验证工具（receiver + replay-verify.py）
- `ca8898cd` 录制无快照时重新锚定 + 会话边界清撤销栈（用它定位出的内容丢失）
- `db2d4098` 填充 expand/feather/closeGap 纳入录制 + 回放数据损坏不再崩渲染线程 + 合成用例
