package com.mycro.cinegl.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Size
import android.view.Surface
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mycro.cinegl.camera.CameraController
import com.mycro.cinegl.camera.CameraControls
import com.mycro.cinegl.camera.Metering
import com.mycro.cinegl.camera.chooseJpegSize
import com.mycro.cinegl.camera.chooseStreamSize
import com.mycro.cinegl.camera.snapOrientation
import com.mycro.cinegl.camera.zoomPresets
import com.mycro.cinegl.core.CineCore
import com.mycro.cinegl.core.Encoding
import com.mycro.cinegl.core.FrameGeometry
import com.mycro.cinegl.core.OutputPlan
import com.mycro.cinegl.core.RenderParams
import com.mycro.cinegl.media.MediaSaver
import com.mycro.cinegl.record.AudioCapture
import com.mycro.cinegl.record.VideoRecorder
import com.mycro.cinegl.render.RenderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 相机页的大脑：持有相机、GL 引擎、录像器，把 UI 操作翻译成它们的调用。
 */
class CameraViewModel(app: Application) : AndroidViewModel(app), RenderEngine.Listener {

    private val settings = SettingsStore(app)
    private val render = RenderEngine()
    private val camera = CameraController(app)
    private val audio = AudioCapture()
    private var recorder: VideoRecorder? = null

    private val cameraMutex = Mutex()
    private var cameraJob: Job? = null
    private var backId: String? = null
    private var frontId: String? = null
    private var streamSize: Size? = null
    private var sessionFps = 30
    private var resumed = false
    private var cameraRunning = false

    private val _state = MutableStateFlow(settings.load())
    val state: StateFlow<CameraUiState> = _state
    val audioLevels: StateFlow<AudioCapture.Levels> = audio.levels

    init {
        render.listener = this
        render.start()
        camera.onReadout = { r -> _state.update { it.copy(readout = r) } }
        camera.onError = { msg -> toast(msg) }
        runCatching { camera.findCameras() }.getOrNull()?.let { (b, f) ->
            backId = b
            frontId = f
        }
        refreshLooks()
        viewModelScope.launch { loadImportedLuts() }
        pushRenderParams()
    }

    // ================================================================== 生命周期

    fun onPermissions(cameraGranted: Boolean, micGranted: Boolean) {
        _state.update { it.copy(cameraPermission = cameraGranted, micPermission = micGranted) }
        maybeStartCamera()
    }

    fun onResume() {
        resumed = true
        maybeStartCamera()
    }

    fun onPause() {
        resumed = false
        if (_state.value.recording) stopRecording()
        audio.stop()
        cameraJob?.cancel()
        cameraRunning = false
        viewModelScope.launch { cameraMutex.withLock { camera.close() } }
    }

    fun onSurfaceCreated(surface: Surface) = render.attachScreen(surface)
    fun onSurfaceChanged(width: Int, height: Int) = render.resizeScreen(width, height)
    fun onSurfaceDestroyed() = render.detachScreen()
    fun setOverlayMetrics(cornerRadiusPx: Float, topInsetPx: Float) {
        render.cornerRadiusPx = cornerRadiusPx
        render.overlayTopInsetPx = topInsetPx
    }

    private fun maybeStartCamera() {
        if (resumed && _state.value.cameraPermission && !cameraRunning) restartCamera()
    }

    private fun restartCamera() {
        cameraRunning = true
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch {
            cameraMutex.withLock {
                val s = _state.value
                camera.close()
                audio.stop()
                val id = (if (s.front) frontId else backId) ?: backId ?: frontId
                if (id == null) {
                    toast("没有找到可用的相机")
                    return@withLock
                }
                val caps = try {
                    camera.open(id)
                } catch (e: Exception) {
                    toast(e.message ?: "打开相机失败")
                    cameraRunning = false
                    return@withLock
                }
                val video = s.mode == CaptureMode.VIDEO
                // 拍照用 4:3 全幅流；录像按画幅选 16:9 或 4:3 的流，裁切交给 GPU
                val wide = video && (s.aspect == Aspect.R16x9 || s.aspect == Aspect.R239)
                val streamAspect = if (wide) 16f / 9f else 4f / 3f
                val uhd = caps.previewSizes.any { minOf(it.width, it.height) == 2160 }
                val targetShort = if (video && s.resolution == Resolution.UHD && uhd) 2160 else 1080
                val size = chooseStreamSize(caps.previewSizes, streamAspect, targetShort)
                if (size == null) {
                    toast("相机没有可用的预览尺寸")
                    return@withLock
                }
                val fps = if (caps.supportsFps(s.fps)) s.fps else 30
                streamSize = size
                sessionFps = fps
                val surface = render.configureInput(
                    RenderEngine.InputConfig(size.width, size.height, caps.sensorOrientation, caps.front, s.aspect.code),
                )
                camera.update(s.controls, ispLogCurve = s.logMode == LogMode.ISP)
                try {
                    camera.startSession(
                        CameraController.SessionConfig(surface, size, if (video) null else chooseJpegSize(caps.jpegSizes), fps, video),
                    )
                } catch (e: Exception) {
                    toast(e.message ?: "相机会话失败")
                    return@withLock
                }
                if (video && s.micPermission) audio.start(caps.timestampRealtime)
                _state.update {
                    it.copy(
                        caps = caps,
                        fps = fps,
                        availableFps = listOf(24, 25, 30, 60).filter { f -> caps.supportsFps(f) }.ifEmpty { listOf(30) },
                        uhdAvailable = uhd,
                        ispLogAvailable = caps.tonemapCurve && caps.maxCurvePoints >= 2,
                        zoomPresets = zoomPresets(caps.zoomRange.lower, caps.zoomRange.upper),
                    )
                }
                pushRenderParams()
            }
        }
    }

