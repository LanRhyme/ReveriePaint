/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * 零拷贝/O(1) 堆内存的 ABR 随机访问数据源抽象。
 *
 * 使用 Long 偏移量突破 ByteArray 2GB 与单块分配限制，屏蔽底层是：
 * 1. [ByteArraySource]（单测小样本与内存数组）
 * 2. [MappedFileSource]（64位虚拟内存页缓存零拷贝映射）
 * 3. [PagedChannelSource]（超大文件 / 32位机型 / mmap 失败时的 64KB 滑动页框 pread 兜底）
 */
internal interface AbrSource : Closeable {
    val size: Long

    fun get(pos: Long): Byte
    fun u8(pos: Long): Int = get(pos).toInt() and 0xFF
    fun u16(pos: Long): Int
    fun i16(pos: Long): Short
    fun i32(pos: Long): Int
    fun u32(pos: Long): Long = i32(pos).toLong() and 0xFFFFFFFFL
    fun i64(pos: Long): Long
    fun f64(pos: Long): Double = Double.fromBits(i64(pos))

    /** 将 [pos, pos + len) 区间拷贝到目标数组 [dst] 的 [dstOffset] 处 */
    fun copyTo(pos: Long, dst: ByteArray, dstOffset: Int = 0, len: Int = dst.size - dstOffset)

    /** 仅在按需提取单个笔尖/字符串时分配小数组 */
    fun sliceBytes(pos: Long, len: Int): ByteArray {
        if (len < 0 || pos < 0L || pos + len > size) {
            throw EOFException("sliceBytes out of bounds: pos=$pos len=$len size=$size")
        }
        if (len == 0) return ByteArray(0)
        return ByteArray(len).also { copyTo(pos, it, 0, len) }
    }

    fun readString(pos: Long, byteLen: Int, charset: Charset): String {
        if (byteLen <= 0 || pos < 0L || pos + byteLen > size) return ""
        return String(sliceBytes(pos, byteLen), charset)
    }

    override fun close() {}

    companion object {
        /** 超过 256MB 或 32 位进程时优先走 PagedChannelSource，避免 32 位虚拟地址空间碎片及 Cleaner 延迟 */
        private const val MMAP_THRESHOLD_BYTES = 256L * 1024L * 1024L

        fun fromBytes(bytes: ByteArray): AbrSource = ByteArraySource(bytes)

        /**
         * 打开文件通道并根据文件大小与 ABI 智能选择 Mmap 或 64KB 页缓存通道。
         * 若 [channel] 已被外部持有，调用 [close] 时会一并安全释放映射与通道。
         */
        fun fromChannel(channel: FileChannel, preferMmap: Boolean = true): AbrSource {
            val fileSize = channel.size()
            val is64Bit = is64BitProcess()
            if (preferMmap && is64Bit && fileSize in 1..MMAP_THRESHOLD_BYTES) {
                runCatching {
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, fileSize)
                    mapped.order(ByteOrder.BIG_ENDIAN)
                    return MappedFileSource(mapped, channel, fileSize)
                }.onFailure { e ->
                    // mmap 失败（如虚拟内存受限或文件系统不支持）静默降级为 PagedChannelSource
                    runCatching { android.util.Log.w("AbrSource", "mmap fallback to PagedChannelSource: ${e.message}") }
                }
            }
            return PagedChannelSource(channel, fileSize)
        }

        fun fromFile(file: File, preferMmap: Boolean = true): AbrSource {
            val raf = RandomAccessFile(file, "r")
            return try {
                fromChannel(raf.channel, preferMmap)
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw t
            }
        }

        private fun is64BitProcess(): Boolean {
            return runCatching {
                android.os.Process.is64Bit()
            }.getOrElse {
                System.getProperty("os.arch")?.contains("64") == true
            }
        }
    }
}

/**
 * 内存数组实现（供既有单元测试与小块测试零开销调用）
 */
internal class ByteArraySource(private val bytes: ByteArray) : AbrSource {
    override val size: Long get() = bytes.size.toLong()

    private fun checkBounds(pos: Long, n: Int): Int {
        if (pos < 0L || n < 0 || pos + n > bytes.size) {
            throw EOFException("ByteArraySource EOF: pos=$pos need=$n size=${bytes.size}")
        }
        return pos.toInt()
    }

    override fun get(pos: Long): Byte = bytes[checkBounds(pos, 1)]

    override fun u16(pos: Long): Int {
        val i = checkBounds(pos, 2)
        return ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
    }

