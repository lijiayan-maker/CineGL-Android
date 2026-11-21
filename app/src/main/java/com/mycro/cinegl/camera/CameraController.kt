package com.mycro.cinegl.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.mycro.cinegl.core.CineCore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

/**
 * Camera2 封装：开相机、建会话、按 [CameraControls] 组装请求、拍照。
 * 所有 Camera2 回调都在自己的 HandlerThread 上；对外的 suspend 方法可以在任意协程里调。
 */
class CameraController(context: Context) {

    data class SessionConfig(
        val previewSurface: Surface,
        val streamSize: Size,
        val jpegSize: Size?,
        val fps: Int,
        val video: Boolean,
    )

    class StillCapture(
        val jpeg: ByteArray,
        val iso: Int?,
        val exposureNs: Long?,
        val focalLength: Float?,
        val aperture: Float?,
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CineGL-Camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // 以下状态只在相机线程上读写
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var config: SessionConfig? = null
    private var afRegion: MeteringRectangle? = null
    private var aeRegion: MeteringRectangle? = null
    private var lastReadoutAt = 0L

    @Volatile var caps: CameraCaps? = null
        private set
    @Volatile private var controls = CameraControls()
    @Volatile private var ispLog = false
    /** 自动曝光时测到的「ISO × 曝光时间」（已扣除曝光补偿），半自动曝光靠它反推。 */
    @Volatile private var meteredProduct = 100.0 * 20_000_000.0
    @Volatile private var wbGainsSnapshot: RggbChannelVector? = null
    @Volatile private var wbTransformSnapshot: ColorSpaceTransform? = null

    @Volatile var onReadout: ((Readout) -> Unit)? = null
    @Volatile var onError: ((String) -> Unit)? = null

    /** 手动白平衡能不能交给 ISP（需要 MANUAL_POST_PROCESSING，并且已经拿到过 AWB 的增益快照）。 */
    val wbOnIsp: Boolean
        get() = caps?.manualPostProcessing == true && wbGainsSnapshot != null && wbTransformSnapshot != null

    fun findCameras(): Pair<String?, String?> {
        var back: String? = null
        var front: String? = null
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraMetadata.LENS_FACING_BACK && back == null) back = id
            if (facing == CameraMetadata.LENS_FACING_FRONT && front == null) front = id
        }
        return back to front
    }

    @SuppressLint("MissingPermission")
    suspend fun open(id: String): CameraCaps = suspendCancellableCoroutine { cont ->
        val c = CameraCaps.from(id, manager.getCameraCharacteristics(id))
        Log.i(
            TAG,
            "open camera $id: front=${c.front} level=${c.hardwareLevel} manualSensor=${c.manualSensor} " +
                "manualPost=${c.manualPostProcessing} tonemapCurve=${c.tonemapCurve}/${c.maxCurvePoints} " +
                "iso=${c.isoRange} exp=${c.exposureRange} focus=${c.minFocusDiopters} zoom=${c.zoomRange} " +
                "fps=${c.fpsRanges} realtimeTs=${c.timestampRealtime}",
        )
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                caps = c
                if (cont.isActive) cont.resume(c) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (device == camera) device = null
                if (cont.isActive) cont.resumeWithException(IllegalStateException("相机被其他应用占用"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (device == camera) device = null
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("打开相机失败（错误码 $error）"))
                } else {
                    onError?.invoke("相机出错（错误码 $error）")
                }
            }
        })
    }

    suspend fun startSession(cfg: SessionConfig): Unit = suspendCancellableCoroutine { cont ->
        handler.post {
            val d = device
            if (d == null) {
                cont.resumeWithException(IllegalStateException("相机未打开"))
                return@post
            }
            jpegReader?.close()
            jpegReader = cfg.jpegSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2) }
            config = cfg
            afRegion = null
            aeRegion = null
            val outputs = mutableListOf(OutputConfiguration(cfg.previewSurface))
            jpegReader?.let { outputs += OutputConfiguration(it.surface) }
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        submitRepeating()
                        cont.resume(Unit)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    cont.resumeWithException(IllegalStateException("相机会话配置失败"))
                }
            }
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
        }
    }

    /** 参数变了就重新下发重复请求（Camera2 的请求是不可变的，改参数 = 换一个新请求）。 */
    fun update(c: CameraControls, ispLogCurve: Boolean) {
        controls = c
        ispLog = ispLogCurve
        handler.post { runCatching { submitRepeating() }.onFailure { Log.w(TAG, "update failed", it) } }
    }

    /** 点击对焦/测光。手动对焦时只改测光区域。 */
    fun focusAt(region: MeteringRectangle) = handler.post {
        val s = session ?: return@post
        val d = device ?: return@post
        val cfg = config ?: return@post
        aeRegion = region
        try {
            if (controls.focusAuto && (caps?.maxAfRegions ?: 0) > 0) {
                afRegion = region
                val b = d.createCaptureRequest(template(cfg))
                b.addTarget(cfg.previewSurface)
                applyControls(b, cfg)
                // 先取消上一次对焦，再在新区域触发一次：AF_MODE_AUTO 下对完就锁住
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
            }
            submitRepeating()
        } catch (e: Exception) {
            Log.w(TAG, "focusAt failed", e)
        }
    }

    /** 回到连续自动对焦、全局测光。 */
    fun resetFocus() = handler.post {
        afRegion = null
        aeRegion = null
        runCatching { submitRepeating() }
    }

    suspend fun capture(): StillCapture = suspendCancellableCoroutine { cont ->
        handler.post {
            val d = device
            val s = session
            val reader = jpegReader
            val cfg = config
            if (d == null || s == null || reader == null || cfg == null) {
                cont.resumeWithException(IllegalStateException("当前模式不能拍照"))
                return@post
            }
            var jpeg: ByteArray? = null
            var result: TotalCaptureResult? = null
            fun finish() {
                val bytes = jpeg ?: return
                val r = result ?: return
                if (!cont.isActive) return
                reader.setOnImageAvailableListener(null, null)
                cont.resume(
                    StillCapture(
                        bytes,
                        r.get(CaptureResult.SENSOR_SENSITIVITY),
                        r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        r.get(CaptureResult.LENS_FOCAL_LENGTH),
                        r.get(CaptureResult.LENS_APERTURE),
                    ),
                )
            }
            reader.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
                finish()
            }, handler)

            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(reader.surface)
            b.addTarget(cfg.previewSurface)   // 拍照时预览不断流
            applyControls(b, cfg)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            // 0 = 不旋转：JPEG 保持传感器方向，旋转统一由 GPU 管线做
            b.set(CaptureRequest.JPEG_ORIENTATION, 0)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                    result = r
                    finish()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("拍照失败（${failure.reason}）"))
                }
            }, handler)
        }
    }

    suspend fun close(): Unit = suspendCancellableCoroutine { cont ->
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            jpegReader?.close()
            session = null
            device = null
            jpegReader = null
            config = null
            cont.resume(Unit)
        }
    }

    fun release() {
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            jpegReader?.close()
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ 请求组装

    private fun template(cfg: SessionConfig) =
        if (cfg.video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

    private fun submitRepeating() {
        val d = device ?: return
        val s = session ?: return
        val cfg = config ?: return
        val b = d.createCaptureRequest(template(cfg))
        b.addTarget(cfg.previewSurface)
        applyControls(b, cfg)
        s.setRepeatingRequest(b.build(), resultCallback, handler)
    }

    private fun applyControls(b: CaptureRequest.Builder, cfg: SessionConfig) {
        val caps = caps ?: return
        val c = controls
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, caps.fpsRangeFor(cfg.fps, cfg.video))
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom.coerceIn(caps.zoomRange.lower, caps.zoomRange.upper))

        // —— 曝光 ——
        if (c.exposureAuto || !caps.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evIndex.coerceIn(caps.evRange.lower, caps.evRange.upper))
            aeRegion?.takeIf { caps.maxAeRegions > 0 }?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        } else {
            val frameNs = 1_000_000_000L / cfg.fps
            // 录像时快门不能慢于一帧；拍照预览放宽到 1/15s
            val maxExposure = if (cfg.video) frameNs else 66_666_666L
            val (iso, exposure) = resolveExposure(c, caps.isoRange, caps.exposureRange, maxExposure, meteredProduct, caps.evStep)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(frameNs, exposure))
        }
        b.set(
            CaptureRequest.FLASH_MODE,
            if (c.torch && caps.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )

        // —— 对焦 ——
        val af = afRegion
        when {
            !c.focusAuto && caps.manualFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDiopters.coerceIn(0f, caps.minFocusDiopters))
            }
            af != null && CameraMetadata.CONTROL_AF_MODE_AUTO in caps.afModes -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(af))
            }
            else -> {
                val continuous = if (cfg.video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                val mode = when {
                    continuous in caps.afModes -> continuous
                    CameraMetadata.CONTROL_AF_MODE_AUTO in caps.afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                }
                b.set(CaptureRequest.CONTROL_AF_MODE, mode)
            }
        }

        // —— 白平衡 ——
        val gains = wbGainsSnapshot
        val transform = wbTransformSnapshot
        when {
            c.wbAuto -> {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
            caps.manualPostProcessing && gains != null && transform != null -> {
                // ISP 白平衡：沿用 AWB 最后一次的色彩矩阵，只按色温相对 5500K 调 R/B 增益
                val rel = CineCore.whiteBalanceGains(c.kelvin, c.tint)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
                b.set(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(gains.red * rel[0] / rel[1], gains.greenEven, gains.greenOdd, gains.blue * rel[2] / rel[1]),
                )
            }
            else -> {
                // 不支持手动后处理：锁住 AWB，色温交给 GPU（镜头 pass 里的 uWbGains）
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
        }

        // —— ISP Log：把 ISP 的色调曲线换成 CineLog ——
        if (ispLog && caps.tonemapCurve && caps.maxCurvePoints >= 2) {
            val curve = CineCore.cineLogCurve(minOf(64, caps.maxCurvePoints))
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(curve, curve, curve))
        }

        // —— 防抖 ——
        if (caps.previewStabilization) {
            b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (c.stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
        }
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val c = controls
            val caps = caps ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (c.exposureAuto && iso != null && exposure != null) {
                // 记下「不含曝光补偿」的曝光量，半自动时再把补偿加回去
                meteredProduct = iso.toDouble() * exposure / 2.0.pow((c.evIndex * caps.evStep).toDouble())
            }
            if (c.wbAuto) {
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { wbGainsSnapshot = it }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { wbTransformSnapshot = it }
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastReadoutAt < 120) return
            lastReadoutAt = now
            onReadout?.invoke(
                Readout(
                    iso = iso ?: 0,
                    exposureNs = exposure ?: 0,
                    focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                    afLocked = result.get(CaptureResult.CONTROL_AF_STATE) == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "CineGL"
    }
}