    // ================================================================== RenderEngine 回调

    override fun onFrameSizeChanged(width: Int, height: Int) {
        _state.update { it.copy(frameWidth = width, frameHeight = height) }
    }

    override fun onThumbnails(thumbnails: Map<Int, Bitmap>) {
        _state.update { s -> s.copy(lookThumbs = s.lookThumbs + thumbnails.mapValues { it.value.asImageBitmap() }) }
    }

    // ================================================================== 拍摄设置

    fun setMode(mode: CaptureMode) {
        val s = _state.value
        if (s.recording || s.mode == mode) return
        updateAndSave { it.copy(mode = mode, activeTool = null) }
        restartCamera()
    }

    fun flipCamera() {
        if (_state.value.recording) return
        _state.update { it.copy(front = !it.front, controls = it.controls.copy(zoom = 1f, focusAuto = true)) }
        restartCamera()
    }

    fun setAspect(aspect: Aspect) {
        val s = _state.value
        if (s.recording || s.aspect == aspect) return
        val wide = { a: Aspect -> a == Aspect.R16x9 || a == Aspect.R239 }
        updateAndSave { it.copy(aspect = aspect) }
        // 录像时 16:9 ↔ 4:3 要换相机流；同一类比例只需要换 GPU 裁切
        if (s.mode == CaptureMode.VIDEO && wide(s.aspect) != wide(aspect)) restartCamera() else render.setAspect(aspect.code)
    }

    fun setResolution(r: Resolution) {
        if (_state.value.recording) return
        updateAndSave { it.copy(resolution = r) }
        if (_state.value.mode == CaptureMode.VIDEO) restartCamera()
    }

    fun setFps(fps: Int) {
        if (_state.value.recording) return
        updateAndSave { it.copy(fps = fps) }
        restartCamera()
    }

    fun setLogMode(mode: LogMode) {
        if (_state.value.recording) return
        updateAndSave { it.copy(logMode = mode) }
        camera.update(_state.value.controls, ispLogCurve = mode == LogMode.ISP)
        pushRenderParams()
    }

    fun setBurnIn(on: Boolean) {
        if (_state.value.recording) return
        updateAndSave { it.copy(burnIn = on) }
    }

    fun setShowSignal(on: Boolean) {
        _state.update { it.copy(showSignal = on) }
        pushRenderParams()
    }

    fun setAssist(transform: (Assist) -> Assist) {
        updateAndSave { it.copy(assist = transform(it.assist)) }
        pushRenderParams()
    }

    fun onDeviceOrientation(degrees: Int) {
        val current = _state.value.deviceRotation
        val snapped = snapOrientation(degrees, current)
        if (snapped != current && !_state.value.recording) _state.update { it.copy(deviceRotation = snapped) }
    }

    // ================================================================== 影调 / 效果

    fun selectLook(index: Int) {
        val look = _state.value.looks.getOrNull(index) ?: return
        updateAndSave { it.copy(lookIndex = index, lookId = look.id) }
        render.selectLook(index)
    }

    fun setEffects(transform: (Effects) -> Effects) {
        updateAndSave { it.copy(effects = transform(it.effects)) }
        pushRenderParams()
    }

    fun resetEffects() = setEffects { Effects(lookStrength = it.lookStrength) }

