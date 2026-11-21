package com.mycro.cinegl.render

import android.graphics.RectF

/**
 * 画面在预览区域里的位置：等比缩放、居中。
 * GL 上屏和 Compose 叠加层（网格、对焦框）都用它，保证两边算出来是同一个框。
 */
object FrameFit {
    fun fit(contentW: Float, contentH: Float, boxW: Float, boxH: Float): RectF {
        if (contentW <= 0f || contentH <= 0f || boxW <= 0f || boxH <= 0f) return RectF()
        val scale = minOf(boxW / contentW, boxH / contentH)
        val w = contentW * scale
        val h = contentH * scale
        val x = (boxW - w) / 2f
        val y = (boxH - h) / 2f
        return RectF(x, y, x + w, y + h)
    }
}
