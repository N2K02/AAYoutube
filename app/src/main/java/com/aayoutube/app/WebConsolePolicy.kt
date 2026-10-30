package com.aayoutube.app

/** Removes irrelevant YouTube console spam while keeping actionable errors. */
internal object WebConsolePolicy {
    private val inlineImage = Regex("(?i)data:image/[-+a-z0-9.]+;base64,[a-z0-9+/=]+")
    fun clean(message: String): String? {
        if (message.contains("was preloaded using link preload but not used")) return null
        if (message.contains("Loading the image 'data:image/")) return null
        return inlineImage.replace(message, "[INLINE_IMAGE]").take(600)
    }
}
