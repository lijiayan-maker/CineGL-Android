package com.mycro.cinegl.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.mycro.cinegl.core.CineCore
import com.mycro.cinegl.core.FrameGeometry
import com.mycro.cinegl.core.OutputPlan
import com.mycro.cinegl.core.RenderParams
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * GL 线程：整个渲染管线的「司机」。
 *
 * 平台相关的事（EGL 上下文、SurfaceTexture、屏幕和编码器的 EGLSurface）在这里做，
 * 真正的绘制交给 C++ 引擎（CineCore.engine*）。一帧的顺序：
 *
 *   相机出帧 → onFrameAvailable → updateTexImage
 *     → engineRenderFrame（镜头 → 光晕/柔光 → LUT + 颗粒）
 *     → 正在录像？切到编码器 surface 再画一遍（带旋转），打上时间戳
 *     → 切到屏幕 surface，enginePresent（带圆角和监看辅助）
 *     → 影调面板开着？每隔几帧渲染一次缩略图
 */
class RenderEngine {

    interface Listener {
        /** 画面尺寸变化（切画幅、切分辨率、换镜头）。主线程回调。 */
        fun onFrameSizeChanged(width: Int, height: Int)
        /** 影调缩略图（索引 → 位图）。主线程回调。 */
        fun onThumbnails(thumbnails: Map<Int, Bitmap>)
    }

    /** 相机输出给 GL 的缓冲描述。 */
    data class InputConfig(
        val bufferWidth: Int,
        val bufferHeight: Int,
        val sensorOrientation: Int,
        val front: Boolean,
        val aspect: Int,
    )

    data class StillRequest(
        val bitmap: Bitmap,
        val sensorOrientation: Int,
        val front: Boolean,
        val aspect: Int,
        val deviceRotation: Int,
        val plane: Int,
        val params: RenderParams,
    )

    private class EncoderTarget(
        val surface: EGLSurface,
        val width: Int,
        val height: Int,
        val matrix: FloatArray,
        val plane: Int,
    )

    private val thread = HandlerThread("CineGL-Render").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 只在 GL 线程访问 ----
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var engine = 0L
    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var screen: EGLSurface? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var input: InputConfig? = null
    private var geometry: FrameGeometry? = null
    private var cropMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private var encoder: EncoderTarget? = null
    private var frameIndex = 0
    private var selectedLook = 0
    private var thumbnailsEnabled = false
    private var thumbBuffer: ByteBuffer? = null
    private lateinit var lutBank: LutBank
    private val startNanos = System.nanoTime()

    @Volatile var params = RenderParams()
    @Volatile var cornerRadiusPx = 0f
    /** 画面顶部被 UI 角标占用的高度（像素），波形图要让开。 */
    @Volatile var overlayTopInsetPx = 0f
    @Volatile var listener: Listener? = null

    fun start() = handler.post {
        val core = EglCore()
        egl = core
        pbuffer = core.createPbufferSurface(1, 1).also { core.makeCurrent(it) }
        engine = CineCore.engineCreate()
        // 只会出现在崩溃日志里，给开发者看，所以用英文（和其他日志一致）
        check(CineCore.engineInit(engine)) { "C++ engine init failed, see the CineGL errors in logcat" }

        // 外部纹理：相机通过 SurfaceTexture 往里送帧，纹理目标必须是 GL_TEXTURE_EXTERNAL_OES
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTexture)
        // 第二个参数指定回调线程：帧到达时直接在 GL 线程上被调用
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = st
        cameraSurface = Surface(st)

