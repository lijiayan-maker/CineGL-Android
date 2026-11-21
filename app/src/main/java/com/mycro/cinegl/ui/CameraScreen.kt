package com.mycro.cinegl.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mycro.cinegl.camera.formatEv
import com.mycro.cinegl.camera.formatFocus
import com.mycro.cinegl.camera.formatShutter
import com.mycro.cinegl.ui.components.AudioMeter
import com.mycro.cinegl.ui.components.AutoButton
import com.mycro.cinegl.ui.components.CineIcons
import com.mycro.cinegl.ui.components.GalleryButton
import com.mycro.cinegl.ui.components.LookButton
import com.mycro.cinegl.ui.components.ResetToolButton
import com.mycro.cinegl.ui.components.RoundIconButton
import com.mycro.cinegl.ui.components.RulerDial
import com.mycro.cinegl.ui.components.ShutterButton
import com.mycro.cinegl.ui.components.TextTabs
import com.mycro.cinegl.ui.components.ToolButton
import com.mycro.cinegl.ui.components.isoLabel
import com.mycro.cinegl.ui.theme.CineColors
import kotlinx.coroutines.delay
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onPermissions(
            result[Manifest.permission.CAMERA] == true || granted(context, Manifest.permission.CAMERA),
            result[Manifest.permission.RECORD_AUDIO] == true || granted(context, Manifest.permission.RECORD_AUDIO),
        )
    }
    LaunchedEffect(Unit) {
        val cam = granted(context, Manifest.permission.CAMERA)
        val mic = granted(context, Manifest.permission.RECORD_AUDIO)
        if (cam && mic) vm.onPermissions(true, true)
        else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) = vm.onDeviceOrientation(orientation)
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importCube)
    }

    Box(Modifier.fillMaxSize().background(CineColors.Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(state, vm)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.cameraPermission) {
                    PreviewArea(state, vm, Modifier.fillMaxSize().padding(horizontal = 6.dp))
                } else {
                    PermissionHint {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                }
                PanelOverlay(state, vm, Modifier.align(Alignment.BottomCenter)) {
                    importLauncher.launch(arrayOf("*/*"))
                }
            }
            ToolRow(state, vm)
            DialArea(state, vm)
            BottomBar(state, vm) {
                val uri = state.lastUri ?: return@BottomBar
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, if (state.lastIsVideo) "video/*" else "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    vm.toast("没有可以打开它的应用")
                }
            }
        }
        Toast(state.message, state.messageId, Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
    }
}

/** 影调 / 设置面板从底部滑上来，盖在预览下半部分。 */
@Composable
private fun PanelOverlay(state: CameraUiState, vm: CameraViewModel, modifier: Modifier, onImport: () -> Unit) {
    AnimatedVisibility(
        visible = state.panel != null,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        when (state.panel) {
            Panel.LOOKS -> LookPanel(state, vm, onImport = onImport)
            Panel.SETTINGS -> SettingsPanel(state, vm)
            null -> Unit
        }
    }
}

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun TopBar(state: CameraUiState, vm: CameraViewModel) {
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
        if (state.mode == CaptureMode.VIDEO && state.micPermission) {
            val levels = vm.audioLevels.collectAsStateWithLifecycle()
            AudioMeter(levels, Modifier.align(Alignment.CenterStart))
        }
        TextTabs(
            items = CaptureMode.entries,
            selected = state.mode,
            label = { it.label },
            enabled = !state.recording,
            onSelect = vm::setMode,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            if (state.caps?.hasFlash == true) {
                RoundIconButton(CineIcons.Torch, active = state.controls.torch) {
                    vm.updateControls { it.copy(torch = !it.torch) }
                }
            }
            RoundIconButton(CineIcons.Flip, enabled = !state.recording, onClick = vm::flipCamera)
            RoundIconButton(CineIcons.More, active = state.panel == Panel.SETTINGS) { vm.togglePanel(Panel.SETTINGS) }
        }
    }
}

