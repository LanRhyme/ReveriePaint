/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Procedural "paper friction" writing sound engine.
 *
 * Synthesizes a continuous pencil/pen-on-paper noise texture in real time:
 * xorshift white noise -> one-pole IIR low-pass -> speed-modulated gain envelope,
 * streamed through a 16 kHz mono AudioTrack. No audio asset files are required.
 *
 * Latency design (rewritten after first on-device test felt laggy):
 * - While the engine is enabled the track stays primed: the worker thread keeps
 *   writing (near-)silent blocks so the AudioFlinger pipeline never goes cold.
 *   Stroke start only flips the gain target - no play() round-trip on the
 *   audio path, so the perceived onset is ~1 buffer (32 ms) + hardware latency.
 * - Asymmetric envelope: fast attack (0.85/block ≈ 60 ms to full) so the sound
 *   is audible almost immediately, slow release (0.25/block) for a natural tail.
 *
 * Threading & allocation contract (see AGENTS.md §4):
 * - Touch hot path ([startStroke]/[updateStroke]/[stopStroke]) only writes a few
 *   volatile fields and wakes the worker thread - zero allocation, non-blocking.
 * - All PCM buffers are preallocated once; the worker thread uses the blocking
 *   AudioTrack.write as its own metronome (no sleep loops, no garbage).
 */
