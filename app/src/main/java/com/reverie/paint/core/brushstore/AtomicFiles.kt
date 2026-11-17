/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 笔刷存储的原子写：先写同目录 .tmp 再 rename，避免进程被杀时留下半个文件 */
object AtomicFiles {
    private const val TAG = "AtomicFiles"
    private val writeLock = Any()
    private val pendingWrites = ConcurrentHashMap<File, ByteArray>()
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Reverie-AtomicFiles-IO").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun write(target: File, bytes: ByteArray) {
        val canonicalTarget = target.canonicalFile
        synchronized(writeLock) {
            canonicalTarget.parentFile?.mkdirs()
            val tmp = File(canonicalTarget.parentFile, canonicalTarget.name + ".tmp")
            tmp.outputStream().use {
                it.write(bytes)
                it.fd.sync()
            }
            if (canonicalTarget.exists() && !canonicalTarget.canWrite()) canonicalTarget.setWritable(true)
            if (!tmp.renameTo(canonicalTarget)) {
                // 部分文件系统 rename 不覆盖已存在目标
                canonicalTarget.delete()
                if (!tmp.renameTo(canonicalTarget)) {
                    tmp.delete()
                    throw IOException("atomic rename failed: $canonicalTarget")
                }
            }
        }
    }

    fun writeText(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))

    /**
     * 异步合并写：将落盘移至后台 I/O 线程执行，并在高频调用时合并写入最新内容
     * 避免在 UI 线程同步执行 fd.sync 导致 ANR (签名 f8c16b08bb, 6b84f7efe5)
     */
    fun writeAsync(target: File, bytes: ByteArray) {
        val canonical = target.canonicalFile
        pendingWrites[canonical] = bytes
        ioExecutor.execute {
            val bytesToWrite = pendingWrites.remove(canonical) ?: return@execute
            try {
                write(canonical, bytesToWrite)
            } catch (t: Throwable) {
                Log.e(TAG, "Async write failed for $canonical", t)
            }
        }
    }

    fun writeTextAsync(target: File, text: String) {
        writeAsync(target, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 排空并同步等待所有待写入的原子文件落盘 (在进程退出/崩溃抢救/备份时调用)
     */
    fun flushPending() {
        val latch = CountDownLatch(1)
        ioExecutor.execute {
            latch.countDown()
        }
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                drainRemainingSynchronously()
            }
        } catch (_: InterruptedException) {
            drainRemainingSynchronously()
        }
    }

    private fun drainRemainingSynchronously() {
        for ((file, bytes) in pendingWrites) {
            try {
                write(file, bytes)
            } catch (t: Throwable) {
                Log.e(TAG, "Synchronous drain failed for $file", t)
            }
        }
        pendingWrites.clear()
    }
}
