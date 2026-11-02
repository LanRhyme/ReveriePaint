# INCIDENT — 1.6.4 撤销/重做「笔仙」与 SIGABRT 复盘（2026-11-02）

上报: 用户「月涌」，`crash_native_20261102_142426.log`
设备: OPPO OPD2405 / Android 15 (SDK 35)，ReveriePaint 1.6.4 (versionCode 51)，
文档 1000×1000 / 3 图层，Active Tool: brush。

原话：**「撤回两步之后会莫名其妙出现笔仙，然后就闪退了」**。

Breadcrumbs（本地时间，设备时区 UTC+0）：

```
14:23:13 Undo   14:23:20 Undo   14:23:28 Tool→lasso  14:23:30 Undo
14:23:36 Tool→transform         14:23:47 Tool→brush
14:23:56 Undo×2 14:23:58 Undo   14:24:01 Undo×2 …（连续 undo 共 14 次）
14:24:23 Redo   14:24:24 Undo   14:24:25 Undo        14:24:26 Redo → 崩
```

一条 undo、两条 undo、一堆 undo，末尾 Redo/Undo/Redo 交替 —— 这是**撤回条的连续泵**
（`UndoScrubBar` 拖动时 undo/redo 交替下发）的典型节奏。

## 1. 崩溃点：精确到指令

```
#04 libkritaimage.so  KisMementoManager::commit()+1120
#05 libkritaimage.so  KisMementoManager::rollforward()
#07 libkritaimage.so  KisTransactionData::redo()
#08 libkritacommand.so KUndo2Command::redoMergedCommands()
#09 libkritacommand.so KUndo2QStack::redo()
#10 libreverie_core.so ReverieCore::replayHistoryCommand()
#11 libreverie_core.so ReverieCore::redo()
Signal: SIGABRT  (abort ← libc ← commit+1120)
```

线上库是 strip 过的，但本机 `~/Projects/krita-source/build-android/lib/libkritaimage.so`
就是这一份的来源（`commit` 符号 = `0x259eec`，`0x259eec + 1120 = 0x25a34c`，与日志偏移
完全吻合）。反汇编 `0x25a34c` 前后：

```asm
25a328: mov  w0, #-1
25a32c: mov  x1, x20                  ; m_currentMemento
25a330: str  xzr, [x19, #0x100]       ; m_currentMemento = 0
25a334: bl   __aarch64_ldadd4_acq_rel ; refcount--
25a338: cmp  w0, #0x1                 ; 旧值 == 1 ⟹ 归零
25a33c: b.ne skip
25a340: ldr  x0, [x20, #0x10]         ; KisMemento::m_oldDefaultPixel
25a348: bl   _ZdaPv@plt               ; operator delete[]
```

`llvm-addr2line` 给出 `KisMemento::~KisMemento() (kis_memento.h:41)`，即
`kis_memento_manager.cc:206` 的 `m_currentMemento = 0;` 触发了析构，析构里
`delete[] m_oldDefaultPixel` 拿到的是**垃圾指针** → scudo 判定非法 free → abort。

**结论：不是并发、不是断言，是一个已经被释放的 `KisMemento` 被当成活的用了（UAF）。**

> 1.6.3 的三条同栈上报（`docs/INCIDENT-1.6.3-CRASHES.md` §3）也是这个，1.6.4 加的
> `barrierLock` / `waitForDone` 加固防的是**并发**，没有命中病因 —— 病因是**顺序**
> （历史状态错位），不是竞态。

## 2. 根因：canceled 列表里躺着一颗指向已释放 memento 的雷

### 2.1 引擎侧两个隐含假设

`KisMementoManager` 维护一份 `m_cancelledRevisions`（重做区），元素是

```cpp
struct KisHistoryItem { KisMemento *memento; KisMementoItemList itemList; };  // 裸指针
```

它假设：① 只有 `getMemento()`（每次开新事务）会整体清空它；② 它的顺序与
`KUndo2QStack` 的重做区一一对应。

`KisTransaction::revert()`（`libs/image/kis_transaction.h:110`）打破这两个假设：

```cpp
void revert() {
    m_transactionData->endTransaction();   // commit(): 本次瓦片变化作为一条 revision 进 m_revisions
    m_transactionData->undo();             // rollback(): takeLast() 把它挪进 m_cancelledRevisions
    delete m_transactionData;              // ~KisTransactionData → purgeHistory(memento)
}
```

