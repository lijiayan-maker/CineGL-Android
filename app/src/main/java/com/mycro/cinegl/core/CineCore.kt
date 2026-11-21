package com.mycro.cinegl.core

import java.nio.ByteBuffer

/** 信号编码，数值和 C++ 的 cinegl::Encoding 一致。 */
object Encoding {
    const val REC709 = 0
    const val CINELOG = 1
}

/**
 * 一帧的渲染参数。所有效果强度都是 0..1（UI 上的 0..10 除以 10）。
 * 通过 [pack] 打包成 FloatArray 传给 C++，下标与 cinegl_jni.cpp 的 unpack() 一一对应。
 */
data class RenderParams(
    val sourceEncoding: Int = Encoding.REC709,
    val workingEncoding: Int = Encoding.REC709,
    val lookStrength: Float = 1f,
    val halation: Float = 0f,
    val mist: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = 0.3f,
    val vignette: Float = 0f,
    val aberration: Float = 0f,
    val wbR: Float = 1f,
    val wbG: Float = 1f,
    val wbB: Float = 1f,
    val showSignal: Boolean = false,
    val zebra: Boolean = false,
    val zebraThreshold: Float = 0.95f,
    val peaking: Boolean = false,
    val waveform: Boolean = false,
) {
    fun pack(): FloatArray = floatArrayOf(
        sourceEncoding.toFloat(), workingEncoding.toFloat(), lookStrength, halation, mist, grain, grainSize,
        vignette, aberration, wbR, wbG, wbB, showSignal.f, zebra.f, zebraThreshold, peaking.f, waveform.f,
    )

    private val Boolean.f get() = if (this) 1f else 0f
}

/** 画面几何（C++ computeFrame 的结果）。 */
class FrameGeometry(private val raw: FloatArray) {
    val width: Int get() = raw[0].toInt()
    val height: Int get() = raw[1].toInt()
    val cropX: Float get() = raw[2]
    val cropY: Float get() = raw[3]
    val cropW: Float get() = raw[4]
    val cropH: Float get() = raw[5]
    fun cropMatrix(): FloatArray = CineCore.cropMatrix(raw)
}

/** 输出方案（C++ planVideoOutput / planPhotoOutput 的结果）。 */
class OutputPlan(raw: IntArray) {
    val width = raw[0]
    val height = raw[1]
    val rotationCw = raw[2]
    val orientationHint = raw[3]
}

/**
 * 影调信息。name 是中文名（导入的 LUT 为文件名），nameEn 是英文名（导入的 LUT 为 .cube 里的 TITLE，可能为空）。
 * 界面语言跟随系统：简体中文用中文名，其他语言一律用英文名。
 */
data class LookInfo(val index: Int, val id: String, val name: String, val nameEn: String, val isCube: Boolean) {
    /** 卡片大字、角标、面板标题用的名字。导入的 LUT 两种语言都用文件名（那是用户自己起的名字）。 */
    fun title(zh: Boolean): String = if (isCube || zh) name else nameEn

    /** 卡片小字：中文界面配英文名；导入的 LUT 配 TITLE，没有 TITLE 返回 null（界面显示「导入的 LUT」）；英文界面的内置影调不配小字。 */
    fun subtitle(zh: Boolean): String? = when {
        isCube -> nameEn.ifEmpty { null }
        zh -> nameEn
        else -> null
    }

    /** 缩略图出来之前影调按钮里的字：中文取前两个字，英文取前两个单词的首字母。 */
    fun badge(zh: Boolean): String {
        val t = title(zh)
        if (zh) return t.take(2)
        val initials = t.split(' ', '-', '_')
            .filter { it.firstOrNull()?.isLetterOrDigit() == true }
            .take(2)
            .joinToString("") { it.first().uppercase() }
        return initials.ifEmpty { t.take(2) }
    }
}

