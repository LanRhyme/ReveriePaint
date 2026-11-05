package com.reverie.paint.core

/**
 * 光标环笔刷轮廓的查询入口（v53）。
 *
 * 原生 `currentBrushOutline()` 读 `m_brushPreset`，而 `loadBrushPreset` 在渲染线程上整体换掉它，
 * 两边没有任何共用的锁（原生 `m_presetInfoMutex` 只护元数据缓存）。以前轮廓在主线程查，
 * 预设在渲染线程换，交叠即读到已释放的预设（v52 平板实测 SIGSEGV）。
 * 现在轮廓的原生查询一律排到渲染线程——和 `loadBrushPreset` 同一个单线程队列，天然串行，不新增锁。
 */
internal object BrushOutlineFetch {
    /** 原生约定: [polyCount, (ptCount, x0, y0, ...)*]。无轮廓返回 null。 */
    fun parse(raw: FloatArray?): List<FloatArray>? {
        if (raw == null || raw.isEmpty()) return null
        val numPolys = raw[0].toInt()
        if (numPolys <= 0) return null
        val list = ArrayList<FloatArray>(numPolys)
        var idx = 1
        for (p in 0 until numPolys) {
            if (idx >= raw.size) break
            val count = raw[idx++].toInt()
            if (count > 0 && idx + count * 2 <= raw.size) {
                val pts = FloatArray(count * 2)
                System.arraycopy(raw, idx, pts, 0, count * 2)
                list.add(pts)
                idx += count * 2
            }
        }
        return if (list.isNotEmpty()) list else null
    }

    /**
     * [onRenderThread]：调用方已在渲染线程（runCore 的 op 里）就地查询；否则排到渲染线程。
     * [postRender] 为 null 表示没有渲染线程（无头/单测），退回调用线程同步查询（旧行为）。
     * 结果经 [postMain] 回主线程交给 [apply]。
     */
    fun fetch(
        onRenderThread: Boolean,
        postRender: ((() -> Unit) -> Unit)?,
        postMain: (() -> Unit) -> Unit,
        query: () -> FloatArray?,
        apply: (List<FloatArray>?) -> Unit,
    ) {
        fun run(deliver: ((List<FloatArray>?) -> Unit)) {
            val polys = try {
                parse(query())
            } catch (_: Throwable) {
                null
            }
            deliver(polys)
        }
        when {
            postRender == null -> run(apply)
            onRenderThread -> run { polys -> postMain { apply(polys) } }
            else -> postRender { run { polys -> postMain { apply(polys) } } }
        }
    }
}