@Composable
private fun ToolRow(state: CameraUiState, vm: CameraViewModel) {
    val c = state.controls
    val r = state.readout
    val caps = state.caps
    // 按权重平分宽度：384dp 的窄屏也放得下 5 个工具 + 重置，不需要横向滚动
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Tool.entries.forEach { tool ->
            val (value, manual) = when (tool) {
                Tool.FOCUS -> (if (c.focusAuto) (if (r.afLocked) "AF-L" else "AF") else formatFocus(c.focusDiopters)) to !c.focusAuto
                Tool.WB -> (if (c.wbAuto) "AWB" else "${c.kelvin.roundToInt()}K") to !c.wbAuto
                Tool.ISO -> (if (c.isoAuto) "A ${r.iso}" else "${c.iso}") to !c.isoAuto
                Tool.SHUTTER -> (if (c.shutterAuto) "A ${formatShutter(r.exposureNs)}" else formatShutter(c.exposureNs)) to !c.shutterAuto
                Tool.EV -> formatEv(c.evIndex * (caps?.evStep ?: 0f)) to (c.evIndex != 0)
            }
            ToolButton(
                icon = when (tool) {
                    Tool.FOCUS -> CineIcons.Focus
                    Tool.WB -> CineIcons.WhiteBalance
                    Tool.SHUTTER -> CineIcons.Shutter
                    Tool.EV -> CineIcons.Exposure
                    Tool.ISO -> null
                },
                iconText = if (tool == Tool.ISO) "ISO" else null,
                label = tool.label,
                value = value,
                selected = state.activeTool == tool,
                manual = manual,
                onClick = { vm.toggleTool(tool) },
                modifier = Modifier.weight(1f),
            )
        }
        ResetToolButton(enabled = state.canResetControls, onClick = vm::resetControls, modifier = Modifier.weight(0.8f))
    }
}

