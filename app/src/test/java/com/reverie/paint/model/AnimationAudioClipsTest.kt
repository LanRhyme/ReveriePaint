/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationAudioClipsTest {
    @Test
    fun `位置元数据可编解码并忽略损坏条目`() {
        val clips = listOf(
            AudioClip("a.mp3", 12, 48),
            AudioClip("b.wav", 90, 120),
        )

        assertEquals(clips, AnimationAudioClips.decode(AnimationAudioClips.encode(clips).decodeToString()))
        assertEquals(
            listOf(AudioClip("valid.mp3", 3, 9)),
            AnimationAudioClips.decode("broken\nvalid.mp3\t3\t9\nempty\t-1\t2"),
        )
    }

    @Test
    fun `对齐工程资源时保留已有位置并给旧音频补默认长度`() {
        val result = AnimationAudioClips.reconcile(
            clips = listOf(AudioClip("kept.mp3", 24, 60), AudioClip("gone.mp3", 99, 99)),
            names = listOf("kept.mp3", "legacy.wav"),
            fps = 12,
        )

        assertEquals(
            listOf(AudioClip("kept.mp3", 24, 60), AudioClip("legacy.wav", 0, 48)),
            result,
        )
    }

    @Test
    fun `拖动只改变指定音频并且起始帧不小于零`() {
        val original = listOf(AudioClip("a.mp3", 10, 20), AudioClip("b.wav", 40, 30))

        assertEquals(
            listOf(AudioClip("a.mp3", 0, 20), AudioClip("b.wav", 40, 30)),
            AnimationAudioClips.withStart(original, "a.mp3", -4),
        )
        assertEquals(listOf(original.last()), AnimationAudioClips.without(original, "a.mp3"))
    }
}
