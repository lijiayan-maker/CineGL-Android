package com.mycro.cinegl.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mycro.cinegl.core.LookInfo
import com.mycro.cinegl.ui.components.CineIcons
import com.mycro.cinegl.ui.components.InfoChip
import com.mycro.cinegl.ui.components.LabeledSlider
import com.mycro.cinegl.ui.components.RoundIconButton
import com.mycro.cinegl.ui.components.SectionTitle
import com.mycro.cinegl.ui.components.Segmented
import com.mycro.cinegl.ui.components.SettingSwitch
import com.mycro.cinegl.ui.theme.CineColors

@Composable
private fun PanelScaffold(
    title: String,
    trailing: String?,
    onClose: () -> Unit,
    modifier: Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(CineColors.Panel)
            // 吃掉面板内的点击，别穿透到预览上触发对焦
            .clickable(enabled = true, onClick = {}, indication = null, interactionSource = null)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = CineColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (trailing != null) {
                Text("  ·  $trailing", color = CineColors.TextSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            action?.invoke()
            RoundIconButton(CineIcons.Close, onClick = onClose)
        }
        content()
    }
}

/** 影调面板：实时缩略图选影调 + 影调强度 + 胶片效果。 */
@Composable
fun LookPanel(
    state: CameraUiState,
    vm: CameraViewModel,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelScaffold(
        "影调",
        state.currentLook?.name,
        vm::closePanels,
        modifier,
        action = { ResetAction(enabled = state.effects != Effects(), onClick = vm::resetEffects) },
    ) {
        LazyRow(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.looks, key = { it.id }) { look ->
                LookCard(look, state.lookThumbs[look.index], look.index == state.lookIndex) { vm.selectLook(look.index) }
            }
            item(key = "import") { ImportCard(onImport) }
        }
        Column(
            Modifier
                .padding(top = 8.dp)
                .height(260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val fx = state.effects
            LabeledSlider("影调强度", fx.lookStrength) { v -> vm.setEffects { it.copy(lookStrength = v) } }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.08f))
            LabeledSlider("光晕", fx.halation) { v -> vm.setEffects { it.copy(halation = v) } }
            LabeledSlider("柔光", fx.mist) { v -> vm.setEffects { it.copy(mist = v) } }
            LabeledSlider("颗粒", fx.grain) { v -> vm.setEffects { it.copy(grain = v) } }
            LabeledSlider("颗粒大小", fx.grainSize) { v -> vm.setEffects { it.copy(grainSize = v) } }
            LabeledSlider("暗角", fx.vignette) { v -> vm.setEffects { it.copy(vignette = v) } }
            LabeledSlider("色散", fx.aberration) { v -> vm.setEffects { it.copy(aberration = v) } }
        }
    }
}

/** 面板标题栏的「重置」：放在标题栏而不是滑块列表底部，不用滚动就能找到；都是默认值时置灰。 */
@Composable
private fun ResetAction(enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) CineColors.TextPrimary else CineColors.TextSecondary.copy(alpha = 0.35f)
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CineIcons.Reset, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("重置", color = color, fontSize = 13.sp)
    }
}

@Composable
private fun LookCard(look: LookInfo, thumb: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(width = 88.dp, height = 124.dp)
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) CineColors.Accent else Color.White.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick),
    ) {
        if (thumb != null) {
            Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(CineColors.SurfaceHigh))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(look.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(look.nameEn, color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, maxLines = 1)
        }
        if (look.isCube) {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { InfoChip("LUT", Color.White) }
        }
    }
}

@Composable
private fun ImportCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .size(width = 88.dp, height = 124.dp)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(CineIcons.Plus, null, tint = CineColors.TextPrimary, modifier = Modifier.size(26.dp))
        Text("导入 .cube", color = CineColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 设置面板：画幅、分辨率、帧率、Log、辅助功能。 */
@Composable
fun SettingsPanel(state: CameraUiState, vm: CameraViewModel, modifier: Modifier = Modifier) {
    PanelScaffold("设置", null, vm::closePanels, modifier) {
        Column(
            Modifier
                .height(420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("画幅")
            Segmented(Aspect.entries, state.aspect, { it.label }, vm::setAspect)
            if (state.mode == CaptureMode.VIDEO) {
                SectionTitle("分辨率 / 帧率")
                Segmented(Resolution.entries, state.resolution, { it.label }, vm::setResolution, { it == Resolution.FHD || state.uhdAvailable })
                Spacer(Modifier.height(8.dp))
                Segmented(state.availableFps, state.fps, { "$it fps" }, vm::setFps)
            }

            SectionTitle("Log")
            Segmented(LogMode.entries, state.logMode, { it.label }, vm::setLogMode, { it != LogMode.ISP || state.ispLogAvailable })
            Text(
                when (state.logMode) {
                    LogMode.OFF -> "相机直出的 Rec.709 画面直接进影调。"
                    LogMode.GPU -> "在 GPU 里把画面转成 CineLog 灰片：工作流和真 Log 一样，但宽容度不会凭空变大。"
                    LogMode.ISP -> "让相机 ISP 直接用 CineLog 曲线出片（TONEMAP_CURVE），高光层次比直出更多。"
                },
                color = CineColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            SettingSwitch(
                "LUT 烧录",
                "关闭后录的是 Log 原片，屏幕上仍然套着影调监看",
                state.burnIn,
                enabled = state.logOn,
                onChange = vm::setBurnIn,
            )
            SettingSwitch("显示 Log 原片", "屏幕上看灰片（不影响录制）", state.showSignal, enabled = state.logOn, onChange = vm::setShowSignal)

            SectionTitle("辅助")
            SettingSwitch("网格", checked = state.assist.grid) { v -> vm.setAssist { it.copy(grid = v) } }
            SettingSwitch("水平仪", checked = state.assist.level) { v -> vm.setAssist { it.copy(level = v) } }
            SettingSwitch("斑马纹", "标出过曝区域", state.assist.zebra) { v -> vm.setAssist { it.copy(zebra = v) } }
            SettingSwitch("峰值对焦", "合焦的边缘标红", state.assist.peaking) { v -> vm.setAssist { it.copy(peaking = v) } }
            SettingSwitch("波形图", "亮度分布示波器", state.assist.waveform) { v -> vm.setAssist { it.copy(waveform = v) } }
            if (state.caps?.previewStabilization == true) {
                SettingSwitch("防抖", checked = state.controls.stabilization) { v -> vm.updateControls { it.copy(stabilization = v) } }
            }
            Text(
                "CineGL · OpenGL ES 3.0 渲染学习项目",
                color = CineColors.TextSecondary.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).width(400.dp),
            )
        }
    }
}
