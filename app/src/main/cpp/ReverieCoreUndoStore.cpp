/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCoreUndoStore.h"

#include <kundo2stack.h>

ReverieUndoStore::ReverieUndoStore()
    : m_undoStack(new KUndo2Stack)
{
    // 与 KisSurrogateUndoStore 一致: 直连转发避免信号排队 (BUG:447985)
    connect(m_undoStack, SIGNAL(indexChanged(int)), this, SIGNAL(historyStateChanged()), Qt::DirectConnection);
}

ReverieUndoStore::~ReverieUndoStore()
{
    // 析构前断开信号, 避免 indexChanged 在销毁途中继续转发
    disconnect(m_undoStack, SIGNAL(indexChanged(int)), this, SIGNAL(historyStateChanged()));
    delete m_undoStack;
}

const KUndo2Command* ReverieUndoStore::presentCommand()
{
    return m_undoStack->command(m_undoStack->index() - 1);
}

void ReverieUndoStore::undoLastCommand()
{
    m_undoStack->undo();
}

void ReverieUndoStore::addCommand(KUndo2Command *cmd)
{
    if (!cmd) return;
    m_undoStack->push(cmd);
}

void ReverieUndoStore::beginMacro(const KUndo2MagicString& macroName)
{
    m_undoStack->beginMacro(macroName);
    ++m_macroDepth;
}

void ReverieUndoStore::endMacro()
{
    const bool closingOutermost = (m_macroDepth == 1);
    if (m_macroDepth > 0) {
        --m_macroDepth;
    }
    m_undoStack->endMacro();
    if (!closingOutermost) {
        return;
    }
    // s13-C5: 最外层宏刚关掉, 而它一条子命令都没有 —— 典型是对称笔画被撤销/重做/取消中止
    // (笔触回滚不产生命令) 或落笔被图层检查拒绝。KUndo2QStack::endMacro 仍会把这个空宏当成
    // 一个撤销步压栈, 用户下一次撤销"什么都没发生"。这里把它撤掉并从栈里清除。
    // 安全性: beginMacro 已清掉 redo 区 (kundo2stack.cpp beginMacro), 所以 index()==count();
    // 空命令的 undo 不触碰任何图层; purgeRedoState 只删除这一个空宏, clean 索引规则与原生一致。
    const int idx = m_undoStack->index();
    if (idx <= 0 || idx != m_undoStack->count() || !m_undoStack->canUndo()) {
        return;
    }
    const KUndo2Command *top = m_undoStack->command(idx - 1);
    if (!top || top->childCount() != 0) {
        return;
    }
    m_undoStack->undo();
    m_undoStack->purgeRedoState();
}

void ReverieUndoStore::purgeRedoState()
{
    m_undoStack->purgeRedoState();
}

void ReverieUndoStore::undo()
{
    if (m_undoStack && m_undoStack->canUndo()) {
        m_undoStack->undo();
    }
}

void ReverieUndoStore::redo()
{
    if (m_undoStack && m_undoStack->canRedo()) {
        m_undoStack->redo();
    }
}

void ReverieUndoStore::clear()
{
    m_undoStack->clear();
}

void ReverieUndoStore::setUndoLimit(int limit)
{
    m_undoStack->setUndoLimit(limit);
}

bool ReverieUndoStore::canUndo() const
{
    return m_undoStack && m_undoStack->canUndo();
}

bool ReverieUndoStore::canRedo() const
{
    return m_undoStack && m_undoStack->canRedo();
}

int ReverieUndoStore::count() const
{
    return m_undoStack ? m_undoStack->count() : 0;
}

int ReverieUndoStore::index() const
{
    return m_undoStack ? m_undoStack->index() : 0;
}
