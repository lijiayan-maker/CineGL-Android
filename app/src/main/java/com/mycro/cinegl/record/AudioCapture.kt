package com.mycro.cinegl.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * 麦克风采集：常驻运行（视频模式下），一边给顶栏的 L/R 电平表供数，一边在录像时把 PCM 交给编码器。
 *
 * 时间戳和相机用同一个时钟：相机时间戳是 REALTIME（开机时间，含休眠）就用 TIMEBASE_BOOTTIME，
 * 否则用 MONOTONIC。两边对齐了，音画才同步。
 */
class AudioCapture {

    data class Levels(val left: Float = 0f, val right: Float = 0f)

    fun interface Sink {
        /** data 会被下一次读取复用，实现方必须在回调里同步拷走。 */
        fun onPcm(data: ByteArray, length: Int, ptsNanos: Long)
    }

    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels

    val sampleRate = 48_000
    var channelCount = 2
        private set

    @Volatile var sink: Sink? = null
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(boottimeClock: Boolean): Boolean {
        if (running) return true
        val rec = create(AudioFormat.CHANNEL_IN_STEREO, 2) ?: create(AudioFormat.CHANNEL_IN_MONO, 1) ?: return false
        record = rec
        rec.startRecording()
        running = true
        thread = Thread({ loop(rec, boottimeClock) }, "CineGL-Audio").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        _levels.value = Levels()
    }

    @SuppressLint("MissingPermission")
    private fun create(channelMask: Int, channels: Int): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 2, 16 * 1024),
            )
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        channelCount = channels
        return rec
    }

    private fun loop(rec: AudioRecord, boottime: Boolean) {
        val bytesPerFrame = 2 * channelCount
        val buffer = ByteArray(1024 * bytesPerFrame)
        val timestamp = AudioTimestamp()
        val timebase = if (boottime) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
        var framesRead = 0L
        while (running) {
            val n = rec.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            val frames = n / bytesPerFrame
            // AudioTimestamp：第 framePosition 帧是在 nanoTime 时刻采到的，据此推算这批数据第一帧的时间
            val pts = if (rec.getTimestamp(timestamp, timebase) == AudioRecord.SUCCESS) {
                timestamp.nanoTime + (framesRead - timestamp.framePosition) * 1_000_000_000L / sampleRate
            } else {
                val now = if (boottime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
                now - frames * 1_000_000_000L / sampleRate
            }
            framesRead += frames
            updateLevels(buffer, n)
            sink?.onPcm(buffer, n, pts)
        }
    }

    private fun updateLevels(buffer: ByteArray, length: Int) {
        var peakL = 0
        var peakR = 0
        var i = 0
        var channel = 0
        while (i + 1 < length) {
            val sample = abs(((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt())
            if (channel == 0) peakL = max(peakL, sample) else peakR = max(peakR, sample)
            channel = if (channelCount == 2) 1 - channel else 0
            i += 2
        }
        if (channelCount == 1) peakR = peakL
        // 峰值换成 dBFS，-60 dB → 0，0 dB → 1
        fun norm(peak: Int) = if (peak <= 0) 0f else ((20f * log10(peak / 32768f) + 60f) / 60f).coerceIn(0f, 1f)
        _levels.value = Levels(norm(peakL), norm(peakR))
    }
}
