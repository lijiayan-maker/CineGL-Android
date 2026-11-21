package com.mycro.cinegl.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mycro.cinegl.render.FrameFit
import com.mycro.cinegl.ui.components.InfoChip
import com.mycro.cinegl.ui.components.ZoomPills
import com.mycro.cinegl.ui.theme.CineColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 预览区：SurfaceView（GL 画在这里）+ Compose 叠加层（网格、水平仪、对焦框、角标、变焦）。
 * 画面框用 [FrameFit] 计算，和 GL 端上屏用的是同一个函数，所以叠加层能严丝合缝地贴着画面。
 */
@Composable
fun PreviewArea(state: CameraUiState, vm: CameraViewModel, modifier: Modifier = Modifier) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { 16.dp.toPx() }
    val chipRowPx = with(density) { 40.dp.toPx() }   // 左上角角标那一行（含上边距）的高度
    LaunchedEffect(radiusPx, chipRowPx) { vm.setOverlayMetrics(radiusPx, chipRowPx) }
    val frame = remember(boxSize, state.frameWidth, state.frameHeight) {
        FrameFit.fit(state.frameWidth.toFloat(), state.frameHeight.toFloat(), boxSize.width.toFloat(), boxSize.height.toFloat())
    }
    val latest by rememberUpdatedState(state)

    Box(
        modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(frame) {
                detectTapGestures(
                    onTap = { p ->
                        val s = latest
                        if (s.panel != null || s.activeTool != null) {
                            vm.closePanels()
                        } else if (frame.contains(p.x, p.y)) {
                            vm.tapToFocus((p.x - frame.left) / frame.width(), (p.y - frame.top) / frame.height())
                        }
                    },
                    onLongPress = { vm.resetFocus() },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val range = latest.caps?.zoomRange ?: return@detectTransformGestures
                    if (zoom != 1f) vm.updateControls { c -> c.copy(zoom = (c.zoom * zoom).coerceIn(range.lower, range.upper)) }
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            vm.onSurfaceCreated(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            vm.onSurfaceChanged(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            vm.onSurfaceDestroyed()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (frame.width() <= 0f) return@Box
        val roll = rememberDeviceRoll(state.assist.level)
        Canvas(Modifier.fillMaxSize()) {
            if (state.assist.grid) {
                val c = Color.White.copy(alpha = 0.28f)
                for (i in 1..2) {
                    val x = frame.left + frame.width() * i / 3f
                    val y = frame.top + frame.height() * i / 3f
                    drawLine(c, Offset(x, frame.top), Offset(x, frame.bottom), 1f)
                    drawLine(c, Offset(frame.left, y), Offset(frame.right, y), 1f)
                }
            }
            if (state.assist.level) {
                // 水平仪：中间那条线始终和真实世界的地平线平行，对齐时变绿
                val angle = roll.value
                val nearest = (angle / 90f).roundToInt() * 90f
                val level = abs(angle - nearest) < 1f
                val color = if (level) CineColors.MeterGreen else Color.White.copy(alpha = 0.8f)
                val center = Offset(frame.centerX(), frame.centerY())
                val half = frame.width() * 0.12f
                val gap = 10.dp.toPx()
                rotate(angle, center) {
                    drawLine(color, Offset(center.x - half, center.y), Offset(center.x - gap, center.y), 2.dp.toPx())
                    drawLine(color, Offset(center.x + gap, center.y), Offset(center.x + half, center.y), 2.dp.toPx())
                }
                rotate(nearest, center) {
                    val ref = Color.White.copy(alpha = 0.45f)
                    drawLine(ref, Offset(center.x - half - 14.dp.toPx(), center.y), Offset(center.x - half - 4.dp.toPx(), center.y), 1.5.dp.toPx())
                    drawLine(ref, Offset(center.x + half + 4.dp.toPx(), center.y), Offset(center.x + half + 14.dp.toPx(), center.y), 1.5.dp.toPx())
                }
            }
        }

        val frameLeftDp = with(density) { frame.left.toDp() }
        val frameTopDp = with(density) { frame.top.toDp() }
        val frameWidthDp = with(density) { frame.width().toDp() }
        val frameHeightDp = with(density) { frame.height().toDp() }

        // 左上角角标
        Row(
            Modifier.offset(frameLeftDp + 12.dp, frameTopDp + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.logOn) {
                InfoChip("LOG", CineColors.Accent)
                InfoChip(if (state.burnIn) "LUT 烧录" else "LUT 仅监看", if (state.burnIn) CineColors.Record else CineColors.TextSecondary, outlined = true)
            }
            if (state.showSignal && state.logOn) InfoChip("Log 原片", Color.White)
            state.currentLook?.let { InfoChip(it.name, Color.White) }
        }

        // 录制计时
        if (state.recording) {
            RecordingTimer(
                state.recordStartMs,
                Modifier
                    .offset(frameLeftDp, frameTopDp + 48.dp)
                    .width(frameWidthDp),
            )
        }

        // 对焦框
        FocusIndicator(state, frame.left, frame.top, frame.width(), frame.height())

        // 变焦
        val baseFocal = state.caps?.equivalentFocal ?: 24f
        Box(
            Modifier
                .offset(frameLeftDp, frameTopDp + frameHeightDp - 60.dp)
                .width(frameWidthDp),
            contentAlignment = Alignment.Center,
        ) {
            ZoomPills(state.zoomPresets, state.controls.zoom, baseFocal, onSelect = { z -> vm.updateControls { it.copy(zoom = z) } })
        }
    }
}

@Composable
private fun RecordingTimer(startMs: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((now - startMs) / 1000).coerceAtLeast(0)
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(CineColors.Record.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
            Text(
                "  %02d:%02d".format(seconds / 60, seconds % 60),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun FocusIndicator(state: CameraUiState, left: Float, top: Float, width: Float, height: Float) {
    val point = state.focusPoint ?: return
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state.focusEventId) {
        alpha.snapTo(1f)
        delay(1500)
        alpha.animateTo(0.35f, tween(400))
    }
    val density = LocalDensity.current
    val boxSize = 72.dp
    val half = with(density) { (boxSize / 2).toPx() }
    val x = left + point.first * width - half
    val y = top + point.second * height - half
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(boxSize)
            .alpha(alpha.value)
            .border(1.5.dp, CineColors.Accent, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (state.readout.afLocked && state.controls.focusAuto) {
            Text("AF-L", color = CineColors.Accent, fontSize = 9.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** 设备绕屏幕法线的旋转角（度），竖持端正时为 0。 */
@Composable
private fun rememberDeviceRoll(enabled: Boolean): State<Float> {
    val context = LocalContext.current
    val roll = remember { mutableFloatStateOf(0f) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val angle = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                val prev = roll.floatValue
                val delta = ((angle - prev + 540f) % 360f) - 180f   // 处理 ±180° 处的跳变
                roll.floatValue = ((prev + delta * 0.25f + 540f) % 360f) - 180f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return roll
}
