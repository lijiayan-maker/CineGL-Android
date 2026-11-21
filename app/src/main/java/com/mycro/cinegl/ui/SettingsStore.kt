package com.mycro.cinegl.ui

import android.content.Context

/** 记住用户上次的选择（模式、画幅、影调、效果……）。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cinegl", Context.MODE_PRIVATE)

    fun load(): CameraUiState {
        val d = CameraUiState()
        val e = Effects()
        val a = Assist()
        return d.copy(
            mode = enumOr(prefs.getString("mode", null), d.mode),
            aspect = enumOr(prefs.getString("aspect", null), d.aspect),
            resolution = enumOr(prefs.getString("resolution", null), d.resolution),
            fps = prefs.getInt("fps", d.fps),
            logMode = enumOr(prefs.getString("logMode", null), d.logMode),
            burnIn = prefs.getBoolean("burnIn", d.burnIn),
            lookId = prefs.getString("lookId", d.lookId) ?: d.lookId,
            effects = Effects(
                lookStrength = prefs.getFloat("fx.look", e.lookStrength),
                halation = prefs.getFloat("fx.halation", e.halation),
                mist = prefs.getFloat("fx.mist", e.mist),
                grain = prefs.getFloat("fx.grain", e.grain),
                grainSize = prefs.getFloat("fx.grainSize", e.grainSize),
                vignette = prefs.getFloat("fx.vignette", e.vignette),
                aberration = prefs.getFloat("fx.aberration", e.aberration),
            ),
            assist = Assist(
                grid = prefs.getBoolean("as.grid", a.grid),
                level = prefs.getBoolean("as.level", a.level),
                zebra = prefs.getBoolean("as.zebra", a.zebra),
                peaking = prefs.getBoolean("as.peaking", a.peaking),
                waveform = prefs.getBoolean("as.waveform", a.waveform),
            ),
        )
    }

    fun save(s: CameraUiState) {
        prefs.edit()
            .putString("mode", s.mode.name)
            .putString("aspect", s.aspect.name)
            .putString("resolution", s.resolution.name)
            .putInt("fps", s.fps)
            .putString("logMode", s.logMode.name)
            .putBoolean("burnIn", s.burnIn)
            .putString("lookId", s.lookId)
            .putFloat("fx.look", s.effects.lookStrength)
            .putFloat("fx.halation", s.effects.halation)
            .putFloat("fx.mist", s.effects.mist)
            .putFloat("fx.grain", s.effects.grain)
            .putFloat("fx.grainSize", s.effects.grainSize)
            .putFloat("fx.vignette", s.effects.vignette)
            .putFloat("fx.aberration", s.effects.aberration)
            .putBoolean("as.grid", s.assist.grid)
            .putBoolean("as.level", s.assist.level)
            .putBoolean("as.zebra", s.assist.zebra)
            .putBoolean("as.peaking", s.assist.peaking)
            .putBoolean("as.waveform", s.assist.waveform)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback
}