而 `purgeHistory()` 用 `findRevisionByMemento()` **只查 `m_revisions`**；此刻该 memento
已经在 cancelled 里，查不到 → 直接 `return`。于是：

* cancelled 里留下一条 revision，其 `memento` 是裸指针；
* 这个 `KisMemento` 唯一的强引用随事务析构消失 → 对象被 delete；
* cancelled 队首从此指向**已释放内存**。

### 2.2 应用侧的引信：暂定事务

ReveriePaint 必须保留一批「不进撤销栈的本地事务」（预览/暂存回滚），它们都由
`ReverieCore` 直接 `new KisTransaction`：

| 位置 | 用途 |
|---|---|
| `ReverieCoreStroke.cpp` 首帧 flush | 直绘笔画的撤销事务（取消/换设备/回放前 revert） |
| `ReverieCoreTransform.cpp` `startTransformPreview()` | 变换预览（`cancelTransformPreview()` 逐条 `revert()`） |
| `ReverieCoreStroke.cpp` `restoreStashedStrokePatchOpen()` | 速创图形的暂存笔迹回滚 |
| `ReverieCoreMiscTools.cpp` `liquifyBegin()` | 液化预览 |

**创建**这类事务时，`KisTiledDataManager::getMemento()` 会
`m_cancelledRevisions.clear()` —— 引擎的重做信息当场没了，可 `KUndo2QStack` 的重做区
还留着命令（`getMemento` 不知道撤销栈的存在）。revert 之 后，那颗悬垂指针就落在
cancelled 的**队首**。

下一次 `redo()` 走到 `KisMementoManager::rollforward()`：

```cpp
if (!m_cancelledRevisions.size()) return;
KisHistoryItem changeList = m_cancelledRevisions.takeFirst();   // ← 不校验身份
KIS_SAFE_ASSERT_RECOVER_NOOP(changeList.memento == memento);    // Release 下只是一条 qWarning
...
m_currentMemento = changeList.memento;
commit();                                                       // ← delete[] 在这里炸
```

两个后果，正好对上用户的两句话：

1. 瓦片被「滚进」文档 → 屏幕上凭空出现内容 = **笔仙**；
2. 对已释放的 `KisMemento` 做 refcount + 析构 → **SIGABRT** = 闪退。

### 2.3 最小复现（四步）

① 画一笔 → ② `undo` → ③ 落笔后取消这一笔（= 建暂定事务 + `revert()`）
→ ④ `redo`。

用桌面 Krita 21.0.0（RelWithDebInfo，同一份源码）跑最小程序：

```
补丁前:  B: after redo  →  SIGSEGV
         #1 KisMemento::~KisMemento (kis_memento.h:40)
         #6 KisMementoManager::commit (kis_memento_manager.cc:206)
         #7 KisMementoManager::rollforward
         #10 KisTransactionData::redo
         #11 KUndo2QStack::redo
```

栈帧与线上日志逐级对应（线上是 scudo 把同一处非法 free 判成 abort，本机 glibc
直接 SIGSEGV）。把「变换预览」当暂定事务再跑一遍，还能看到**内容被装错**：

```
取消变换预览后: [0] a=255 [1] a=0 [2] a=0     ← 正确状态
redo #1 之后:  [0] a=0   [1] a=0 [2] a=0     ← 预览里"清空图层"的那一帧被滚进来了 = 笔仙
```

## 3. 修复

### 3.1 引擎（Krita fork `reverie-android`，`libs/image/tiles3/kis_memento_manager.cc`）

按 memento **身份**做校验，而不是盲目 `takeFirst()/takeLast()`：

1. `rollforward()`：目标必须是 `m_cancelledRevisions` 队首那条 revision，否则**保持现状**
   （这次重做退化成 no-op）并 `warnKrita` 留痕；
2. `rollback()`：只有 `m_revisions.last().memento == memento` 才回滚，否则保持现状；
3. `purgeHistory()`：顺手摘掉 cancelled 里指向该 memento 的条目，不再留悬垂指针。

