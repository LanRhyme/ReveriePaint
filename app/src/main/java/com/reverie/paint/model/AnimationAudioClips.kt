/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** 时间轴上的一段音频: 资源名 + 起始帧 + 时长(帧)。 */
data class AudioClip(
    val name: String,
    val startFrame: Int,
    val durationFrames: Int,
)

/**
 * 音频片段的位置元数据。
 *
 * 音频字节本身仍以原始资源名存进 .revp 的 assets/, 这里只记录"从第几帧开始、占多少帧"。
 * 资源键带 `reverie.anim.` 前缀, 会被 [AnimationLayerOps.isMarkerAsset] 过滤, 不会被当成
 * 音频资源本身。
 */
object AnimationAudioClips {
    const val ASSET = "reverie.anim.audio_clips"

    /** 载入时缺少时长信息 (旧工程只有音频字节) 的兜底秒数。 */
    const val FALLBACK_SECONDS = 4
    const val MAX_TIMELINE_FRAMES = 24 * 60 * 60 * 240

    fun encode(clips: List<AudioClip>): ByteArray =
        clips.joinToString("\n") { "${it.name}\t${it.startFrame}\t${it.durationFrames}" }.toByteArray()

    fun decode(text: String): List<AudioClip> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val name = parts[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val start = parts[1].toIntOrNull()?.takeIf { it in 0..MAX_TIMELINE_FRAMES } ?: return@mapNotNull null
        val duration = parts[2].toIntOrNull()?.takeIf { it in 1..MAX_TIMELINE_FRAMES } ?: return@mapNotNull null
        AudioClip(name, start, duration)
    }.toList()

    /**
     * 用资源表里的音频名对齐片段列表: 保留已有位置, 新资源按第 0 帧补齐,
     * 文件已被删掉的片段丢弃。顺序跟随资源表, 保证 UI 稳定。
     */
    fun reconcile(clips: List<AudioClip>, names: List<String>, fps: Int): List<AudioClip> {
        val byName = clips.associateBy { it.name }
        val fallback = (fps.coerceIn(1, 240) * FALLBACK_SECONDS).coerceAtLeast(1)
        return names.map { name -> byName[name] ?: AudioClip(name, 0, fallback) }
    }

    fun withStart(clips: List<AudioClip>, name: String, startFrame: Int): List<AudioClip> =
        clips.map { if (it.name == name) it.copy(startFrame = startFrame.coerceAtLeast(0)) else it }

    fun without(clips: List<AudioClip>, name: String): List<AudioClip> = clips.filterNot { it.name == name }
}
