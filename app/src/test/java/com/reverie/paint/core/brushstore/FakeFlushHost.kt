/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushStoreVm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal class FakeFlushHost(
    private val engine: BrushApply.EngineFacade,
    private val indexOf: (String) -> Int,
    @Volatile var active: String?,
) : BrushStoreVm.FlushHost {
    val reloads: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    /** 测试里模拟"笔画进行中" */
    @Volatile var stroking: Boolean = false
    override fun strokeActive(): Boolean = stroking
    override fun activeId(): String? = active
    override fun reload(id: String) {
        reloads += id
        engine.loadBrushPreset(indexOf(id))
    }
    override fun notifyBrushModified() {}
}
