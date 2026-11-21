package com.mycro.cinegl.ui

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.mycro.cinegl.camera.CameraCaps
import com.mycro.cinegl.camera.CameraControls
import com.mycro.cinegl.camera.Readout
import com.mycro.cinegl.core.LookInfo

enum class CaptureMode(val label: String) { PHOTO("照片"), VIDEO("视频") }

/** code 和 C++ 的 cinegl::AspectRatio 一致。 */
enum class Aspect(val code: Int, val label: String) {
    R16x9(0, "16:9"),
    R4x3(1, "4:3 全开"),
    R1x1(2, "1:1"),
    R239(3, "2.39:1"),
}

enum class Resolution(val label: String, val shortSide: Int) { FHD("1080p", 1080), UHD("4K", 2160) }

enum class LogMode(val label: String) { OFF("关"), GPU("GPU 模拟"), ISP("ISP 曲线") }

enum class Tool(val label: String) { FOCUS("对焦"), WB("白平衡"), ISO("ISO"), SHUTTER("快门"), EV("曝光补偿") }

enum class Panel { LOOKS, SETTINGS }

/** 所有视觉效果都是 0..10 的连续值。 */
data class Effects(
    val lookStrength: Float = 10f,
    val halation: Float = 3f,
    val mist: Float = 0f,
    val grain: Float = 3f,
    val grainSize: Float = 3f,
    val vignette: Float = 3f,
    val aberration: Float = 0f,
)

data class Assist(
    val grid: Boolean = true,
    val level: Boolean = true,
    val zebra: Boolean = false,
    val peaking: Boolean = false,
    val waveform: Boolean = false,
)

data class CameraUiState(
    val cameraPermission: Boolean = false,
    val micPermission: Boolean = false,
    val mode: CaptureMode = CaptureMode.VIDEO,
    val front: Boolean = false,
    val aspect: Aspect = Aspect.R16x9,
    val resolution: Resolution = Resolution.FHD,
    val fps: Int = 30,
    val availableFps: List<Int> = listOf(24, 25, 30),
    val uhdAvailable: Boolean = false,
    val logMode: LogMode = LogMode.OFF,
    val ispLogAvailable: Boolean = false,
    val burnIn: Boolean = true,
    val showSignal: Boolean = false,
    val lookId: String = "teal_orange",
    val lookIndex: Int = 0,
    val looks: List<LookInfo> = emptyList(),
    val lookThumbs: Map<Int, ImageBitmap> = emptyMap(),
    val effects: Effects = Effects(),
    val assist: Assist = Assist(),
    val controls: CameraControls = CameraControls(),
    val caps: CameraCaps? = null,
    val readout: Readout = Readout(),
    val zoomPresets: List<Float> = listOf(1f),
    val activeTool: Tool? = null,
    val panel: Panel? = null,
    val frameWidth: Int = 1080,
    val frameHeight: Int = 1920,
    val recording: Boolean = false,
    val recordStartMs: Long = 0L,
    val busy: Boolean = false,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    val lastThumb: ImageBitmap? = null,
    val deviceRotation: Int = 0,
    val focusPoint: Pair<Float, Float>? = null,
    val focusEventId: Int = 0,
    val message: String? = null,
    val messageId: Int = 0,
) {
    val logOn: Boolean get() = logMode != LogMode.OFF
    val currentLook: LookInfo? get() = looks.getOrNull(lookIndex)

    /** 一键重置是否可用：有手动参数，或点击对焦锁定了对焦/测光区域。 */
    val canResetControls: Boolean get() = controls.hasManual || focusPoint != null
}
