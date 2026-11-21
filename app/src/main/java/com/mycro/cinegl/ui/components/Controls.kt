package com.mycro.cinegl.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mycro.cinegl.R
import com.mycro.cinegl.record.AudioCapture
import com.mycro.cinegl.ui.theme.CineColors
import kotlin.math.roundToInt

private val noRipple = MutableInteractionSource()

@Composable
fun <T> TextTabs(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            val isSelected = item == selected
            Text(
                label(item),
                color = if (isSelected) CineColors.TextPrimary else CineColors.TextSecondary,
                fontSize = if (isSelected) 16.sp else 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable(enabled = enabled && !isSelected) { onSelect(item) },
            )
        }
    }
}

/** 左上角的 L/R 电平表：每声道 10 颗点，绿 → 黄 → 红。 */
@Composable
fun AudioMeter(levels: State<AudioCapture.Levels>, modifier: Modifier = Modifier) {
    val l by levels
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MeterRow("L", l.left)
        MeterRow("R", l.right)
    }
}

@Composable
private fun MeterRow(label: String, level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CineColors.TextSecondary, fontSize = 8.sp, modifier = Modifier.width(9.dp))
        Canvas(Modifier.size(width = 64.dp, height = 5.dp)) {
            val n = 10
            val gap = size.width / n
            val r = size.height / 2f
            for (i in 0 until n) {
                val lit = level >= (i + 1) / n.toFloat()
                val color = when {
                    !lit -> Color.White.copy(alpha = 0.18f)
                    i < 7 -> CineColors.MeterGreen
                    i < 9 -> CineColors.MeterYellow
                    else -> CineColors.MeterRed
                }
                drawCircle(color, r, Offset(gap * i + r, r))
            }
        }
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) CineColors.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> CineColors.TextSecondary.copy(alpha = 0.4f)
                active -> CineColors.Accent
                else -> CineColors.TextPrimary
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 底部工具栏的一项：图标（或文字图标）+ 名称 + 当前值。 */
@Composable
fun ToolButton(
    icon: ImageVector?,
    iconText: String?,
    label: String,
    value: String,
    selected: Boolean,
    manual: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(if (selected) CineColors.Accent else CineColors.TextPrimary, label = "toolTint")
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            } else {
                Text(iconText.orEmpty(), color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(label, color = CineColors.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        Text(
            value,
            color = if (manual) CineColors.Accent else CineColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 工具栏末尾的「重置」：有手动参数时可点，一点全部回到自动；都在自动时置灰。 */
@Composable
fun ResetToolButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(
        if (enabled) CineColors.TextPrimary else CineColors.TextSecondary.copy(alpha = 0.35f),
        label = "resetTint",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            Icon(CineIcons.Reset, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            stringResource(R.string.reset),
            color = if (enabled) CineColors.TextSecondary else tint,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun AutoButton(auto: Boolean, enabled: Boolean, text: String = "A", onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (auto) CineColors.Accent else CineColors.TextSecondary, CircleShape)
            .background(if (auto) CineColors.Accent.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (auto) CineColors.Accent else CineColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 快门：拍照是白色实心圆；录像是红色圆，录制中变成圆角方块。 */
@Composable
fun ShutterButton(video: Boolean, recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val innerSize by animateDpAsState(if (recording) 30.dp else 60.dp, label = "shutterInner")
    val corner by animateDpAsState(if (recording) 8.dp else 30.dp, label = "shutterCorner")
    Box(
        Modifier
            .size(78.dp)
            .border(3.5.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = noRipple, indication = null, enabled = !busy || recording, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(corner))
                .background(if (video) CineColors.Record else Color.White),
        )
        if (busy && !recording) {
            CircularProgressIndicator(Modifier.size(70.dp), color = CineColors.Accent, strokeWidth = 2.dp)
        }
    }
}

/** 影调按钮：彩色渐变圈 + 当前影调的实时缩略图；缩略图出来之前显示 badge（中文两个字 / 英文首字母）。 */
@Composable
fun LookButton(thumb: ImageBitmap?, badge: String, onClick: () -> Unit) {
    val ring = remember {
        Brush.sweepGradient(listOf(Color(0xFFFF8A3D), Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFF118AB2), Color(0xFFB5179E), Color(0xFFFF8A3D)))
    }
    Box(
        Modifier
            .size(56.dp)
            .border(BorderStroke(2.5.dp, ring), CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(CineColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp))
        } else {
            Text(badge, color = CineColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun GalleryButton(thumb: ImageBitmap?, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .background(CineColors.Surface)
            .clickable(enabled = thumb != null, onClick = onClick),
    ) {
        if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
    }
}

/** 变焦预设：显示成 35mm 等效焦距。 */
@Composable
fun ZoomPills(presets: List<Float>, current: Float, baseFocal: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    if (presets.size <= 1) return
    val active = presets.minByOrNull { kotlin.math.abs(it - current) } ?: 1f
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        presets.forEach { z ->
            val selected = z == active
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(z) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${(baseFocal * (if (selected) current else z)).roundToInt()}",
                        color = if (selected) CineColors.Accent else CineColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (selected) Text("mm", color = CineColors.Accent, fontSize = 7.sp)
                }
            }
        }
    }
}

@Composable
fun InfoChip(text: String, color: Color, outlined: Boolean = false) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .then(
                if (outlined) Modifier.border(1.dp, color, RoundedCornerShape(5.dp))
                else Modifier.background(color.copy(alpha = 0.18f)),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = CineColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
fun <T> Segmented(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    isEnabled: (T) -> Boolean = { true },
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CineColors.Surface)
            .padding(3.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            val enabled = isEnabled(item)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) CineColors.SurfaceHigh else Color.Transparent)
                    .clickable(enabled = enabled && !isSelected) { onSelect(item) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(item),
                    color = when {
                        !enabled -> CineColors.TextSecondary.copy(alpha = 0.35f)
                        isSelected -> CineColors.Accent
                        else -> CineColors.TextPrimary
                    },
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) CineColors.TextPrimary else CineColors.TextSecondary, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = CineColors.TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = CineColors.Accent, checkedThumbColor = Color.White),
        )
    }
}

/** 0..10 连续滑杆：左边名称，右边数值。 */
@Composable
fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = CineColors.TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.width(dimensionResource(R.dimen.slider_label_width)),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..10f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = CineColors.Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
        Text(
            "%.1f".format(value),
            color = CineColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(36.dp).padding(start = 8.dp),
        )
    }
}

/** 半透明圆角描边，用来画对焦框。 */
fun focusStroke(width: Float) = Stroke(width = width)