效果：错位时宁可「什么都没发生」，也绝不把错的瓦片装进文档、绝不解引用已释放的
memento。双 ABI 已重编 + strip（`--strip-unneeded`，与仓库既有闭包同一流程），
导出符号 8628 / 7683 条与旧库**完全一致**，只多 ~1.8KB 代码。

### 3.2 应用（`ReverieCore`）

引擎兜底之外，从源头不让两边错位：**开每一个暂定事务之前**调
`ReverieCore::invalidateRedoState(reason)`：

* 丢掉 `KUndo2QStack` 的重做区（`purgeRedoState()`）并把 `m_redoCount` 清零 —— 引擎已经
  失效的重做，UI 也一起失效（`ReverieUndoStore::purgeRedoState()` 里补发
  `historyStateChanged()`，因为 `KUndo2QStack` 只发 `canRedoChanged`，UI 收不到）；
* 宏事务打开期间 `KUndo2QStack::purgeRedoState()` 是 no-op → 记 `m_redoPurgePending`，
  在 `endUndoMacro()` 里由 `flushPendingRedoPurge()` 补做；
* 13 个事务创建点全部覆盖（复核命令：`grep -rn "new KisTransaction" app/src/main/cpp`
  的每一处前面都必须有 `invalidateRedoState`）。

这样即使换回旧引擎库，也不会走到「重做区 vs cancelled 错位」这条路上；两者叠加后
引擎层再也不可能装错帧。

### 3.3 顺手修掉三处「事务提前 return」

排查时发现三个函数在**校验失败时直接 return**，而 `KisTransaction` 已经建好了：
`applyPerspectiveTransform`（`quadToQuad` 失败 / 不可逆）、`stampLayer`（贴图区与
画布无交集）、`moveLayerContent`（选区为空）。未提交的 `KisTransaction` 析构走
`purgeHistory(当前 memento)` → 先 `commit()` 出一条空 revision，再把这个 revision
**之前的所有 revision 全部丢掉**（`resetRevisionHistory` + `removeFirst`）—— 撤销栈里
N 条命令的瓦片快照被一次性清空，之后 undo 会拿错 revision 回滚，同样是「撤错帧」。
修法：把事务的开具挪到全部校验之后（零行为变更，只是顺序）。

## 4. 验证

| 项 | 结果 |
|---|---|
| 桌面最小复现（补丁前） | SIGSEGV / 内容装错，栈与线上逐帧一致 |
| 桌面最小复现（补丁后） | 三组场景全绿：取消笔画 + redo 不崩不画错；变换预览取消 + redo 内容不变 |
| 正常 undo/redo 回归 | 3 笔 → undo×3 → redo×3 → undo×2，逐帧像素与预期一致（补丁不影响正常路径） |
| `libreverie_core.so` | arm64-v8a / armeabi-v7a 均重编 + strip 刷新 |
| `libkritaimage.so` | 双 ABI 重编 + strip，导出符号与旧库完全一致 |
| `scripts/check_jni_symbols.sh` | 334/334 四份（两 ABI × third_party/jniLibs）全绿 |
| `scripts/check_native_symbols.sh` | arm64（115 个 .so）/ v7a（117 个 .so）闭包全解析 |
| `./gradlew assembleDebug` | 通过（打包闭包含新库） |
| 事务提前 return 三处 | 移到校验之后；重编后 undo/redo 行为不变 |

真机回归点（下次装机时按这个顺序过一遍）：

1. 画 3 笔 → 撤回 2 步 → 进变换工具 → 退出 → 点重做（不再出现"凭空内容"/闪退）；
2. 撤回 1 步 → 落笔画一条 → 双指撤回（取消该笔）→ 点重做；
3. 连续拖动撤回条来回扫（undo/redo 交替泵）20 次以上；
4. 速创图形：识别后预览/取消/提交各一次，再 undo/redo；
5. 液化预览取消 → redo。

## 5. 遗留

* 上游 Krita 的 `purgeHistory()` 不处理 `m_cancelledRevisions`、`rollforward()` 不校验
  memento 身份，是本轮事故的上游根因，值得给 KDE 提一个 bug（我们的补丁层已修）。
* `m_cancelledRevisions` 存裸指针是设计层面的问题，长期看应该改成 `KisMementoSP`；
  那是一次上游重构，本轮不做。
