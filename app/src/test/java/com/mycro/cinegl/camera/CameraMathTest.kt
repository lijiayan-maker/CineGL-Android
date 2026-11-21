package com.mycro.cinegl.camera

import com.mycro.cinegl.core.FrameGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 相机侧的纯逻辑（不碰 Android 类），JVM 上直接跑：./gradlew :app:testDebugUnitTest */
class CameraMathTest {

    private val isoMin = 50
    private val isoMax = 3200
    private val expMin = 100_000L          // 1/10000s
    private val expMax = 1_000_000_000L    // 1s

    @Test
    fun manualIsoKeepsMeteredExposure() {
        // 测光值：ISO 100 × 1/50s。手动把 ISO 提到 400 → 快门应该快 2 档到 1/200s
        val metered = 100.0 * 20_000_000
        val c = CameraControls(isoAuto = false, iso = 400, shutterAuto = true)
        val (iso, exp) = resolveExposure(c, isoMin, isoMax, expMin, expMax, metered, 1f / 3f)
        assertEquals(400, iso)
        assertEquals(5_000_000L, exp)
    }

    @Test
    fun manualShutterDerivesIsoAndAppliesEv() {
        // 快门手动 1/100s，曝光补偿 +1 档（3 × 1/3）→ ISO = 100 × 2 × 2 = 400
        val metered = 100.0 * 20_000_000
        val c = CameraControls(isoAuto = true, shutterAuto = false, exposureNs = 10_000_000L, evIndex = 3)
        val (iso, exp) = resolveExposure(c, isoMin, isoMax, expMin, expMax, metered, 1f / 3f)
        assertEquals(400, iso)
        assertEquals(10_000_000L, exp)
    }

    @Test
    fun derivedShutterIsCappedByFrameDuration() {
        // 暗场景：推出来要 1/10s，但录像 30fps 时快门不能慢于 1/30s
        val metered = 100.0 * 100_000_000
        val c = CameraControls(isoAuto = false, iso = 100, shutterAuto = true)
        val (_, exp) = resolveExposure(c, isoMin, isoMax, expMin, 1_000_000_000L / 30, metered, 1f / 3f)
        assertEquals(33_333_333L, exp)
    }

    @Test
    fun fullyManualIgnoresMetering() {
        val c = CameraControls(isoAuto = false, iso = 800, shutterAuto = false, exposureNs = 4_000_000L, evIndex = 6)
        assertEquals(800 to 4_000_000L, resolveExposure(c, isoMin, isoMax, expMin, expMax, 1.0, 1f / 3f))
    }

    private val sizes = listOf(
        4000 to 3000, 1920 to 1080, 1440 to 1080, 1280 to 720, 3840 to 2160, 640 to 480, 1080 to 1080,
    )

    @Test
    fun streamSizePrefersExactShortSide() {
        assertEquals(1920 to 1080, pickStreamSize(sizes, { it.first }, { it.second }, 16f / 9f, 1080))
        assertEquals(3840 to 2160, pickStreamSize(sizes, { it.first }, { it.second }, 16f / 9f, 2160))
        assertEquals(1440 to 1080, pickStreamSize(sizes, { it.first }, { it.second }, 4f / 3f, 1080))
    }

    @Test
    fun streamSizeFallsBackToLargestBelowTarget() {
        val small = listOf(1280 to 720, 640 to 360)
        assertEquals(1280 to 720, pickStreamSize(small, { it.first }, { it.second }, 16f / 9f, 1080))
        assertNull(pickStreamSize(emptyList<Pair<Int, Int>>(), { it.first }, { it.second }, 16f / 9f, 1080))
    }

    @Test
    fun jpegSizeCapsAt13Megapixels() {
        val jpegs = listOf(8000 to 6000, 4000 to 3000, 3264 to 2448, 1920 to 1080)
        assertEquals(4000 to 3000, pickJpegSize(jpegs, { it.first }, { it.second }))
    }

    @Test
    fun zoomPresetsStayInRange() {
        assertEquals(listOf(0.6f, 1f, 2f, 3f, 5f), zoomPresets(0.6f, 10f))
        assertEquals(listOf(1f, 2f, 3f), zoomPresets(1f, 4f))
        assertEquals(listOf(1f), zoomPresets(1f, 1f))
    }

    @Test
    fun orientationSnapsWithHysteresis() {
        assertEquals(0, snapOrientation(30, 0))      // 还在 0° 的回差区里
        assertEquals(90, snapOrientation(80, 0))
        assertEquals(90, snapOrientation(130, 90))   // 刚过 135° 之前不跳
        assertEquals(180, snapOrientation(160, 90))
        assertEquals(270, snapOrientation(275, 0))
        assertEquals(0, snapOrientation(350, 270))
        assertEquals(90, snapOrientation(-1, 90))    // 平放（ORIENTATION_UNKNOWN）保持不变
    }

    @Test
    fun formatters() {
        assertEquals("1/50", formatShutter(20_000_000L))
        assertEquals("1/4000", formatShutter(250_000L))
        assertEquals("0.5\"", formatShutter(500_000_000L))
        assertEquals("±0", formatEv(0.01f))
        assertEquals("+0.7", formatEv(2f / 3f))
        assertEquals("-1.0", formatEv(-1f))
        assertEquals("∞", formatFocus(0f))
        assertEquals("0.50m", formatFocus(2f))
    }

    // —— 点击对焦的坐标换算：必须和渲染时的旋转/镜像互为逆变换 ——

    private val fullFrame = FrameGeometry(floatArrayOf(1080f, 1440f, 0f, 0f, 1f, 1f))

    @Test
    fun meteringUndoesSensorRotation() {
        // 后置 90°：直立画面的左上角来自缓冲的左下角
        val p = Metering.frameToBuffer(0f, 0f, fullFrame, 90, front = false)
        assertEquals(0f, p.x, 1e-6f)
        assertEquals(1f, p.y, 1e-6f)
        // 画面中心永远是缓冲中心
        val c = Metering.frameToBuffer(0.5f, 0.5f, fullFrame, 90, front = false)
        assertEquals(0.5f, c.x, 1e-6f)
        assertEquals(0.5f, c.y, 1e-6f)
    }

    @Test
    fun meteringUndoesFrontMirror() {
        // 前置 270° + 镜像：画面左上角 → 缓冲右下角（和 C++ stillInputMatrix 的单测一致）
        val p = Metering.frameToBuffer(0f, 0f, fullFrame, 270, front = true)
        assertEquals(1f, p.x, 1e-6f)
        assertEquals(1f, p.y, 1e-6f)
    }

    @Test
    fun meteringAccountsForAspectCrop() {
        // 4:3 直立图像裁成 1:1：画面顶边对应直立图像的 1/8 处
        val square = FrameGeometry(floatArrayOf(1080f, 1080f, 0f, 0.125f, 1f, 0.75f))
        val p = Metering.frameToBuffer(0.5f, 0f, square, 0, front = false)
        assertEquals(0.5f, p.x, 1e-6f)
        assertEquals(0.125f, p.y, 1e-6f)
    }
}