    override fun i16(pos: Long): Short = u16(pos).toShort()

    override fun i32(pos: Long): Int {
        val i = checkBounds(pos, 4)
        return ((bytes[i].toInt() and 0xFF) shl 24) or
            ((bytes[i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[i + 2].toInt() and 0xFF) shl 8) or
            (bytes[i + 3].toInt() and 0xFF)
    }

    override fun i64(pos: Long): Long {
        val i = checkBounds(pos, 8)
        return ((bytes[i].toLong() and 0xFFL) shl 56) or
            ((bytes[i + 1].toLong() and 0xFFL) shl 48) or
            ((bytes[i + 2].toLong() and 0xFFL) shl 40) or
            ((bytes[i + 3].toLong() and 0xFFL) shl 32) or
            ((bytes[i + 4].toLong() and 0xFFL) shl 24) or
            ((bytes[i + 5].toLong() and 0xFFL) shl 16) or
            ((bytes[i + 6].toLong() and 0xFFL) shl 8) or
            (bytes[i + 7].toLong() and 0xFFL)
    }

    override fun copyTo(pos: Long, dst: ByteArray, dstOffset: Int, len: Int) {
        if (len == 0) return
        val i = checkBounds(pos, len)
        System.arraycopy(bytes, i, dst, dstOffset, len)
    }
}

/**
 * MappedByteBuffer 零拷贝虚拟内存实现（支持显式 unmap 释放文件锁）
 */
internal class MappedFileSource(
    private var buffer: MappedByteBuffer?,
    private val channel: FileChannel,
    override val size: Long,
) : AbrSource {

    private fun buf(pos: Long, n: Int): MappedByteBuffer {
        val b = buffer ?: throw IllegalStateException("MappedFileSource already closed")
        if (pos < 0L || n < 0 || pos + n > size) {
            throw EOFException("MappedFileSource EOF: pos=$pos need=$n size=$size")
        }
        return b
    }

    override fun get(pos: Long): Byte = buf(pos, 1).get(pos.toInt())
    override fun u16(pos: Long): Int = buf(pos, 2).getShort(pos.toInt()).toInt() and 0xFFFF
    override fun i16(pos: Long): Short = buf(pos, 2).getShort(pos.toInt())
    override fun i32(pos: Long): Int = buf(pos, 4).getInt(pos.toInt())
    override fun i64(pos: Long): Long = buf(pos, 8).getLong(pos.toInt())

    override fun copyTo(pos: Long, dst: ByteArray, dstOffset: Int, len: Int) {
        if (len == 0) return
        val b = buf(pos, len)
        val dup = b.duplicate()
        dup.position(pos.toInt())
        dup.limit(pos.toInt() + len)
        dup.get(dst, dstOffset, len)
    }

    override fun close() {
        val b = buffer
        buffer = null
        try {
            if (b != null) {
                DirectBufferUnmapper.unmap(b)
            }
        } finally {
            runCatching { channel.close() }
        }
    }
}

/**
 * 64KB 滑动页缓存 FileChannel 实现：
 * - Java 堆占用恒定为 64KB（pageBuf）；
 * - 支持任意 >2GB 文件（全 Long 寻址）；
 * - 无任何 DirectByteBuffer / mmap 句柄残留，Windows 单测与 Android 真机 100% 立即删除临时文件。
 */
internal class PagedChannelSource(
    private val channel: FileChannel,
    override val size: Long,
    pageSize: Int = 64 * 1024,
) : AbrSource {
    private val pageBuf = ByteArray(pageSize)
    private val byteBuf = ByteBuffer.wrap(pageBuf).order(ByteOrder.BIG_ENDIAN)
    private var pageStart: Long = -1L
    private var pageValidLen: Int = 0

    private fun checkBounds(pos: Long, n: Int) {
        if (pos < 0L || n < 0 || pos + n > size) {
            throw EOFException("PagedChannelSource EOF: pos=$pos need=$n size=$size")
        }
    }

    /** 确保 [pos, pos + n) 落在当前缓存页内（n <= 8） */
    private fun ensureWindow(pos: Long, n: Int): Int {
        checkBounds(pos, n)
        if (pageStart >= 0L && pos >= pageStart && (pos + n) <= (pageStart + pageValidLen)) {
            return (pos - pageStart).toInt()
        }
        val alignedStart = (pos and -4096L).let { if (pos + n <= it + pageBuf.size) it else pos }
        byteBuf.clear()
        var totalRead = 0
        val maxRead = minOf(pageBuf.size.toLong(), size - alignedStart).toInt()
        byteBuf.limit(maxRead)
        while (totalRead < maxRead) {
            val r = channel.read(byteBuf, alignedStart + totalRead)
            if (r <= 0) break
            totalRead += r
        }
        if (alignedStart + totalRead < pos + n) {
            throw EOFException("Unexpected short read from FileChannel at $pos")
        }
        pageStart = alignedStart
        pageValidLen = totalRead
        return (pos - pageStart).toInt()
    }

    override fun get(pos: Long): Byte = pageBuf[ensureWindow(pos, 1)]
    override fun u16(pos: Long): Int = byteBuf.getShort(ensureWindow(pos, 2)).toInt() and 0xFFFF
    override fun i16(pos: Long): Short = byteBuf.getShort(ensureWindow(pos, 2))
    override fun i32(pos: Long): Int = byteBuf.getInt(ensureWindow(pos, 4))
    override fun i64(pos: Long): Long = byteBuf.getLong(ensureWindow(pos, 8))

    override fun copyTo(pos: Long, dst: ByteArray, dstOffset: Int, len: Int) {
        if (len == 0) return
        checkBounds(pos, len)
        if (pageStart >= 0L && pos >= pageStart && (pos + len) <= (pageStart + pageValidLen)) {
            System.arraycopy(pageBuf, (pos - pageStart).toInt(), dst, dstOffset, len)
            return
        }
        val targetBuf = ByteBuffer.wrap(dst, dstOffset, len)
        var readSoFar = 0
        while (readSoFar < len) {
            val r = channel.read(targetBuf, pos + readSoFar)
            if (r <= 0) throw EOFException("Unexpected EOF during bulk read at ${pos + readSoFar}")
            readSoFar += r
        }
    }

    override fun close() {
        runCatching { channel.close() }
    }
}

/**
 * 跨 Android (API 23~36 Libcore) 与桌面 JVM (JDK 9~21) 的 DirectByteBuffer 显式释放工具。
 */
internal object DirectBufferUnmapper {
    private fun interface Strategy {
        fun unmap(buffer: ByteBuffer): Boolean
    }

