package com.mycro.cinegl.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.mycro.cinegl.R
import com.mycro.cinegl.media.MediaSaver
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 录像：H.264 视频（输入是一个 Surface，GL 直接往里画）+ AAC 音频 → MP4。
 *
 * 视频编码器用异步回调；音频由采集线程直接喂给编码器（同步模式更好懂）。
 * MediaMuxer 必须等所有轨道的格式都确定了才能 start，之前产生的样本先拷一份缓存起来。
 * 时间戳：所有样本减去「第一帧视频」的时间，音频早于它的直接丢掉。
 */
class VideoRecorder(private val context: Context) {

    data class Spec(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val orientationHint: Int,
        val audio: Boolean,
        val sampleRate: Int,
        val channels: Int,
    )

    private class Sample(val audio: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private val muxLock = Any()
    private val audioLock = Any()
    private var output: MediaSaver.PendingVideo? = null
    private var muxer: MediaMuxer? = null
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var spec: Spec? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val pending = ArrayList<Sample>()
    private var basePtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var lastAudioInputUs = 0L
    private var audioEnded = false
    private val videoEos = CountDownLatch(1)

    /** 准备编码器，返回给 GL 画的输入 Surface。 */
    @Throws(IOException::class)
    fun prepare(spec: Spec): Surface {
        this.spec = spec
        val out = MediaSaver.createVideo(context) ?: throw IOException(context.getString(R.string.error_video_file))
        output = out
        muxer = MediaMuxer(out.fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(spec.orientationHint)
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val thread = HandlerThread("CineGL-Encoder").apply { start() }
        codecThread = thread
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(videoCallback, Handler(thread.looper))
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        video = codec

        if (spec.audio) {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000 * spec.channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
        return surface
    }

    fun start() {
        video?.start()
        audio?.start()
    }

    /** 音频采集线程调用。 */
    fun writeAudio(data: ByteArray, length: Int, ptsNanos: Long) = synchronized(audioLock) {
        val codec = audio ?: return
        val s = spec ?: return
        if (audioEnded) return
        val bytesPerFrame = 2 * s.channels
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) break   // 编码器忙不过来就丢这一小段，别卡住采集
            val buffer = codec.getInputBuffer(index) ?: break
            buffer.clear()
            val n = minOf(buffer.remaining(), length - offset)
            buffer.put(data, offset, n)
            val ptsUs = (ptsNanos + (offset / bytesPerFrame) * 1_000_000_000L / s.sampleRate) / 1000
            codec.queueInputBuffer(index, 0, n, ptsUs, 0)
            lastAudioInputUs = ptsUs
            offset += n
        }
        drainAudio(endOfStream = false)
    }

    private fun drainAudio(endOfStream: Boolean) {
        val codec = audio ?: return
        val info = MediaCodec.BufferInfo()
        var idleRounds = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleRounds > 100) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(true, codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buffer != null) writeSample(true, buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private val videoCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit   // Surface 输入，用不到

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val buffer = codec.getOutputBuffer(index)
            // SPS/PPS 已经在输出格式里了，muxer 会自己写，这里跳过
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (info.size > 0 && buffer != null) writeSample(false, buffer, info)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoEos.countDown()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "video encoder error", e)
            videoEos.countDown()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = addTrack(false, format)
    }

    private fun addTrack(isAudio: Boolean, format: MediaFormat) = synchronized(muxLock) {
        val m = muxer ?: return
        if (muxerStarted) return
        if (isAudio) audioTrack = m.addTrack(format) else videoTrack = m.addTrack(format)
        if (videoTrack >= 0 && (audio == null || audioTrack >= 0)) {
            m.start()
            muxerStarted = true
            // 第一帧视频还没到时基准时间未定，缓存先留着，等它来了再一起写
            if (basePtsUs >= 0) flushPending()
        }
    }

    private fun writeSample(isAudio: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(muxLock) {
        if (!isAudio && basePtsUs < 0) basePtsUs = info.presentationTimeUs
        if (!muxerStarted || basePtsUs < 0) {
            val copy = ByteBuffer.allocate(info.size)
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            copy.put(buffer)
            copy.flip()
            val copyInfo = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending += Sample(isAudio, copy, copyInfo)
            return
        }
        flushPending()
        writeNow(isAudio, buffer, info)
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        pending.forEach { writeNow(it.audio, it.data, it.info) }
        pending.clear()
    }

    private fun writeNow(isAudio: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        var pts = info.presentationTimeUs - basePtsUs
        if (pts < 0) return   // 第一帧视频之前的声音
        val last = if (isAudio) lastAudioPtsUs else lastVideoPtsUs
        if (last < 0) Log.d(TAG, "first ${if (isAudio) "audio" else "video"} sample: raw=${info.presentationTimeUs}us base=${basePtsUs}us")
        if (pts <= last) pts = last + 1   // muxer 要求同一轨时间戳严格递增
        if (isAudio) lastAudioPtsUs = pts else lastVideoPtsUs = pts
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val out = MediaCodec.BufferInfo().apply { set(info.offset, info.size, pts, info.flags) }
        m.writeSampleData(if (isAudio) audioTrack else videoTrack, buffer, out)
    }

    /**
     * 结束录制（阻塞，放后台线程调）。调用前 GL 必须已经停止往输入 Surface 画、音频回调已经摘掉。
     * 成功返回相册里的 Uri。
     */
    fun stop(): Uri? {
        Log.d(TAG, "stop: last video pts=${lastVideoPtsUs}us, last audio pts=${lastAudioPtsUs}us")
        runCatching { video?.signalEndOfInputStream() }
        synchronized(audioLock) {
            audioEnded = true
            audio?.let { codec ->
                runCatching {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, lastAudioInputUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    drainAudio(endOfStream = true)
                }
            }
        }
        videoEos.await(3, TimeUnit.SECONDS)
        runCatching { video?.stop() }
        runCatching { video?.release() }
        runCatching { audio?.stop() }
        runCatching { audio?.release() }
        codecThread?.quitSafely()

        val ok = synchronized(muxLock) {
            val started = muxerStarted && lastVideoPtsUs >= 0
            val stopped = started && runCatching { muxer?.stop() }.isSuccess
            runCatching { muxer?.release() }
            muxer = null
            stopped
        }
        val out = output ?: return null
        runCatching { out.fd.close() }
        return if (ok) {
            MediaSaver.publish(context, out.uri)
            out.uri
        } else {
            MediaSaver.delete(context, out.uri)
            null
        }
    }

    private companion object {
        const val TAG = "CineGL"
    }
}