* 「暂定事务作废重做区」是行为变更：进变换工具就会让重做不可用（**此前也是这样**
  ——引擎的重做信息在开事务那一刻就被清空了，只是 UI 还亮着按钮）。现在 UI 与引擎一致。

## 附录 A：最小复现程序

编译（桌面 Krita 构建产物在 `build-desktop`）：

```bash
c++ @flags.rsp repro.cpp -o repro \
  -L$HOME/Projects/krita-source/build-desktop/bin -Wl,-rpath,$HOME/Projects/krita-source/build-desktop/bin \
  -lkritaimage -lkritaflake -lkritacommand -lkritapigment -lkritaglobal -lkritametadata \
  -lkritapsdutils -lkritaresources -lkritastore -lkritaplugin -lkritaversion -lkritamultiarch \
  -lkritawidgets -lkritawidgetutils -lkritaresourcewidgets \
  -lQt6Widgets -lQt6Gui -lQt6Core -lQt6Xml -lQt6Sql -lQt6Svg -lQt6Network -lQt6PrintSupport \
  -lQt6OpenGL -lQt6Core5Compat -Wl,--allow-shlib-undefined
# flags.rsp = build-desktop/compile_commands.json 里 kis_memento_manager.cc 那行的编译选项
```

```cpp
#include <QCoreApplication>
#include <KoColor.h>
#include <KoColorSpaceRegistry.h>
#include <kis_image.h>
#include <kis_paint_layer.h>
#include <kis_fill_painter.h>
#include <kis_transaction.h>
#include <kis_undo_stores.h>

int main(int argc, char **argv)
{
    QCoreApplication app(argc, argv);
    const KoColorSpace *cs = KoColorSpaceRegistry::instance()->rgb8();

    KisSurrogateUndoStore *store = new KisSurrogateUndoStore();
    KisImageSP image = new KisImage(store, 200, 200, cs, "repro");
    KisPaintLayerSP layer = new KisPaintLayer(image, "L1", OPACITY_OPAQUE_U8);
    image->addNode(layer, image->root());
    KisPaintDeviceSP dev = layer->paintDevice();

    // 一笔可撤销的"笔画"
    {
        KisTransaction txn(kundo2_noi18n(QStringLiteral("Stroke")), dev);
        KisFillPainter p(dev);
        p.fillRect(QRect(10, 10, 20, 20), KoColor(Qt::red, cs));
        p.end();
        txn.commit(image->undoAdapter());
    }

    store->undo();                              // ② 撤回一步: cancelled 里出现这个事务的 revision

    // ③ 落笔 -> 建暂定事务 (getMemento 清空 cancelled) -> 取消该笔 -> revert()
    KisTransaction *stroke = new KisTransaction(kundo2_noi18n(QStringLiteral("Stroke")), dev, nullptr, -1, nullptr);
    {
        KisFillPainter p(dev);
        p.fillRect(QRect(50, 10, 20, 20), KoColor(Qt::red, cs));
        p.end();
    }
    stroke->revert();
    delete stroke;

    store->redo();                              // ④ 崩: rollforward 拿悬垂 memento 当重做目标
    return 0;
}
```

修复后同一程序返回 0（redo 变成安全的 no-op），文档里的场景 A/B/C 输出见 §2.3 / §4。

## 附录 B：线上日志关键片段

```
App Version: 1.6.4
Signal: SIGABRT (Abort program) (code=18446744073709551615)
Crashed Thread PID: 17669, TID: 17704
#02 .../libc.so  (abort+168)
#04 .../libkritaimage.so  (_ZN17KisMementoManager6commitEv+1120)
#05 .../libkritaimage.so  (_ZN17KisMementoManager11rollforwardEP23KisTileHashTableTraits2I7KisTileE12KisSharedPtrI10KisMementoE+960)
#07 .../libkritaimage.so  (_ZN18KisTransactionData4redoEv+404)
#08 .../libkritacommand.so (_ZN13KUndo2Command18redoMergedCommandsEv+256)
#09 .../libkritacommand.so (_ZN12KUndo2QStack4redoEv+76)
#10 .../libreverie_core.so (_ZN11ReverieCore20replayHistoryCommandEb+232)
#11 .../libreverie_core.so (_ZN11ReverieCore4redoEv+648)
```
