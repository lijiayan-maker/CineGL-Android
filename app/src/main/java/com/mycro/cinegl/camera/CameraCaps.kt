package com.mycro.cinegl.camera

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import android.util.SizeF
import kotlin.math.sqrt

/** 从 CameraCharacteristics 里摘出这个 App 关心的能力。 */
data class CameraCaps(
    val id: String,
    val front: Boolean,
    val sensorOrientation: Int,
    val hardwareLevel: Int,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val isoRange: Range<Int>,
    val exposureRange: Range<Long>,
    val minFocusDiopters: Float,
    val evRange: Range<Int>,
    val evStep: Float,
    val zoomRange: Range<Float>,
    val fpsRanges: List<Range<Int>>,
    val previewSizes: List<Size>,
    val jpegSizes: List<Size>,
    val focalLength: Float,
    val sensorSize: SizeF?,
    val activeArray: Rect,
    val hasFlash: Boolean,
    val tonemapCurve: Boolean,
    val maxCurvePoints: Int,
    val afModes: IntArray,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val previewStabilization: Boolean,
    val timestampRealtime: Boolean,
) {
    val manualFocus: Boolean get() = manualSensor && minFocusDiopters > 0f

    /** 主摄 35mm 等效焦距（手机上通常 23~26mm）。 */
    val equivalentFocal: Float
        get() {
            val s = sensorSize ?: return 24f
            val diag = sqrt(s.width * s.width + s.height * s.height)
            return if (diag > 0f && focalLength > 0f) focalLength * 43.27f / diag else 24f
        }

    /** 能不能稳定输出某个帧率：要求存在 [fps, fps] 或至少上限等于 fps 的区间。 */
    fun supportsFps(fps: Int) = fpsRanges.any { it.upper == fps }

    fun fpsRangeFor(fps: Int, video: Boolean): Range<Int> {
        // 录像要恒定帧率：优先 [fps, fps]；拍照预览可以让下限低一点，暗处更亮
        val exact = fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
        if (video && exact != null) return exact
        return fpsRanges.filter { it.upper == fps }.minByOrNull { it.lower }
            ?: fpsRanges.minByOrNull { kotlin.math.abs(it.upper - fps) }
            ?: Range(fps, fps)
    }

    companion object {
        fun from(id: String, c: CameraCharacteristics): CameraCaps {
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val tonemapModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
            val stabModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val timestampSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            return CameraCaps(
                id = id,
                front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
                manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                manualPostProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps,
                isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200),
                exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?: Range(100_000L, 250_000_000L),
                minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                evStep = evStep?.toFloat() ?: (1f / 3f),
                zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f),
                fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                    ?: listOf(Range(30, 30)),
                previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList(),
                jpegSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList() ?: emptyList(),
                focalLength = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
                sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
                activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000),
                hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                tonemapCurve = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes,
                maxCurvePoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0,
                afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                maxAeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                previewStabilization = Build.VERSION.SDK_INT >= 33 &&
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in stabModes,
                timestampRealtime = timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            )
        }
    }

    // IntArray 让默认的 equals/hashCode 失效，这里按 id 比较就够了
    override fun equals(other: Any?) = other is CameraCaps && other.id == id
    override fun hashCode() = id.hashCode()
}
