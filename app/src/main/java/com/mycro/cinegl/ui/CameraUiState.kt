package com.mycro.cinegl.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import com.mycro.cinegl.R
import com.mycro.cinegl.camera.CameraCaps
import com.mycro.cinegl.camera.CameraControls
import com.mycro.cinegl.camera.Readout
import com.mycro.cinegl.core.LookInfo

// 界面文案都放在字符串资源里：系统为简体中文时显示中文，其他语言一律显示英文

enum class CaptureMode(@StringRes val label: Int) { PHOTO(R.string.mode_photo), VIDEO(R.string.mode_video) }

/** code 和 C++ 的 cinegl::AspectRatio 一致。 */
enum class Aspect(val code: Int, @StringRes val label: Int) {
    R16x9(0, R.string.aspect_16x9),
    R4x3(1, R.string.aspect_4x3),
    R1x1(2, R.string.aspect_1x1),
    R239(3, R.string.aspect_239),
}

enum class Resolution(val label: String, val shortSide: Int) { FHD("1080p", 1080), UHD("4K", 2160) }

enum class LogMode(@StringRes val label: Int) { OFF(R.string.log_off), GPU(R.string.log_gpu), ISP(R.string.log_isp) }

enum class Tool(@StringRes val label: Int) {
    FOCUS(R.string.tool_focus),
    WB(R.string.tool_wb),
    ISO(R.string.tool_iso),
    SHUTTER(R.string.tool_shutter),
    EV(R.string.tool_ev),
}

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