/** .cube 解析失败的原因，code 与 C++ cinegl::CubeError 一致（文案在界面层按系统语言生成）。 */
enum class CubeError(val code: Int) {
    UNKNOWN(-1),
    SIZE_OUT_OF_RANGE(1),
    UNSUPPORTED_1D(2),
    BAD_DOMAIN(3),
    BAD_DATA(4),
    DATA_BEFORE_SIZE(5),
    COUNT_MISMATCH(6),
    DOMAIN_ORDER(7),
    MISSING_SIZE(8),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** 上一次 .cube 解析失败的详情：line 为出错行号，count / expected 为实际与应有的数据行数。 */
data class CubeFailure(val error: CubeError, val line: Int, val count: Int, val expected: Int)

/**
 * C++ 渲染核心的 JNI 入口。
 *  - 影调 / 几何 / 色彩工具：纯 CPU，任意线程可调
 *  - engine*：必须在 GL 线程、且有当前 EGL 上下文时调用
 */
object CineCore {
    init {
        System.loadLibrary("cinegl_jni")
    }

    const val PLANE_SIGNAL = 0
    const val PLANE_GRADED = 1
    const val LUT_SIZE = 33

    fun looks(): List<LookInfo> = (0 until lookCount()).mapNotNull { i ->
        lookInfo(i)?.let { LookInfo(i, it[0], it[1], it[2], it[3] == "1") }
    }

    fun frameGeometry(bufferW: Int, bufferH: Int, sensorOrientation: Int, aspect: Int) =
        FrameGeometry(computeFrame(bufferW, bufferH, sensorOrientation, aspect))

    fun lastCubeFailure(): CubeFailure = lastCubeError().let { CubeFailure(CubeError.of(it[0]), it[1], it[2], it[3]) }

    // ---- 影调库 ----
    external fun lookCount(): Int
    external fun lookInfo(index: Int): Array<String>?
    external fun bakeLook(index: Int, encoding: Int, size: Int): FloatArray?
    external fun bakeNeutral(encoding: Int, size: Int): FloatArray
    /** 解析 .cube 并注册成影调，返回索引；失败返回 -1，原因见 [lastCubeFailure]。 */
    external fun registerCubeLook(id: String, name: String, text: String): Int
    /** [错误码, 行号, 实际行数, 应有行数]，用 [lastCubeFailure] 取更方便。 */
    external fun lastCubeError(): IntArray

    // ---- 几何 / 色彩工具 ----
    external fun computeFrame(bufferW: Int, bufferH: Int, sensorOrientation: Int, aspect: Int): FloatArray
    external fun cropMatrix(geometry: FloatArray): FloatArray
    external fun stillInputMatrix(sensorOrientation: Int, front: Boolean): FloatArray
    external fun outputMatrix(rotationCw: Int, unmirror: Boolean, flipY: Boolean): FloatArray
    external fun planVideoOutput(frameW: Int, frameH: Int, deviceOrientation: Int): IntArray
    external fun planPhotoOutput(frameW: Int, frameH: Int, deviceOrientation: Int): IntArray
    external fun whiteBalanceGains(kelvin: Float, tint: Float): FloatArray
    /** Camera2 TONEMAP_CURVE 用的 CineLog 曲线：(Pin, Pout) 交错。 */
    external fun cineLogCurve(points: Int): FloatArray

    // ---- GL 引擎 ----
    external fun engineCreate(): Long
    external fun engineInit(handle: Long): Boolean
    external fun engineRelease(handle: Long)
    external fun engineUploadNeutralLut(handle: Long, data: FloatArray, size: Int)
    external fun engineUploadLookLut(handle: Long, index: Int, data: FloatArray, size: Int)
    external fun engineClearLookLuts(handle: Long)
    external fun engineSelectLook(handle: Long, index: Int)
    external fun engineRenderFrame(
        handle: Long, texture: Int, oes: Boolean, texMatrix: FloatArray, frameW: Int, frameH: Int,
        params: FloatArray, frameIndex: Int,
    )
    external fun enginePresent(
        handle: Long, viewW: Int, viewH: Int, x: Float, y: Float, w: Float, h: Float, cornerRadius: Float,
        topInset: Float, params: FloatArray, time: Float,
    )
    external fun engineDrawToSurface(handle: Long, plane: Int, matrix: FloatArray, width: Int, height: Int)
    external fun engineProcessStill(
        handle: Long, texture: Int, texMatrix: FloatArray, frameW: Int, frameH: Int, params: FloatArray,
        plane: Int, outMatrix: FloatArray, outW: Int, outH: Int, dst: ByteBuffer,
    ): Boolean
    external fun engineRenderThumbnails(
        handle: Long, looks: IntArray, thumbW: Int, thumbH: Int, columns: Int, dst: ByteBuffer,
    ): Boolean
}
