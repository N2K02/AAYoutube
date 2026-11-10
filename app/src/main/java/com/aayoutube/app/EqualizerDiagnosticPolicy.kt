package com.aayoutube.app

import kotlin.math.roundToInt

/** Fixed-schema EQ diagnostics: never include media IDs, URLs, PIDs or raw audio dumps. */
internal object EqualizerDiagnosticPolicy {
    fun settingSummary(s: SoundEqSettings): String {
        val safe = SoundEqPolicy.safe(s)
        val gains = safe.bands.joinToString(",") { it.roundToInt().toString() }
        val preset = safe.preset.takeIf {
            it == "Custom" || SoundEqPolicy.presets.any { curve -> curve.first == it }
        } ?: "Custom"
        return "enabled=${safe.enabled} preset=$preset " +
            "bandsDb=[$gains] bassPct=${(safe.bass * 100).roundToInt()} " +
            "spatial=${safe.mode.name} strengthPct=${(safe.intensity * 100).roundToInt()}"
    }

    fun outcome(
        attached: Boolean,
        scoped: Boolean,
        equalizer: Boolean,
        bass: Boolean,
        spatial: Boolean,
        hardwareBands: Int,
    ): String = "bound=$attached scope=${if (scoped) "own-session" else "global-mix"} " +
        "eq=$equalizer bass=$bass spatial=$spatial hardwareBands=$hardwareBands"

    fun discovery(hasGrant: Boolean, scanned: Boolean, verified: Boolean): String =
        "permission=${if (hasGrant) "granted" else "missing"} " +
            "dump=${if (scanned) "readable" else "unavailable"} " +
            "uniqueOwnSession=$verified"
}
