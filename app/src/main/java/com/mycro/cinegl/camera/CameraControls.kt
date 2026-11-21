package com.mycro.cinegl.camera

import android.util.Range
import android.util.Size
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 用户可调的相机参数。每一项都有「自动 / 手动」两种状态。 */
data class CameraControls(
    val isoAuto: Boolean = true,
    val iso: Int = 100,
    val shutterAuto: Boolean = true,
    val exposureNs: Long = 1_000_000_000L / 50,
    val evIndex: Int = 0,
    val focusAuto: Boolean = true,
    val focusDiopters: Float = 0f,
    val wbAuto: Boolean = true,
    val kelvin: Float = 5500f,
    val tint: Float = 0f,          // -1..1
    val zoom: Float = 1f,
    val torch: Boolean = false,
    val stabilization: Boolean = false,
) {
    val exposureAuto: Boolean get() = isoAuto && shutterAuto
}

/** 相机实际在用的参数（从 CaptureResult 读回来，自动模式下给 UI 显示）。 */
data class Readout(
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val focusDiopters: Float = 0f,
    val afLocked: Boolean = false,
)

/**
 * 半自动曝光：ISO、快门只有一个是手动时，另一个由「测光值」推出来。
 *
 * Camera2 只有「全自动 AE」和「全手动」两档，没有快门优先/感光度优先。
 * 做法：在关掉 AE 之前记下当时的曝光量 meteredProduct = ISO × 曝光时间（纳秒），
 * 手动改其中一个时保持乘积不变（再叠加曝光补偿），另一个反算出来。
 */
fun resolveExposure(
    c: CameraControls,
    isoRange: Range<Int>,
    exposureRange: Range<Long>,
    maxExposureNs: Long,
    meteredProduct: Double,
    evStep: Float,
): Pair<Int, Long> {
    val target = meteredProduct * 2.0.pow((c.evIndex * evStep).toDouble())
    val maxExp = minOf(exposureRange.upper, maxExposureNs)
    var iso = c.iso.coerceIn(isoRange.lower, isoRange.upper)
    var exp = c.exposureNs.coerceIn(exposureRange.lower, maxExp)
    if (c.isoAuto && !c.shutterAuto) {
        iso = (target / exp).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
    } else if (!c.isoAuto && c.shutterAuto) {
        exp = (target / iso).roundToLong().coerceIn(exposureRange.lower, maxExp)
    }
    return iso to exp
}

/**
 * 选相机流尺寸：拍照用 4:3（和传感器一致，全幅取景）；录像按画幅选 16:9 或 4:3，
 * 短边尽量等于目标（1080 / 2160），找不到就取不超过目标的最大一个。
 */
fun chooseStreamSize(sizes: List<Size>, aspect: Float, targetShort: Int): Size? {
    val matching = sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }
    val pool = matching.ifEmpty { sizes }
    return pool.firstOrNull { minOf(it.width, it.height) == targetShort }
        ?: pool.filter { minOf(it.width, it.height) <= targetShort }.maxByOrNull { it.width.toLong() * it.height }
        ?: pool.minByOrNull { it.width.toLong() * it.height }
}

/** 拍照尺寸：4:3、不超过约 1300 万像素、长边不超过 4096（GPU 纹理和内存都吃得消）。 */
fun chooseJpegSize(sizes: List<Size>): Size? {
    val fit = sizes.filter {
        abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f &&
            it.width.toLong() * it.height <= 13_000_000L && maxOf(it.width, it.height) <= 4096
    }
    return fit.maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull { abs(it.width * it.height - 12_000_000) }
}

/** 变焦预设：在支持范围内挑几个常用倍率，显示成等效焦距。 */
fun zoomPresets(range: Range<Float>): List<Float> {
    val candidates = listOf(range.lower, 1f, 2f, 3f, 5f)
    return candidates.filter { it >= range.lower - 1e-3f && it <= range.upper + 1e-3f }
        .map { (it * 10).roundToInt() / 10f }
        .distinct()
        .sorted()
}

/** 快门显示：1/50、1/1000、0.5"。 */
fun formatShutter(ns: Long): String {
    if (ns <= 0) return "--"
    val seconds = ns / 1e9
    return if (seconds >= 0.5) "%.1f\"".format(seconds) else "1/${(1.0 / seconds).roundToInt()}"
}

fun formatEv(ev: Float): String = when {
    abs(ev) < 0.05f -> "±0"
    ev > 0 -> "+%.1f".format(ev)
    else -> "%.1f".format(ev)
}

fun formatFocus(diopters: Float): String = if (diopters < 0.01f) "∞" else "%.2fm".format(1f / diopters)

/** 设备朝向吸附到 0/90/180/270，带 ±10° 回差，避免在 45° 附近来回跳。 */
fun snapOrientation(degrees: Int, current: Int): Int {
    if (degrees < 0) return current
    val diff = abs(((degrees - current + 540) % 360) - 180)
    if (diff < 55) return current
    return ((degrees + 45) / 90 * 90) % 360
}