    fun importCube(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "imported.cube"
                    val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("读不到文件")
                    name to text
                }.getOrNull()
            }
            if (imported == null) {
                toast("读取 LUT 失败")
                return@launch
            }
            val (fileName, text) = imported
            val safeName = fileName.replace(Regex("[^\\w.\\-]"), "_")
            val index = withContext(Dispatchers.Default) {
                CineCore.registerCubeLook("cube:$safeName", fileName.substringBeforeLast('.'), text)
            }
            if (index < 0) {
                toast("导入失败：${CineCore.lastError()}")
                return@launch
            }
            // 拷一份到私有目录，下次启动自动加载
            withContext(Dispatchers.IO) { File(lutDir(), safeName).writeText(text) }
            refreshLooks()
            render.bakeLook(index)
            selectLook(index)
            toast("已导入 ${fileName.substringBeforeLast('.')}")
        }
    }

    private suspend fun loadImportedLuts() {
        val indices = withContext(Dispatchers.IO) {
            lutDir().listFiles { f -> f.extension.equals("cube", ignoreCase = true) }.orEmpty().map { f ->
                CineCore.registerCubeLook("cube:${f.name}", f.nameWithoutExtension, f.readText())
            }.filter { it >= 0 }
        }
        if (indices.isEmpty()) return
        refreshLooks()
        indices.forEach { render.bakeLook(it) }
    }

    private fun lutDir() = File(getApplication<Application>().filesDir, "luts").apply { mkdirs() }

    private fun refreshLooks() {
        val looks = CineCore.looks()
        _state.update { s ->
            val index = looks.indexOfFirst { it.id == s.lookId }.takeIf { it >= 0 } ?: s.lookIndex.coerceIn(0, looks.lastIndex)
            s.copy(looks = looks, lookIndex = index)
        }
        render.selectLook(_state.value.lookIndex)
    }

    // ================================================================== 面板 / 工具

    fun toggleTool(tool: Tool) = _state.update { it.copy(activeTool = if (it.activeTool == tool) null else tool, panel = null) }

    fun togglePanel(panel: Panel) {
        val open = _state.value.panel != panel
        _state.update { it.copy(panel = if (open) panel else null, activeTool = null) }
        render.setThumbnailsEnabled(open && panel == Panel.LOOKS)
    }

    fun closePanels() {
        _state.update { it.copy(panel = null, activeTool = null) }
        render.setThumbnailsEnabled(false)
    }

    // ================================================================== 手动参数

    fun updateControls(transform: (CameraControls) -> CameraControls) {
        val s = _state.value
        val c = transform(s.controls)
        _state.update { it.copy(controls = c) }
        camera.update(c, ispLogCurve = s.logMode == LogMode.ISP)
        pushRenderParams()
    }

    /** 从自动切到手动时，用相机当前的实际值做起点，画面不会跳。 */
    fun setAuto(tool: Tool, auto: Boolean) {
        val r = _state.value.readout
        updateControls { c ->
            when (tool) {
                Tool.ISO -> c.copy(isoAuto = auto, iso = if (!auto && r.iso > 0) r.iso else c.iso)
                Tool.SHUTTER -> c.copy(shutterAuto = auto, exposureNs = if (!auto && r.exposureNs > 0) r.exposureNs else c.exposureNs)
                Tool.FOCUS -> c.copy(focusAuto = auto, focusDiopters = if (!auto) r.focusDiopters else c.focusDiopters)
                Tool.WB -> c.copy(wbAuto = auto)
                Tool.EV -> c.copy(evIndex = 0)
            }
        }
        if (tool == Tool.FOCUS && auto) camera.resetFocus()
    }

    fun tapToFocus(frameX: Float, frameY: Float) {
        val s = _state.value
        val caps = s.caps ?: return
        val size = streamSize ?: return
        val geo: FrameGeometry = CineCore.frameGeometry(size.width, size.height, caps.sensorOrientation, s.aspect.code)
        camera.focusAt(Metering.regionFor(frameX, frameY, geo, caps, size.width, size.height))
        _state.update { it.copy(focusPoint = frameX to frameY, focusEventId = it.focusEventId + 1) }
    }

    fun resetFocus() {
        camera.resetFocus()
        _state.update { it.copy(focusPoint = null) }
    }

    // ================================================================== 拍摄

    fun onShutter() {
        val s = _state.value
        if (s.mode == CaptureMode.PHOTO) takePhoto() else if (s.recording) stopRecording() else startRecording()
    }

    private fun takePhoto() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val still = camera.capture()
                val s = _state.value
                val caps = s.caps ?: error("相机未就绪")
                val bitmap = withContext(Dispatchers.Default) { decodeJpeg(still.jpeg, 4096) } ?: error("照片解码失败")
                val request = RenderEngine.StillRequest(
                    bitmap = bitmap,
                    sensorOrientation = caps.sensorOrientation,
                    front = caps.front,
                    aspect = s.aspect.code,
                    deviceRotation = s.deviceRotation,
                    plane = recordPlane(s),
                    params = render.params.copy(showSignal = false, zebra = false, peaking = false, waveform = false),
                )
                val processed = suspendCancellableCoroutine { cont -> render.processStill(request) { cont.resume(it) } }
                bitmap.recycle()
                if (processed == null) error("GPU 处理失败")
                val ctx = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) {
                    MediaSaver.savePhoto(ctx, processed, MediaSaver.PhotoMeta(still.iso, still.exposureNs, still.focalLength, still.aperture))
                } ?: error("保存失败")
                val thumb = withContext(Dispatchers.Default) { thumbnailOf(processed) }
                processed.recycle()
                _state.update { it.copy(lastUri = uri, lastIsVideo = false, lastThumb = thumb.asImageBitmap()) }
            } catch (e: Exception) {
                toast(e.message ?: "拍照失败")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun startRecording() {
        val s = _state.value
        val caps = s.caps ?: return
        if (s.busy) return
        val plan = OutputPlan(CineCore.planVideoOutput(s.frameWidth, s.frameHeight, s.deviceRotation))
        val bitrate = (plan.width.toLong() * plan.height * sessionFps * 0.25).toLong().coerceIn(4_000_000L, 100_000_000L).toInt()
        val withAudio = s.micPermission && audio.isRunning
        val rec = VideoRecorder(getApplication())
        val surface = try {
            rec.prepare(
                VideoRecorder.Spec(
                    plan.width, plan.height, sessionFps, bitrate, plan.orientationHint,
                    withAudio, audio.sampleRate, audio.channelCount,
                ),
            )
        } catch (e: Exception) {
            toast("无法开始录像：${e.message}")
            return
        }
        rec.start()
        recorder = rec
        if (withAudio) audio.sink = AudioCapture.Sink { data, length, pts -> rec.writeAudio(data, length, pts) }
        render.startEncoder(surface, plan.width, plan.height, CineCore.outputMatrix(plan.rotationCw, caps.front, false), recordPlane(s))
        _state.update { it.copy(recording = true, recordStartMs = SystemClock.elapsedRealtime(), activeTool = null, panel = null) }
        render.setThumbnailsEnabled(false)
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        audio.sink = null
        _state.update { it.copy(recording = false, busy = true) }
        render.stopEncoder {
            viewModelScope.launch(Dispatchers.IO) {
                val uri = rec.stop()
                val thumb = uri?.let { u ->
                    runCatching {
                        getApplication<Application>().contentResolver.loadThumbnail(u, Size(320, 320), null).asImageBitmap()
                    }.getOrNull()
                }
                _state.update {
                    it.copy(
                        busy = false,
                        lastUri = uri ?: it.lastUri,
                        lastIsVideo = if (uri != null) true else it.lastIsVideo,
                        lastThumb = thumb ?: it.lastThumb,
                    )
                }
                if (uri == null) toast("录像保存失败")
            }
        }
    }

    /** 录像 / 拍照写进文件的是调色后的成片，还是 Log 原片（LUT 只用来监看）。 */
    private fun recordPlane(s: CameraUiState) =
        if (s.burnIn || !s.logOn) CineCore.PLANE_GRADED else CineCore.PLANE_SIGNAL

    // ================================================================== 工具函数

    private fun pushRenderParams() {
        val s = _state.value
        val ispLog = s.logMode == LogMode.ISP && s.ispLogAvailable
        val c = s.controls
        // 手动白平衡：ISP 能做就交给 ISP，否则在 GPU 的镜头 pass 里乘增益
        val wb = if (!c.wbAuto && !camera.wbOnIsp) CineCore.whiteBalanceGains(c.kelvin, c.tint) else floatArrayOf(1f, 1f, 1f)
        val fx = s.effects
        render.params = RenderParams(
            sourceEncoding = if (ispLog) Encoding.CINELOG else Encoding.REC709,
            workingEncoding = if (s.logOn) Encoding.CINELOG else Encoding.REC709,
            lookStrength = fx.lookStrength / 10f,
            halation = fx.halation / 10f,
            mist = fx.mist / 10f,
            grain = fx.grain / 10f,
            grainSize = fx.grainSize / 10f,
            vignette = fx.vignette / 10f,
            aberration = fx.aberration / 10f,
            wbR = wb[0],
            wbG = wb[1],
            wbB = wb[2],
            showSignal = s.showSignal && s.logOn,
            zebra = s.assist.zebra,
            peaking = s.assist.peaking,
            waveform = s.assist.waveform,
        )
    }

    private fun updateAndSave(transform: (CameraUiState) -> CameraUiState) {
        _state.update(transform)
        settings.save(_state.value)
    }

    fun toast(message: String) = _state.update { it.copy(message = message, messageId = it.messageId + 1) }

    private fun decodeJpeg(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun thumbnailOf(bitmap: Bitmap): Bitmap {
        val scale = 240f / max(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
    }

    override fun onCleared() {
        if (_state.value.recording) stopRecording()
        audio.stop()
        camera.release()
        render.release()
    }
}