    private val strategy: Strategy by lazy { resolveStrategy() }

    fun unmap(buffer: ByteBuffer?): Boolean {
        if (buffer == null || !buffer.isDirect) return false
        return runCatching { strategy.unmap(buffer) }.getOrDefault(false)
    }

    private fun resolveStrategy(): Strategy {
        // 路径 1：JDK 9+ / JDK 17/21 (本地单测环境) sun.misc.Unsafe.invokeCleaner(ByteBuffer)
        runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val theUnsafe = theUnsafeField.get(null)
            val invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer::class.java)
            return object : Strategy {
                override fun unmap(buffer: ByteBuffer): Boolean {
                    invokeCleaner.invoke(theUnsafe, buffer)
                    return true
                }
            }
        }

        // 路径 2：Android ART (Libcore java.nio.DirectByteBuffer.cleaner().clean() 或 free())
        runCatching {
            return object : Strategy {
                @Volatile private var cleanerMethod: Method? = null
                @Volatile private var cleanMethod: Method? = null
                @Volatile private var freeMethod: Method? = null
                @Volatile private var initialized = false

                override fun unmap(buffer: ByteBuffer): Boolean {
                    if (!initialized) {
                        synchronized(this) {
                            if (!initialized) {
                                runCatching {
                                    val cm = buffer.javaClass.getMethod("cleaner").apply { isAccessible = true }
                                    cleanerMethod = cm
                                }
                                runCatching {
                                    val fm = buffer.javaClass.getDeclaredMethod("free").apply { isAccessible = true }
                                    freeMethod = fm
                                }
                                initialized = true
                            }
                        }
                    }
                    cleanerMethod?.let { cm ->
                        val cleaner = cm.invoke(buffer) ?: return true
                        val clean = cleanMethod ?: cleaner.javaClass.getMethod("clean").apply {
                            isAccessible = true
                            cleanMethod = this
                        }
                        clean.invoke(cleaner)
                        return true
                    }
                    freeMethod?.let { fm ->
                        fm.invoke(buffer)
                        return true
                    }
                    return false
                }
            }
        }

        return object : Strategy {
            override fun unmap(buffer: ByteBuffer): Boolean = false
        }
    }
}
