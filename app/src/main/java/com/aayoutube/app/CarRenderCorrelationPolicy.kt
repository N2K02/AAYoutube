package com.aayoutube.app

/**
 * Read-only correlation of three DIFFERENT pieces of rendering evidence:
 *  - PixelCopy of Compose synthetic pattern within the app Window buffer;
 *  - PixelCopy of an independent synthetic SurfaceView buffer;
 *  - human reports about what the physical Android Auto screen displayed.
 *
 * No automated playback recovery is authorized by these signals. A successful
 * PixelCopy is not proof of transfer to the head unit; a dark readback is not
 * proof that the physical screen is black.
 */
internal data class CarRenderEvidence(
    val active: Boolean,
    val experimental: Boolean,
    val windowBuffer: Boolean?,
    val windowSeen: Boolean?,
    val surfaceBuffer: Boolean?,
    val surfaceSeen: Boolean?,
    val surfaceFrames: Int,
    val frameHealth: String,
    val checks: Int,
    val repairs: Int,
    val frameSize: String,
    val playerSize: String,
)

internal enum class CarRenderFinding {
    DISCONNECTED,
    DISABLED,
    AWAITING_COMPOSE_OBSERVATION,
    COMPOSE_PRESENTATION_SUSPECTED,
    SECONDARY_LAYER_PRESENTATION_SUSPECTED,
    INCONSISTENT_HUMAN_OBSERVATIONS,
    BUFFER_READBACK_MISMATCH,
    AWAITING_DIRECT_SURFACE_OBSERVATION,
    SYNTHETIC_LAYERS_OBSERVED,
}

internal data class CarRenderAssessment(
    val finding: CarRenderFinding,
    val summary: String,
    val nextStep: String,
)

internal object CarRenderCorrelationPolicy {
    fun assess(input: CarRenderEvidence): CarRenderAssessment = when {
        !input.active -> CarRenderAssessment(
            CarRenderFinding.DISCONNECTED,
            "No active car-video window",
            "Connect Android Auto with the experimental option enabled.",
        )
        !input.experimental -> CarRenderAssessment(
            CarRenderFinding.DISABLED,
            "Standard car projection; experiments inactive",
            "Enable experimental video, then reconnect Android Auto.",
        )
        input.windowSeen == false && input.surfaceSeen == true -> CarRenderAssessment(
            CarRenderFinding.INCONSISTENT_HUMAN_OBSERVATIONS,
            "Moving surface was visible but Compose bars were not",
            "Repeat both paused tests. Window/layer order and host transitions may differ.",
        )
        input.windowSeen == false -> CarRenderAssessment(
            CarRenderFinding.COMPOSE_PRESENTATION_SUSPECTED,
            "Compose test bars were not seen on the head unit",
            if (input.windowBuffer == true)
                "App Window contains synthetic bars; investigate host transfer or visibility."
            else
                "Confirm the car window is visible and repeat the Compose pattern test.",
        )
        input.windowSeen == true && input.surfaceSeen == false -> CarRenderAssessment(
            CarRenderFinding.SECONDARY_LAYER_PRESENTATION_SUSPECTED,
            "Compose bars appeared, but moving SurfaceView did not",
            if (input.surfaceBuffer == true)
                "Surface buffer has bars. Investigate independent-layer host composition."
            else
                "Check SurfaceView attachment, readback and car host layer support.",
        )
        input.windowSeen == true && input.surfaceSeen == true -> CarRenderAssessment(
            CarRenderFinding.SYNTHETIC_LAYERS_OBSERVED,
            "Both synthetic rendering paths were seen on the car",
            "Check actual Chromium video separately; synthetic success does not prove video paint.",
        )
        input.windowSeen == true && input.surfaceBuffer == false &&
            input.surfaceSeen == null -> CarRenderAssessment(
            CarRenderFinding.BUFFER_READBACK_MISMATCH,
            "Compose bars were seen; direct surface buffer sample was inconclusive",
            "Run the moving-surface test and report what appears on the head unit.",
        )
        input.windowSeen == true && input.windowBuffer == false -> CarRenderAssessment(
            CarRenderFinding.BUFFER_READBACK_MISMATCH,
            "Compose bars were visible despite a negative window readback",
            "PixelCopy may sample a different layer; trust direct visual observation.",
        )
        input.windowSeen == true -> CarRenderAssessment(
            CarRenderFinding.AWAITING_DIRECT_SURFACE_OBSERVATION,
            "Compose car-window rendering was observed",
            "Run Test direct surface while paused; report moving-image visibility.",
        )
        else -> CarRenderAssessment(
            CarRenderFinding.AWAITING_COMPOSE_OBSERVATION,
            "Window and direct-surface test results are not yet conclusive",
            "Pause video, run Show test bars on car, then report Visible or Not visible.",
        )
    }

    /**
     * Sanitized, intentionally compact text report suitable for clipboard
     * or pasted into a GitHub issue. No owner tokens, URLs, YouTube IDs,
     * account names, pixel arrays or screenshots are included.
     */
    fun report(input: CarRenderEvidence): String {
        val decision = assess(input)
        fun flag(value: Boolean?): String = when (value) {
            true -> "yes"
            false -> "no"
            null -> "not measured"
        }
        val sizePattern = Regex("""[0-9]{1,5}[×x][0-9]{1,5}""")
        fun safeSize(value: String): String =
            if (sizePattern.matches(value)) value else "unknown"
        val safeFrame = safeSize(input.frameSize)
        val safePlayer = safeSize(input.playerSize)
        // Frame health is an internal enum description. Reject everything
        // else instead of allowing unexpected strings into the clipboard.
        val allowedHealth = setOf(
            "not measured", "no video", "paused", "buffering or seeking",
            "awaiting baseline", "too soon", "counter reset",
            "frames advancing", "decoder stall suspected",
            "playback stall suspected", "frame counter unavailable",
            "monitoring disabled after fallback",
        )
        val health = input.frameHealth.lowercase()
            .takeIf { it in allowedHealth } ?: "unrecognized"
        return buildString {
            appendLine("AAYouTube Experimental Car Rendering Report")
            appendLine("Active: ${input.active}; experimental: ${input.experimental}")
            appendLine("Frame: $safeFrame; WebView: $safePlayer")
            appendLine("Surface recovery checks: ${input.checks}; repairs: ${input.repairs}")
            appendLine("Decoder health: $health")
            appendLine("Compose bars in app buffer: ${flag(input.windowBuffer)}")
            appendLine("Compose bars seen on car: ${flag(input.windowSeen)}")
            appendLine("Direct surface bars in buffer: ${flag(input.surfaceBuffer)}")
            appendLine("Moving surface seen on car: ${flag(input.surfaceSeen)}")
            appendLine("Direct surface submitted frames: ${input.surfaceFrames}")
            appendLine("Finding: ${decision.finding}")
            appendLine("Summary: ${decision.summary}")
            appendLine("Next: ${decision.nextStep}")
            append("Diagnostic only; no physical screen or live video success inferred.")
        }
    }
}
