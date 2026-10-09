/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef REVERIE_CORE_UNDO_STORE_H
#define REVERIE_CORE_UNDO_STORE_H

#include <kis_undo_store.h>

class KUndo2Stack;

/**
 * KisSurrogateUndoStore 的等价实现 + 可配置的撤销历史上限。
 *
 * Krita 自带的 KisSurrogateUndoStore 不暴露内部 KUndo2Stack, 无法设置
 * undoLimit; 长绘画会话中每笔画的 KisTransaction 命令(tile 级快照)会在
 * 栈内无限堆积, 内存随笔画数线性增长(4K 画布上一笔大笔画可占用数 MB
 * 快照), 是"越画越卡"的主因之一。
 *
 * 这里复刻 KisSurrogateUndoStore 的全部行为, 并透出 setUndoLimit:
 * 超过上限的命令由 KUndo2Stack 在 push 时从栈底自动删除并释放
 * (kundo2stack.cpp checkUndoLimit), undo/redo/macro 语义不变。
 * 所有权仍归 KisImage (setUndoStore), 与原实现一致, ReverieCore 不 delete。
 */
class ReverieUndoStore : public KisUndoStore
{
public:
    ReverieUndoStore();
    ~ReverieUndoStore() override;

    const KUndo2Command* presentCommand() override;
    void undoLastCommand() override;
    void addCommand(KUndo2Command *cmd) override;
    void beginMacro(const KUndo2MagicString& macroName) override;
    void endMacro() override;
    void purgeRedoState() override;

    // KisSurrogateUndoStore 自有的非接口方法 (ReverieCore 直接调用)
    void undo();
    void redo();
    void clear();
    void setUndoLimit(int limit);
    bool canUndo() const;
    bool canRedo() const;
    int count() const;
    int index() const;

private:
    KUndo2Stack *m_undoStack;
};

#endif // REVERIE_CORE_UNDO_STORE_H