/** 选中某个工具后出现的刻度盘。 */
@Composable
private fun DialArea(state: CameraUiState, vm: CameraViewModel) {
    val tool = state.activeTool
    val caps = state.caps
    AnimatedVisibility(visible = tool != null && caps != null) {
        if (tool == null || caps == null) return@AnimatedVisibility
        val c = state.controls
        val r = state.readout
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (tool) {
                    Tool.ISO -> {
                        val lo = log2(caps.isoRange.lower.toFloat())
                        val hi = log2(caps.isoRange.upper.toFloat())
                        val iso = if (c.isoAuto) r.iso.takeIf { it > 0 } ?: c.iso else c.iso
                        AutoButton(c.isoAuto, caps.manualSensor) { vm.setAuto(Tool.ISO, !c.isoAuto) }
                        Spacer(Modifier.width(12.dp))
                        RulerDial(
                            value = log2(iso.toFloat()).coerceIn(lo, hi), range = lo..hi, step = 1f / 3f, majorEvery = 3,
                            enabled = caps.manualSensor,
                            label = { "ISO ${isoLabel(it)}" }, tickLabel = ::isoLabel,
                            onValueChange = { v -> vm.updateControls { it.copy(isoAuto = false, iso = 2f.pow(v).roundToInt()) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Tool.SHUTTER -> {
                        val maxNs = if (state.mode == CaptureMode.VIDEO) 1_000_000_000L / state.fps else 66_666_666L
                        val lo = log2(caps.exposureRange.lower / 1e9f)
                        val hi = log2(minOf(caps.exposureRange.upper, maxNs) / 1e9f)
                        val ns = if (c.shutterAuto) r.exposureNs.takeIf { it > 0 } ?: c.exposureNs else c.exposureNs
                        AutoButton(c.shutterAuto, caps.manualSensor) { vm.setAuto(Tool.SHUTTER, !c.shutterAuto) }
                        Spacer(Modifier.width(12.dp))
                        RulerDial(
                            value = log2(ns / 1e9f).coerceIn(lo, hi), range = lo..hi, step = 1f / 3f, majorEvery = 3,
                            enabled = caps.manualSensor,
                            label = { "快门 ${formatShutter((2f.pow(it) * 1e9f).toLong())}" },
                            tickLabel = { formatShutter((2f.pow(it) * 1e9f).toLong()) },
                            onValueChange = { v -> vm.updateControls { it.copy(shutterAuto = false, exposureNs = (2f.pow(v) * 1e9f).roundToLong()) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Tool.EV -> {
                        val step = caps.evStep
                        val enabled = caps.evRange.upper > caps.evRange.lower
                        AutoButton(c.evIndex == 0, enabled, text = "0") { vm.setAuto(Tool.EV, true) }
                        Spacer(Modifier.width(12.dp))
                        RulerDial(
                            value = c.evIndex * step, range = (caps.evRange.lower * step)..(caps.evRange.upper * step),
                            step = step, majorEvery = (1f / step).roundToInt().coerceAtLeast(1), enabled = enabled,
                            label = { "曝光补偿 ${formatEv(it)} EV" }, tickLabel = { formatEv(it) },
                            onValueChange = { v -> vm.updateControls { it.copy(evIndex = (v / step).roundToInt()) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Tool.FOCUS -> {
                        val maxD = caps.minFocusDiopters.coerceAtLeast(0.1f)
                        val d = if (c.focusAuto) r.focusDiopters else c.focusDiopters
                        AutoButton(c.focusAuto, caps.manualFocus) { vm.setAuto(Tool.FOCUS, !c.focusAuto) }
                        Spacer(Modifier.width(12.dp))
                        RulerDial(
                            value = d.coerceIn(0f, maxD), range = 0f..maxD, step = maxD / 40f, majorEvery = 10,
                            enabled = caps.manualFocus,
                            label = { if (caps.manualFocus) "对焦 ${formatFocus(it)}" else "此镜头不支持手动对焦" },
                            tickLabel = ::formatFocus,
                            onValueChange = { v -> vm.updateControls { it.copy(focusAuto = false, focusDiopters = v) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Tool.WB -> {
                        AutoButton(c.wbAuto, true) { vm.setAuto(Tool.WB, !c.wbAuto) }
                        Spacer(Modifier.width(12.dp))
                        RulerDial(
                            value = c.kelvin, range = 2000f..10000f, step = 100f, majorEvery = 10, enabled = true,
                            label = { "色温 ${it.roundToInt()}K" }, tickLabel = { "${(it / 1000).roundToInt()}k" },
                            onValueChange = { v -> vm.updateControls { it.copy(wbAuto = false, kelvin = v) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (tool == Tool.WB) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("色调", color = CineColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                    Slider(
                        value = c.tint * 10f,
                        onValueChange = { v -> vm.updateControls { it.copy(wbAuto = false, tint = v / 10f) } },
                        valueRange = -10f..10f,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFFD16BA5),
                            inactiveTrackColor = Color(0xFF6BD18A),
                        ),
                    )
                    Text("%+.1f".format(c.tint * 10f), color = CineColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(40.dp).padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun BottomBar(state: CameraUiState, vm: CameraViewModel, onOpenLast: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryButton(state.lastThumb, onOpenLast)
        ShutterButton(video = state.mode == CaptureMode.VIDEO, recording = state.recording, busy = state.busy, onClick = vm::onShutter)
        val look = state.currentLook
        LookButton(look?.let { state.lookThumbs[it.index] }, look?.name ?: "影调") { vm.togglePanel(Panel.LOOKS) }
    }
}

@Composable
private fun PermissionHint(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("需要相机权限才能取景", color = CineColors.TextPrimary, fontSize = 15.sp)
        Text(
            "授权",
            color = CineColors.Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRequest)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Toast(message: String?, id: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (message == null) return@LaunchedEffect
        visible = true
        delay(2200)
        visible = false
    }
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            message.orEmpty(),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
