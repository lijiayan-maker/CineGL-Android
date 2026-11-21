package com.mycro.cinegl.camera

import android.graphics.Rect
import android.hardware.camera2.params.MeteringRectangle
import com.mycro.cinegl.core.FrameGeometry

/**
 * 点击对焦：把「画面上点了哪里」换算成传感器坐标系里的测光区域。
 *
 * 一路要撤销的变换（和渲染时正好相反）：
 *   画面坐标 → 撤销画幅裁切 → 撤销前置镜像 → 撤销传感器安装角度 → 撤销流相对传感器的裁切
 * 开了 CONTROL_ZOOM_RATIO 后，测光坐标系就是「变焦后的视野」铺满 active array，所以不用再管变焦。
 */
object Metering {
    data class SensorPoint(val x: Float, val y: Float)

    /** frameX / frameY：点击点在画面里的归一化坐标（左上原点）。返回流缓冲里的归一化坐标。 */
    fun frameToBuffer(frameX: Float, frameY: Float, geo: FrameGeometry, sensorOrientation: Int, front: Boolean): SensorPoint {
        val px = geo.cropX + frameX * geo.cropW
        val py = geo.cropY + frameY * geo.cropH
        val ux = if (front) 1f - px else px
        val uy = py
        // 直立图像 = 缓冲顺时针转 sensorOrientation 度，反推回缓冲坐标
        return when (((sensorOrientation % 360) + 360) % 360) {
            90 -> SensorPoint(uy, 1f - ux)
            180 -> SensorPoint(1f - ux, 1f - uy)
            270 -> SensorPoint(1f - uy, ux)
            else -> SensorPoint(ux, uy)
        }
    }

    fun regionFor(
        frameX: Float,
        frameY: Float,
        geo: FrameGeometry,
        caps: CameraCaps,
        streamWidth: Int,
        streamHeight: Int,
    ): MeteringRectangle {
        val p = frameToBuffer(frameX, frameY, geo, caps.sensorOrientation, caps.front)
        val aa = caps.activeArray
        // 流是 active array 中心按宽高比裁出来的一块
        val streamAspect = streamWidth.toFloat() / streamHeight
        val arrayAspect = aa.width().toFloat() / aa.height()
        val fovW = if (streamAspect > arrayAspect) aa.width().toFloat() else aa.height() * streamAspect
        val fovH = if (streamAspect > arrayAspect) aa.width() / streamAspect else aa.height().toFloat()
        val cx = aa.left + (aa.width() - fovW) / 2f + p.x * fovW
        val cy = aa.top + (aa.height() - fovH) / 2f + p.y * fovH
        val half = aa.width() * 0.06f
        val rect = Rect(
            (cx - half).toInt().coerceIn(aa.left, aa.right - 1),
            (cy - half).toInt().coerceIn(aa.top, aa.bottom - 1),
            (cx + half).toInt().coerceIn(aa.left + 1, aa.right),
            (cy + half).toInt().coerceIn(aa.top + 1, aa.bottom),
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }
}
