package com.aayoutube.app

/** Suppresses only known high-frequency non-actionable compositor diagnostics. */
internal object LogNoisePolicy {
    fun isGraphicsNoise(message: String): Boolean =
        message.trim().startsWith("Use dataspace ") && message.endsWith("as it is")
}
