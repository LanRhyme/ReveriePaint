package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v53：ABR 导入后一次性原生崩溃（getCurrentBrushOutline 读到被换掉的预设）。
 * JVM 里没有原生预设，这里用「加载期间不许被查询」的替身复现同一个并发形状：
 * 渲染线程在换预设（loadBrushPreset），主线程在查轮廓。覆盖的是 Kotlin 侧的线程不变量，
 * 不覆盖原生内存本身；原生侧的 use-after-free 只能在真机上验证。
 */
class BrushOutlineFetchTest {
    private class FakeNative {
        val loading = AtomicBoolean(false)
        val overlaps = AtomicInteger(0)
        @Volatile var queryThread: Thread? = null
        fun load(ms: Long) {
            loading.set(true)
            Thread.sleep(ms)
            loading.set(false)
        }
        fun query(): FloatArray? {
            queryThread = Thread.currentThread()
            if (loading.get()) overlaps.incrementAndGet()
            return floatArrayOf(1f, 3f, 0f, 0f, 1f, 0f, 0f, 1f)
        }
    }

    private fun newThread(name: String) = Executors.newSingleThreadExecutor { r -> Thread(r, name) }

    @Test
    fun `主线程发起的轮廓查询不会和渲染线程上正在进行的预设加载交叠`() {
        val render = newThread("render")
        val main = newThread("main")
        try {
            repeat(10) {
                val n = FakeNative()
                val applied = CountDownLatch(1)
                var got: List<FloatArray>? = null
                // 渲染线程正在换预设（第二次加载），主线程这时发起轮廓查询
                render.execute { n.load(60) }
                Thread.sleep(10)
                main.submit {
                    BrushOutlineFetch.fetch(
                        onRenderThread = false,
                        postRender = { op -> render.execute { op() } },
                        postMain = { op -> main.execute { op() } },
                        query = { n.query() },
                        apply = { got = it; applied.countDown() },
                    )
                }.get()
                assertTrue("轮廓没有回到主线程", applied.await(2, TimeUnit.SECONDS))
                assertEquals("轮廓查询与预设加载在不同线程上同时进行", 0, n.overlaps.get())
                assertNotNull(got)
                assertEquals("查询必须在渲染线程上执行", "render", n.queryThread?.name)
            }
        } finally {
            render.shutdownNow(); main.shutdownNow()
        }
    }

    @Test
    fun `已在渲染线程上就地查询 结果交回主线程应用`() {
        val render = newThread("render")
        val main = newThread("main")
        try {
            val n = FakeNative()
            val applied = CountDownLatch(1)
            var applyThread: String? = null
            render.execute {
                BrushOutlineFetch.fetch(
                    onRenderThread = true,
                    postRender = { op -> render.execute { op() } },
                    postMain = { op -> main.execute { op() } },
                    query = { n.query() },
                    apply = { applyThread = Thread.currentThread().name; applied.countDown() },
                )
            }
            assertTrue(applied.await(2, TimeUnit.SECONDS))
            assertEquals("render", n.queryThread?.name)
            assertEquals("main", applyThread)
        } finally {
            render.shutdownNow(); main.shutdownNow()
        }
    }

    @Test
    fun `没有渲染线程时（无头）退回调用线程同步查询 行为不变`() {
        val n = FakeNative()
        var got: List<FloatArray>? = null
        BrushOutlineFetch.fetch(false, null, { it() }, { n.query() }, { got = it })
        assertNotNull(got)
        assertEquals(Thread.currentThread(), n.queryThread)
    }

    @Test
    fun `查询抛异常或没有轮廓都得到 null 不外泄异常`() {
        var r1: List<FloatArray>? = listOf(floatArrayOf(1f))
        BrushOutlineFetch.fetch(false, null, { it() }, { error("native") }, { r1 = it })
        assertNull(r1)
        var r2: List<FloatArray>? = listOf(floatArrayOf(1f))
        BrushOutlineFetch.fetch(false, null, { it() }, { null }, { r2 = it })
        assertNull(r2)
        assertNull(BrushOutlineFetch.parse(floatArrayOf(0f)))
        assertNull(BrushOutlineFetch.parse(floatArrayOf(1f, 5f, 0f, 0f)))
        val p = BrushOutlineFetch.parse(floatArrayOf(2f, 2f, 0f, 0f, 1f, 1f, 1f, 5f, 5f))
        assertEquals(2, p?.size)
        assertEquals(4, p?.get(0)?.size)
    }
}
