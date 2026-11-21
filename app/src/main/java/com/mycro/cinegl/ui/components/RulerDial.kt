package com.mycro.cinegl.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mycro.cinegl.ui.theme.CineColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 横向刻度盘：左右拖动刻度来调值（像专业相机的拨轮）。
 * value / range / step 都在同一个「刻度域」里：ISO、快门用 log2 域（每格 1/3 档），色温用开尔文。
 */
@Composable
fun RulerDial(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    majorEvery: Int,
    enabled: Boolean,
    label: (Float) -> String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tickLabel: ((Float) -> String)? = null,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { 9.dp.toPx() }
    val current by rememberUpdatedState(value)
    val measurer = rememberTextMeasurer()
    val dragState = rememberDraggableState { delta ->
        if (!enabled || step <= 0f) return@rememberDraggableState
        onValueChange((current - delta / spacingPx * step).coerceIn(range.start, range.endInclusive))
    }
    val accent = if (enabled) CineColors.Accent else CineColors.TextSecondary

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label(value),
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .draggable(dragState, Orientation.Horizontal, enabled = enabled),
        ) {
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                if (step <= 0f) return@Canvas
                val center = size.width / 2f
                val halfTicks = (center / spacingPx).toInt() + 2
                val first = ceil((value - halfTicks * step) / step).toInt()
                val last = floor((value + halfTicks * step) / step).toInt()
                var lastLabelRight = Float.NEGATIVE_INFINITY
                for (i in first..last) {
                    val tickValue = i * step
                    if (tickValue < range.start - step * 0.01f || tickValue > range.endInclusive + step * 0.01f) continue
                    val x = center + (tickValue - value) / step * spacingPx
                    val major = majorEvery > 0 && abs(i % majorEvery) == 0
                    // 越靠边越淡，像刻度盘弯过去了
                    val fade = 1f - (abs(x - center) / center).coerceIn(0f, 1f)
                    val alpha = 0.25f + 0.75f * fade
                    val h = if (major) 16.dp.toPx() else 9.dp.toPx()
                    drawLine(
                        Color.White.copy(alpha = alpha * if (enabled) 1f else 0.4f),
                        Offset(x, 4.dp.toPx()),
                        Offset(x, 4.dp.toPx() + h),
                        strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    if (major && tickLabel != null) {
                        val layout = measurer.measure(
                            tickLabel(tickValue),
                            TextStyle(color = Color.White.copy(alpha = alpha * 0.8f), fontSize = 9.sp),
                        )
                        val left = x - layout.size.width / 2f
                        // 标签太长（比如 1/16384）就隔一个画一个，避免挤成一团
                        if (left > lastLabelRight + 6.dp.toPx()) {
                            drawText(layout, topLeft = Offset(left, 24.dp.toPx()))
                            lastLabelRight = left + layout.size.width
                        }
                    }
                }
                // 中心指针
                drawLine(
                    Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.3f))),
                    Offset(center, 0f),
                    Offset(center, 26.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 把 log2 刻度值格式化成整数（ISO）。 */
fun isoLabel(log2Value: Float): String = (Math.pow(2.0, log2Value.toDouble())).roundToInt().toString()