class PaperSoundEngine {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_FRAMES = 512 // 32 ms per block
        private const val TICK_FRAMES = 576 // 36 ms soft-touch tick
        private const val ATTACK = 0.85f // fast rise: audible within 1-2 blocks
        private const val RELEASE = 0.25f // smooth tail after pen lift
        private const val SILENCE_GAIN = 0.002f // below this the primed loop writes zeros
    }

    /** Per-type synthesis profile: low-pass cutoff and master gain. */
    private class Profile(val cutoffHz: Float, val masterGain: Float) {
        val lowPassAlpha: Float = 1f - Math.exp((-2.0 * Math.PI * cutoffHz / SAMPLE_RATE)).toFloat()
    }

    private val profilePencil = Profile(cutoffHz = 4500f, masterGain = 0.9f) // 明亮宽带沙沙
    private val profileInk = Profile(cutoffHz = 2600f, masterGain = 1.0f) // 窄带清脆划纸
    private val profileTick = Profile(cutoffHz = 3200f, masterGain = 0.9f)

    // ---- configuration mirrors (written from UI thread, read on worker) ----
    @Volatile private var enabled = false
    @Volatile private var volume = 0.6f
    @Volatile private var profile: Profile = profilePencil
    @Volatile private var isTickType = false

    // ---- live stroke state (hot path: plain volatile writes only) ----
    @Volatile private var writing = false
    @Volatile private var pendingTick = false
    @Volatile private var speedGain = 0.5f // raw stroke-speed response 0..1
    @Volatile private var eraseScale = 1f // 橡皮擦音量略收
    @Volatile private var noiseLevel = 0f // random amplitude flutter state

    private val lock = Object()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    private var track: AudioTrack? = null
    private val chunkBuf = ShortArray(CHUNK_FRAMES)
    private val tickBuf = ShortArray(TICK_FRAMES)

    // synthesizer state (worker thread only)
    private var currentGain = 0f
    private var lowPassState = 0f
    private var noiseState: Int = 0x1F123BB5
    private var trackPlaying = false

    fun start() {
        if (running.get()) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_FRAMES * 2)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            track = AudioTrack(attrs, format, minBuf * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (_: Throwable) {
            track = null
        }
        buildTickBuffer()
        running.set(true)
        worker = Thread({
            renderLoop()
        }, "ReveriePaperSound")
        worker?.priority = Thread.NORM_PRIORITY - 1
        worker?.start()
    }

    /**
     * Begin a stroke. [isEraser] slightly darkens/quiets the texture.
     * Non-blocking, zero allocation. The primed pipeline makes this effectively
     * latency-free: only the gain target changes.
     */
    fun startStroke(isEraser: Boolean) {
        if (!enabled) return
        eraseScale = if (isEraser) 0.7f else 1f
        synchronized(lock) {
            if (isTickType) {
                pendingTick = true
            } else {
                writing = true
            }
            lock.notifyAll()
        }
    }

    /**
     * Update per-move gain from document-space stroke speed (px/ms).
     * Non-blocking, zero allocation: single volatile write.
     */
    fun updateStroke(speedPxPerMs: Float) {
        if (!enabled || isTickType) return
        speedGain = when (profile) {
            profileInk -> (0.10f + speedPxPerMs / 3.0f).coerceIn(0.12f, 1f) // 钢笔停笔几乎无声
            else -> (0.30f + speedPxPerMs / 2.0f).coerceIn(0.35f, 1f) // 铅笔停笔仍轻贴纸面
        }
    }

    /** End the current stroke; the tail decays out, then the loop writes zeros (pipeline stays primed). */
    fun stopStroke() {
        synchronized(lock) {
            writing = false
            lock.notifyAll()
        }
    }

    fun configure(enabled: Boolean, volume: Float, type: StylusAudioType) {
        this.enabled = enabled
        this.volume = volume.coerceIn(0f, 1f)
        this.profile = when (type) {
            StylusAudioType.INK_PEN -> profileInk
            StylusAudioType.SOFT_TICK -> profileTick
            else -> profilePencil
        }
        this.isTickType = type == StylusAudioType.SOFT_TICK
        lowPassState = 0f
        if (!enabled) {
            writing = false
            pendingTick = false
        }
        synchronized(lock) { lock.notifyAll() }
    }

    fun release() {
        running.set(false)
        synchronized(lock) { lock.notifyAll() }
        worker?.interrupt()
        worker = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Throwable) {}
        track = null
    }

    // ------------------------------------------------------------------
    //  Worker thread: single owner of the AudioTrack lifecycle & PCM state
    // ------------------------------------------------------------------
    private fun renderLoop() {
        while (running.get()) {
            val t = track
            if (t == null || !enabled) {
                // 冷却: 引擎未启用或轨道不可用, 挂起等待 (configure/release 会唤醒)
                pauseTrack(t)
                synchronized(lock) {
                    while (running.get() && !enabled) {
                        try { lock.wait() } catch (_: InterruptedException) { return }
                    }
                }
                continue
            }
            if (!trackPlaying) {
                try { t.play() } catch (_: Throwable) { }
                trackPlaying = true
            }

            if (pendingTick) {
                pendingTick = false
                writeAll(tickBuf, TICK_FRAMES)
                continue
            }

            if (writing || currentGain > SILENCE_GAIN) {
                renderChunk()
                writeAll(chunkBuf, CHUNK_FRAMES)
            } else {
                currentGain = 0f
                // 管线预热: 持续写静音块, 落笔时无需 AudioTrack.play() 起播往返
                chunkBuf.fill(0)
                writeAll(chunkBuf, CHUNK_FRAMES)
            }
        }
    }

    private fun pauseTrack(t: AudioTrack?) {
        if (trackPlaying) {
            try { t?.pause() } catch (_: Throwable) {}
            trackPlaying = false
        }
    }

    private fun writeAll(buf: ShortArray, frames: Int) {
        val t = track ?: return
        try {
            var written = 0
            while (written < frames && written >= 0 && running.get()) {
                val n = t.write(buf, written, frames - written, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break // released/errored track: bail out instead of spinning
                written += n
            }
        } catch (_: Throwable) {}
    }

    /** Synthesize one 32 ms block: noise -> low-pass -> flutter -> gain envelope. */
    private fun renderChunk() {
        val prof = profile
        val target = if (writing) {
            speedGain * eraseScale * (volume * volume) * prof.masterGain
        } else {
            0f
        }
        // 快攻慢放: 落笔 1-2 块内可闻, 抬笔自然衰减
        val approach = if (target > currentGain) ATTACK else RELEASE
        currentGain += (target - currentGain) * approach
        val g = currentGain
        val alpha = prof.lowPassAlpha
        var lp = lowPassState
        var nState = noiseState
        var flutter = noiseLevel

        for (i in 0 until CHUNK_FRAMES) {
            // xorshift32: cheap, deterministic, allocation-free white noise
            var x = nState
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            nState = x
            val white = (x / 2147483648.0).toFloat() // -1..1
            lp += alpha * (white - lp)

            // slow amplitude flutter: breaks periodicity into a granular "grit" feel
            flutter += (0.85f + (x shr 24) * (0.30f / 128f) - flutter) * 0.06f

            var s = lp * flutter * g
            // one-pole has unity DC gain; compensate high-frequency loss per profile
            s *= (1.6f - prof.lowPassAlpha * 0.5f)
            if (s > 1f) s = 1f else if (s < -1f) s = -1f
            chunkBuf[i] = (s * 32767f).toInt().toShort()
        }
        noiseState = nState
        lowPassState = lp
        noiseLevel = flutter
    }

    /** Precompute the soft-touch tick: 1750 Hz sine with exponential decay. */
    private fun buildTickBuffer() {
        val freq = 1750.0
        val tau = 0.009 // 9 ms decay constant
        for (i in 0 until TICK_FRAMES) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = Math.exp(-t / tau)
            val s = Math.sin(2.0 * Math.PI * freq * t) * env * 0.5
            tickBuf[i] = (s * 32767.0).toInt().toShort()
        }
    }
}