        lutBank = LutBank(handler) { engine }
        lutBank.ensureEncoding(params.workingEncoding, selectedLook)
    }

    /** 配置相机输入，返回给相机用的 Surface。必须在建立相机会话之前调用。 */
    suspend fun configureInput(config: InputConfig): Surface = suspendCancellableCoroutine { cont ->
        handler.post {
            val st = surfaceTexture!!
            // 相机按这个尺寸出帧（传感器方向，通常是横的）
            st.setDefaultBufferSize(config.bufferWidth, config.bufferHeight)
            input = config
            updateGeometry()
            cont.resume(cameraSurface!!)
        }
    }

    /** 只换画幅，不换相机流。 */
    fun setAspect(aspect: Int) = handler.post {
        input = input?.copy(aspect = aspect)
        updateGeometry()
    }

    private fun updateGeometry() {
        val cfg = input ?: return
        val geo = CineCore.frameGeometry(cfg.bufferWidth, cfg.bufferHeight, cfg.sensorOrientation, cfg.aspect)
        geometry = geo
        cropMatrix = geo.cropMatrix()
        val l = listener
        mainHandler.post { l?.onFrameSizeChanged(geo.width, geo.height) }
    }

    fun selectLook(index: Int) = handler.post {
        selectedLook = index
        CineCore.engineSelectLook(engine, index)
    }

    /** 新注册的影调（导入的 .cube）需要烘一张表。 */
    fun bakeLook(index: Int) = handler.post { lutBank.bake(index) }

    fun setThumbnailsEnabled(enabled: Boolean) = handler.post { thumbnailsEnabled = enabled }

    // ------------------------------------------------------------------ 屏幕

    fun attachScreen(surface: Surface) = handler.post {
        val core = egl ?: return@post
        screen?.let { core.releaseSurface(it) }
        screen = core.createWindowSurface(surface)
    }

    fun resizeScreen(width: Int, height: Int) = handler.post {
        screenWidth = width
        screenHeight = height
    }

    /** SurfaceView 销毁时调用：必须等 EGLSurface 真正释放后才能返回，否则会往已销毁的窗口里画。 */
    fun detachScreen() = runSync {
        screen?.let { s ->
            egl?.makeCurrent(pbuffer!!)
            egl?.releaseSurface(s)
        }
        screen = null
    }

    // ------------------------------------------------------------------ 录像

    /** 开始往编码器画：matrix 决定旋转/去镜像，plane 决定录调色后的还是 Log 原片。 */
    fun startEncoder(surface: Surface, width: Int, height: Int, matrix: FloatArray, plane: Int) = handler.post {
        val core = egl ?: return@post
        encoder?.let { core.releaseSurface(it.surface) }
        encoder = EncoderTarget(core.createWindowSurface(surface), width, height, matrix, plane)
    }

    fun stopEncoder(onStopped: () -> Unit) = handler.post {
        encoder?.let { enc ->
            egl?.makeCurrent(pbuffer!!)
            egl?.releaseSurface(enc.surface)
        }
        encoder = null
        onStopped()
    }

    // ------------------------------------------------------------------ 拍照

    /** 在 GL 线程上用同一套管线处理一张高分辨率照片。回调在 GL 线程。 */
    fun processStill(req: StillRequest, callback: (Bitmap?) -> Unit) = handler.post {
        val core = egl
        if (core == null) {
            callback(null)
            return@post
        }
        core.makeCurrent(pbuffer!!)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, req.bitmap, 0)

        val bw = req.bitmap.width
        val bh = req.bitmap.height
        // 正常情况下 JPEG 是传感器方向（横的）；个别 HAL 会无视 JPEG_ORIENTATION=0 直接给竖图，那就当它已经转正了
        val orientation = if (req.sensorOrientation % 180 == 90 && bh > bw) 0 else req.sensorOrientation
        val geo = CineCore.frameGeometry(bw, bh, orientation, req.aspect)
        val inputMatrix = FloatArray(16)
        Matrix.multiplyMM(inputMatrix, 0, CineCore.stillInputMatrix(orientation, req.front), 0, geo.cropMatrix(), 0)
        val plan = OutputPlan(CineCore.planPhotoOutput(geo.width, geo.height, req.deviceRotation))
        val outMatrix = CineCore.outputMatrix(plan.rotationCw, req.front, true)
        val buffer = ByteBuffer.allocateDirect(plan.width * plan.height * 4).order(ByteOrder.nativeOrder())

        val ok = CineCore.engineProcessStill(
            engine, tex[0], inputMatrix, geo.width, geo.height, req.params.pack(), req.plane,
            outMatrix, plan.width, plan.height, buffer,
        )
        GLES30.glDeleteTextures(1, tex, 0)
        val result = if (ok) {
            Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888).apply {
                buffer.rewind()
                copyPixelsFromBuffer(buffer)
            }
        } else {
            null
        }
        callback(result)
    }

    // ------------------------------------------------------------------ 每帧

    private fun drawFrame() {
        val core = egl ?: return
        val st = surfaceTexture ?: return
        try {
            core.makeCurrent(screen ?: pbuffer!!)
            st.updateTexImage()   // 把最新一帧「锁」进外部纹理
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        val geo = geometry ?: return
        val p = params
        val packed = p.pack()
        lutBank.ensureEncoding(p.workingEncoding, selectedLook)

        // SurfaceTexture 的矩阵：把「直立画面」坐标映射到缓冲坐标（含传感器旋转、翻转、裁边）；
        // 再乘上画幅裁切，就是镜头 pass 用的 uTexMatrix
        st.getTransformMatrix(stMatrix)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, cropMatrix, 0)
        CineCore.engineRenderFrame(engine, oesTexture, true, texMatrix, geo.width, geo.height, packed, frameIndex)

        encoder?.let { enc ->
            core.makeCurrent(enc.surface)
            CineCore.engineDrawToSurface(engine, enc.plane, enc.matrix, enc.width, enc.height)
            // 用相机传感器的时间戳，音画同步全靠它（音频用同一个时钟）
            core.setPresentationTime(enc.surface, st.timestamp)
            core.swapBuffers(enc.surface)
        }

        screen?.let { s ->
            core.makeCurrent(s)
            val rect = FrameFit.fit(geo.width.toFloat(), geo.height.toFloat(), screenWidth.toFloat(), screenHeight.toFloat())
            val time = (System.nanoTime() - startNanos) / 1e9f
            CineCore.enginePresent(
                engine, screenWidth, screenHeight, rect.left, rect.top, rect.width(), rect.height(),
                cornerRadiusPx, overlayTopInsetPx, packed, time,
            )
            core.swapBuffers(s)
        }

        // 影调面板开着：所有影调高频刷新；关着：只给底部的影调按钮低频刷新当前这一个
        if (thumbnailsEnabled && frameIndex % THUMBNAIL_INTERVAL == 0) {
            renderThumbnails(geo, lutBank.readyLooks)
        } else if (!thumbnailsEnabled && frameIndex % IDLE_THUMBNAIL_INTERVAL == 0 && selectedLook in lutBank.readyLooks) {
            renderThumbnails(geo, listOf(selectedLook))
        }
        frameIndex++
    }

    private fun renderThumbnails(geo: FrameGeometry, looks: List<Int>) {
        if (looks.isEmpty()) return
        val tw = THUMB_WIDTH
        val th = (tw * geo.height / geo.width).coerceIn(tw / 2, tw * 2)
        val cols = THUMB_COLUMNS
        val rows = (looks.size + cols - 1) / cols
        val bytes = cols * tw * rows * th * 4
        val buffer = thumbBuffer?.takeIf { it.capacity() == bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { thumbBuffer = it }
        buffer.rewind()
        if (!CineCore.engineRenderThumbnails(engine, looks.toIntArray(), tw, th, cols, buffer)) return
        val atlas = Bitmap.createBitmap(cols * tw, rows * th, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        atlas.copyPixelsFromBuffer(buffer)
        val result = looks.mapIndexed { i, look ->
            look to Bitmap.createBitmap(atlas, (i % cols) * tw, (i / cols) * th, tw, th)
        }.toMap()
        val l = listener
        mainHandler.post { l?.onThumbnails(result) }
    }

    fun release() {
        handler.post {
            val core = egl ?: return@post
            core.makeCurrent(pbuffer!!)
            encoder?.let { core.releaseSurface(it.surface) }
            screen?.let { core.releaseSurface(it) }
            encoder = null
            screen = null
            if (::lutBank.isInitialized) lutBank.release()
            CineCore.engineRelease(engine)
            engine = 0
            surfaceTexture?.release()
            cameraSurface?.release()
            GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            pbuffer?.let { core.releaseSurface(it) }
            core.release()
            egl = null
        }
        thread.quitSafely()
    }

    private fun runSync(block: () -> Unit) {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private companion object {
        const val TAG = "CineGL"
        const val THUMBNAIL_INTERVAL = 6
        const val IDLE_THUMBNAIL_INTERVAL = 45
        const val THUMB_WIDTH = 132
        const val THUMB_COLUMNS = 4
    }
}
